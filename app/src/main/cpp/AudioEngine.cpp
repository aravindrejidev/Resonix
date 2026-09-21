#include "AudioEngine.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>

#define LOG_TAG "ResonixAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace resonix {

namespace {
// Generous fixed ceiling for one Oboe callback's frame count, so the
// scratch buffer is sized once (outside the real-time callback) and
// onAudioReady never allocates. LowLatency streams typically request
// far fewer frames per callback than this.
constexpr size_t kMaxScratchFrames = 8192;
}  // namespace

AudioEngine::AudioEngine()
        : mDecoder(std::make_unique<AudioDecoder>()),
          mRingBuffer(std::make_unique<RingBuffer>(kRingBufferCapacitySamples)) {}

AudioEngine::~AudioEngine() {
    stopDecodeThread();
    std::lock_guard<std::mutex> lock(mStreamLock);
    closeStreamLocked();
}

bool AudioEngine::playTrack(const std::string &filePath) {
    stopDecodeThread();  // decoder access must be exclusive to us here

    TrackInfo info;
    if (!mDecoder->open(filePath, &info)) {
        LOGE("Failed to open track: %s", filePath.c_str());
        return false;
    }

    double replayGainLinear = std::pow(10.0, info.trackGainDb / 20.0);
    mReplayGainMultiplier.store(replayGainLinear, std::memory_order_relaxed);

    {
        std::lock_guard<std::mutex> lock(mStreamLock);
        mRingBuffer->reset();

        bool needsReopen = !mStream ||
                mStream->getSampleRate() != info.sampleRateHz ||
                mStream->getChannelCount() != info.channelCount;

        if (needsReopen) {
            closeStreamLocked();
            if (openStreamLocked(info.sampleRateHz, info.channelCount) != oboe::Result::OK) {
                return false;
            }
        }
    }

    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        mTrackInfo = info;
    }

    mIsPlaying.store(true, std::memory_order_relaxed);
    startDecodeThread();

    std::lock_guard<std::mutex> lock(mStreamLock);
    if (mStream) {
        mStream->requestStart();
    }

    return true;
}

void AudioEngine::pauseTrack() {
    std::lock_guard<std::mutex> lock(mStreamLock);
    if (mStream) {
        mStream->requestPause();
    }
    mIsPlaying.store(false, std::memory_order_relaxed);
}

void AudioEngine::resumeTrack() {
    std::lock_guard<std::mutex> lock(mStreamLock);
    if (mStream) {
        mStream->requestStart();
        mIsPlaying.store(true, std::memory_order_relaxed);
    }
}

void AudioEngine::seekTo(int64_t positionMs) {
    // Handed off to the decode thread rather than touching mDecoder
    // here directly — see the threading contract in AudioEngine.h.
    mPendingSeekMs.store(positionMs, std::memory_order_relaxed);
}

int64_t AudioEngine::getCurrentPositionMs() const {
    return mDecoder->getCurrentPositionMs();
}

TrackInfo AudioEngine::getAudioTrackInfo() const {
    std::lock_guard<std::mutex> lock(mTrackInfoLock);
    return mTrackInfo;
}

bool AudioEngine::consumeTrackFinishedEvent() {
    return mTrackFinishedEvent.exchange(false, std::memory_order_relaxed);
}

void AudioEngine::setGain(double linearGain) {
    double clamped = std::max(0.0, std::min(linearGain, kMaxGain));
    mGain.store(clamped, std::memory_order_relaxed);
}

oboe::Result AudioEngine::openStreamLocked(int32_t sampleRate, int32_t channelCount) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setChannelCount(channelCount)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream at %d Hz / %d ch: %s",
             sampleRate, channelCount, oboe::convertToText(result));
        return result;
    }

    // Exclusive mode, like in Phase 1, is a request AAudio may not
    // grant — logged here since it directly determines bit-perfect-ness.
    // The requested sample rate can also, in rare cases, not be granted
    // exactly; swresample already normalized the decoder's output to
    // exactly what we asked for, so a mismatch here means the actual
    // hardware output path resampled, not us.
    LOGI("Stream opened: sharingMode=%s, requested %dHz/%dch, granted %dHz/%dch/%s",
         oboe::convertToText(mStream->getSharingMode()),
         sampleRate, channelCount,
         mStream->getSampleRate(), mStream->getChannelCount(),
         oboe::convertToText(mStream->getFormat()));

    size_t scratchSize = kMaxScratchFrames * static_cast<size_t>(mStream->getChannelCount());
    if (mReadScratch.size() < scratchSize) {
        mReadScratch.resize(scratchSize);
    }

    mLimiter.configure(static_cast<double>(mStream->getSampleRate()));

    return oboe::Result::OK;
}

void AudioEngine::closeStreamLocked() {
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
}

void AudioEngine::startDecodeThread() {
    if (mDecodeThreadRunning.load(std::memory_order_relaxed)) {
        return;
    }
    mDecodeThreadRunning.store(true, std::memory_order_relaxed);
    mDecodeThread = std::thread(&AudioEngine::decodeThreadLoop, this);
}

void AudioEngine::stopDecodeThread() {
    mDecodeThreadRunning.store(false, std::memory_order_relaxed);
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
}

void AudioEngine::decodeThreadLoop() {
    using namespace std::chrono_literals;

    while (mDecodeThreadRunning.load(std::memory_order_relaxed)) {
        int64_t pendingSeek = mPendingSeekMs.exchange(-1, std::memory_order_relaxed);
        if (pendingSeek >= 0) {
            mDecoder->seekTo(pendingSeek);
            mRingBuffer->reset();
        }

        size_t freeSpace = kRingBufferCapacitySamples - mRingBuffer->availableToRead();
        if (freeSpace < kLowWaterMarkSamples) {
            // Buffer is already well-stocked (playing/paused with data
            // queued, or track finished) — back off instead of spinning.
            std::this_thread::sleep_for(10ms);
            continue;
        }

        bool more = mDecoder->decodeInto(*mRingBuffer);
        if (!more) {
            // End of stream reached and flushed; nothing left to decode
            // until the next playTrack()/seekTo().
            std::this_thread::sleep_for(20ms);
        }
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    int32_t channels = stream->getChannelCount();

    int32_t framesToProcess = std::min(numFrames, static_cast<int32_t>(kMaxScratchFrames));
    size_t samplesNeeded = static_cast<size_t>(framesToProcess) * channels;

    size_t gotSamples = mRingBuffer->read(mReadScratch.data(), samplesNeeded);
    size_t framesGot = gotSamples / static_cast<size_t>(channels);

    // Double-precision DSP chain: DVC gain * ReplayGain, then the Peak
    // Limiter — computed per frame (not per sample) so its gain
    // reduction is shared across channels and never shifts the stereo
    // image. The future EQ processes mReadScratch here too, still in
    // double, ahead of the limiter so it's protected the same way
    // DVC/ReplayGain boosts are.
    constexpr int32_t kMaxLimiterChannels = 8;  // generous for music; see frameBuf below
    double combinedGain = mGain.load(std::memory_order_relaxed) *
            mReplayGainMultiplier.load(std::memory_order_relaxed);

    for (size_t f = 0; f < framesGot; f++) {
        size_t base = f * static_cast<size_t>(channels);
        double frameBuf[kMaxLimiterChannels];
        int32_t limiterChannels = std::min(channels, kMaxLimiterChannels);
        for (int32_t c = 0; c < limiterChannels; c++) {
            frameBuf[c] = mReadScratch[base + static_cast<size_t>(c)] * combinedGain;
        }
        double limiterGain = mLimiter.process(frameBuf, limiterChannels);

        for (int32_t c = 0; c < channels; c++) {
            double sample = mReadScratch[base + static_cast<size_t>(c)] * combinedGain;
            out[base + static_cast<size_t>(c)] = static_cast<float>(sample * limiterGain);
        }
    }
    // Underrun (decoder fell behind) or nothing queued yet: silence-fill
    // rather than leaving stale/garbage data in the output buffer.
    for (size_t i = gotSamples; i < samplesNeeded; i++) {
        out[i] = 0.0f;
    }
    // Defensive: if Oboe ever asks for more frames than kMaxScratchFrames
    // in one callback, silence the tail rather than reading out of bounds.
    for (int32_t f = framesToProcess; f < numFrames; f++) {
        for (int32_t c = 0; c < channels; c++) {
            out[static_cast<size_t>(f) * channels + c] = 0.0f;
        }
    }

    bool trackDone = mDecoder->isEndOfStream() && mRingBuffer->availableToRead() == 0;
    if (trackDone) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        mTrackFinishedEvent.store(true, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Stop;
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*audioStream*/, oboe::Result error) {
    LOGW("Stream error after close: %s. Reopening if still playing.", oboe::convertToText(error));

    int32_t sampleRate = 0;
    int32_t channelCount = 0;
    {
        std::lock_guard<std::mutex> infoLock(mTrackInfoLock);
        sampleRate = mTrackInfo.sampleRateHz;
        channelCount = mTrackInfo.channelCount;
    }

    std::lock_guard<std::mutex> lock(mStreamLock);
    mStream.reset();

    if (mIsPlaying.load(std::memory_order_relaxed) && sampleRate > 0 && channelCount > 0) {
        if (openStreamLocked(sampleRate, channelCount) == oboe::Result::OK) {
            mStream->requestStart();
        } else {
            mIsPlaying.store(false, std::memory_order_relaxed);
        }
    }
}

}  // namespace resonix

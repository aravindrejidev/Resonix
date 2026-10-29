#include "AudioEngine.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>

#define LOG_TAG "ResonixAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace resonix {

namespace {
// Generous fixed ceiling for one Oboe callback's frame count, so the
// scratch buffer is sized once (outside the real-time callback) and
// onAudioReady never allocates. Streams typically request far fewer
// frames per callback than this.
constexpr size_t kMaxScratchFrames = 8192;

std::string describeSampleRate(int32_t hz) {
    char buf[32];
    if (hz % 1000 == 0) {
        std::snprintf(buf, sizeof(buf), "%d kHz", hz / 1000);
    } else {
        std::snprintf(buf, sizeof(buf), "%.1f kHz", hz / 1000.0);
    }
    return buf;
}
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
    return startPlayback(
            [&](TrackInfo *info) { return mDecoder->open(filePath, info); },
            filePath.c_str());
}

bool AudioEngine::playTrackFd(int fd) {
    return startPlayback(
            [&](TrackInfo *info) { return mDecoder->openFd(fd, info); },
            "fd");
}

bool AudioEngine::startPlayback(const std::function<bool(TrackInfo *)> &openDecoder, const char *label) {
    struct LoadingGuard {
        std::atomic<bool> &flag;
        explicit LoadingGuard(std::atomic<bool> &f) : flag(f) { flag.store(true, std::memory_order_relaxed); }
        ~LoadingGuard() { flag.store(false, std::memory_order_relaxed); }
    } loadingGuard(mLoading);

    mIsPlaying.store(false, std::memory_order_relaxed);  // not playing while the new file loads
    stopDecodeThread();  // decoder access must be exclusive to us here

    // Drop whatever the previous track left queued *before* the (possibly
    // slow) open below, so its tail can't leak out as a blip while the new
    // file loads. The decode thread is stopped, so this thread is the only
    // producer; the audio callback may still be running, which is exactly
    // what flushFromProducer() is safe against.
    mRingBuffer->flushFromProducer();
    mPendingSeekMs.store(-1, std::memory_order_relaxed);
    mTrackFinishedEvent.store(false, std::memory_order_relaxed);

    {
        // Zeroed until decode below actually succeeds, so a caller
        // checking getAudioTrackInfo() after a false return can tell
        // "file wouldn't decode at all" (stays zeroed) apart from
        // "decoded fine but Oboe couldn't open a stream" (see next block).
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        mTrackInfo = TrackInfo{};
    }

    // Bit-perfect: the stream follows the file's own sample rate. Otherwise the
    // decoder converts everything to one fixed rate and the stream stays put.
    mDecoder->setTargetSampleRate(mBitPerfect.load(std::memory_order_relaxed) ? 0 : kFixedOutputRate);

    TrackInfo info;
    if (!openDecoder(&info)) {
        LOGE("Failed to open track: %s", label);
        return false;
    }
    if (info.outputSampleRateHz <= 0) {
        info.outputSampleRateHz = info.sampleRateHz;
    }
    if (info.sampleRateHz <= 0 || info.outputSampleRateHz <= 0 || info.channelCount <= 0) {
        LOGE("Track has no usable audio format: %s", label);
        mDecoder->close();
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        mTrackInfo = info;
    }
    applyReplayGain();

    {
        std::lock_guard<std::mutex> lock(mStreamLock);
        mRingBuffer->flushFromProducer();

        bool needsReopen = !mStream ||
                mStream->getSampleRate() != info.outputSampleRateHz ||
                mStream->getChannelCount() != info.channelCount ||
                mStreamApiSetting != mOutputApi.load(std::memory_order_relaxed) ||
                mStreamExclusiveSetting != mExclusive.load(std::memory_order_relaxed);

        if (needsReopen) {
            closeStreamLocked();
            if (openStreamLocked(info.outputSampleRateHz, info.channelCount) != oboe::Result::OK) {
                // mTrackInfo deliberately stays set to the successfully
                // decoded info above — getAudioTrackInfo() after this
                // false return is how a caller knows decode succeeded
                // and only the native output stream failed to open.
                return false;
            }
        }
    }

    mIsPlaying.store(true, std::memory_order_relaxed);
    startDecodeThread();

    std::lock_guard<std::mutex> lock(mStreamLock);
    if (!ensureStreamStartedLocked()) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        return false;
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
    if (ensureStreamStartedLocked()) {
        mIsPlaying.store(true, std::memory_order_relaxed);
    }
}

// Starts (or restarts) the output for the loaded track. A paused stream that the
// device has since disconnected (headset unplugged, route change, another app
// grabbing an exclusive stream) can't be started any more — reopen it rather than
// leaving the Play button dead.
bool AudioEngine::ensureStreamStartedLocked() {
    int32_t sampleRate;
    int32_t channels;
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        sampleRate = mTrackInfo.outputSampleRateHz;
        channels = mTrackInfo.channelCount;
    }
    if (sampleRate <= 0 || channels <= 0) {
        return false;  // nothing loaded
    }

    if (!mStream && openStreamLocked(sampleRate, channels) != oboe::Result::OK) {
        return false;
    }
    oboe::Result result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGW("requestStart failed (%s) — reopening the stream", oboe::convertToText(result));
        closeStreamLocked();
        if (openStreamLocked(sampleRate, channels) != oboe::Result::OK) {
            return false;
        }
        result = mStream->requestStart();
    }
    return result == oboe::Result::OK;
}

void AudioEngine::seekTo(int64_t positionMs) {
    // Handed off to the decode thread rather than touching mDecoder
    // here directly — see the threading contract in AudioEngine.h.
    mPendingSeekMs.store(std::max<int64_t>(0, positionMs), std::memory_order_relaxed);
}

int64_t AudioEngine::getCurrentPositionMs() const {
    int32_t sampleRate;
    int32_t channels;
    int64_t durationMs;
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        sampleRate = mTrackInfo.outputSampleRateHz;
        channels = mTrackInfo.channelCount;
        durationMs = mTrackInfo.durationMs;
    }
    int64_t decodedMs = mDecoder->getCurrentPositionMs();
    if (sampleRate <= 0 || channels <= 0) {
        return decodedMs;
    }

    // A seek the decode thread hasn't carried out yet: show where we are going,
    // not where we were (otherwise the seek bar snaps back for a moment).
    int64_t pendingSeek = mPendingSeekMs.load(std::memory_order_relaxed);
    if (pendingSeek >= 0) {
        return durationMs > 0 ? std::min(pendingSeek, durationMs) : pendingSeek;
    }

    size_t queuedSamples = mRingBuffer->availableToRead();
    if (queuedSamples == 0 && mDecoder->isEndOfStream() && durationMs > 0) {
        return durationMs;  // everything decoded has been played
    }
    int64_t queuedMs = static_cast<int64_t>(queuedSamples / static_cast<size_t>(channels)) * 1000 / sampleRate;
    return std::max<int64_t>(0, decodedMs - queuedMs);
}

TrackInfo AudioEngine::getAudioTrackInfo() const {
    std::lock_guard<std::mutex> lock(mTrackInfoLock);
    return mTrackInfo;
}

std::string AudioEngine::getOutputDescription() const {
    std::lock_guard<std::mutex> lock(mTrackInfoLock);
    return mOutputDescription;
}

bool AudioEngine::consumeTrackFinishedEvent() {
    return mTrackFinishedEvent.exchange(false, std::memory_order_relaxed);
}

void AudioEngine::setGain(double linearGain) {
    double clamped = std::max(0.0, std::min(linearGain, kMaxGain));
    mGain.store(clamped, std::memory_order_relaxed);
    mDsp.setMasterGain(clamped);
}

void AudioEngine::setLimiterEnabled(bool enabled) {
    mLimiterEnabled.store(enabled, std::memory_order_relaxed);
    mDsp.setLimiterEnabled(enabled);
    applyReplayGain();  // without a limiter, positive ReplayGain must leave headroom
}

void AudioEngine::setReplayGainMode(int mode) {
    mReplayGainMode.store(mode, std::memory_order_relaxed);
    applyReplayGain();
}

void AudioEngine::applyReplayGain() {
    TrackInfo info;
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        info = mTrackInfo;
    }

    bool have = false;
    double gainDb = 0.0;
    double peak = 1.0;
    auto useTrack = [&]() {
        if (!info.hasTrackGain) return false;
        gainDb = info.trackGainDb;
        peak = info.trackPeakLinear;
        return true;
    };
    auto useAlbum = [&]() {
        if (!info.hasAlbumGain) return false;
        gainDb = info.albumGainDb;
        peak = info.albumPeakLinear;
        return true;
    };

    switch (mReplayGainMode.load(std::memory_order_relaxed)) {
        case kRgTrack:
            have = useTrack() || useAlbum();
            break;
        case kRgAlbum:
            have = useAlbum() || useTrack();
            break;
        default:
            break;  // Off
    }

    double linear = have ? std::pow(10.0, gainDb / 20.0) : 1.0;
    if (have && linear > 1.0 && peak > 0.0 && linear * peak > 1.0 &&
        !mLimiterEnabled.load(std::memory_order_relaxed)) {
        linear = 1.0 / peak;  // the tag's own clipping protection
    }
    mDsp.setReplayGain(linear);
}

void AudioEngine::setOutputConfig(int api, bool exclusive, bool bitPerfect) {
    api = std::max<int>(kApiAuto, std::min<int>(api, kApiOpenSL));
    mBitPerfect.store(bitPerfect, std::memory_order_relaxed);  // from the next track on

    bool changed = (mOutputApi.load(std::memory_order_relaxed) != api) ||
                   (mExclusive.load(std::memory_order_relaxed) != exclusive);
    mOutputApi.store(api, std::memory_order_relaxed);
    mExclusive.store(exclusive, std::memory_order_relaxed);
    if (!changed) {
        return;
    }

    std::lock_guard<std::mutex> lock(mStreamLock);
    if (!mStream) {
        return;  // the next open uses the new values
    }
    // Reopen at the very same format, so the PCM already in the ring buffer stays valid.
    int32_t sampleRate = mStream->getSampleRate();
    int32_t channels = mStream->getChannelCount();
    bool wasPlaying = mIsPlaying.load(std::memory_order_relaxed);
    LOGI("Output settings changed — reopening the stream (%d Hz / %d ch)", sampleRate, channels);

    closeStreamLocked();
    if (openStreamLocked(sampleRate, channels) != oboe::Result::OK) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        return;
    }
    if (wasPlaying) {
        mStream->requestStart();
    }
}

oboe::Result AudioEngine::openStreamLocked(int32_t sampleRate, int32_t channelCount) {
    const int api = mOutputApi.load(std::memory_order_relaxed);
    const bool exclusive = mExclusive.load(std::memory_order_relaxed);

    // The user's pick first, then the other API: if a device cannot open a stream
    // through the chosen API at all, music beats obeying the setting.
    oboe::AudioApi first = oboe::AudioApi::Unspecified;  // "Auto": AAudio where available
    oboe::AudioApi second = oboe::AudioApi::OpenSLES;
    if (api == kApiAAudio) {
        first = oboe::AudioApi::AAudio;
    } else if (api == kApiOpenSL) {
        first = oboe::AudioApi::OpenSLES;
        second = oboe::AudioApi::Unspecified;
    }

    oboe::Result result = tryOpenStream(sampleRate, channelCount, first, exclusive);
    if (result != oboe::Result::OK) {
        LOGW("Open via %s failed at %d Hz / %d ch: %s — retrying with %s",
             oboe::convertToText(first), sampleRate, channelCount,
             oboe::convertToText(result), oboe::convertToText(second));
        result = tryOpenStream(sampleRate, channelCount, second, exclusive);
    }
    if (result == oboe::Result::OK) {
        mStreamApiSetting = api;
        mStreamExclusiveSetting = exclusive;
    }
    return result;
}

oboe::Result AudioEngine::tryOpenStream(int32_t sampleRate, int32_t channelCount,
                                        oboe::AudioApi audioApi, bool exclusive) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(exclusive ? oboe::PerformanceMode::LowLatency
                                           : oboe::PerformanceMode::None)
            ->setSharingMode(exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setSampleRate(sampleRate)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setChannelCount(channelCount)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);
    if (audioApi != oboe::AudioApi::Unspecified) {
        builder.setAudioApi(audioApi);
    }

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream (api=%s) at %d Hz / %d ch: %s",
             oboe::convertToText(audioApi), sampleRate, channelCount, oboe::convertToText(result));
        mStream.reset();
        return result;
    }

    // Exclusive mode is a request AAudio may not grant — logged because it
    // directly determines bit-perfect-ness. The requested sample rate can also,
    // in rare cases, not be granted exactly; the decoder already delivers exactly
    // what we asked for, so a mismatch means the output path resampled, not us.
    LOGI("Stream opened: api=%s, sharingMode=%s, requested %dHz/%dch, granted %dHz/%dch/%s",
         oboe::convertToText(mStream->getAudioApi()),
         oboe::convertToText(mStream->getSharingMode()),
         sampleRate, channelCount,
         mStream->getSampleRate(), mStream->getChannelCount(),
         oboe::convertToText(mStream->getFormat()));

    size_t scratchSize = kMaxScratchFrames * static_cast<size_t>(mStream->getChannelCount());
    if (mReadScratch.size() < scratchSize) {
        mReadScratch.resize(scratchSize);
    }

    mDsp.configure(static_cast<double>(mStream->getSampleRate()), mStream->getChannelCount());

    std::string description;
    if (mStream->getAudioApi() == oboe::AudioApi::AAudio) {
        description = std::string("AAudio · ") +
                (mStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared");
    } else {
        description = "OpenSL ES";
    }
    description += " · " + describeSampleRate(mStream->getSampleRate());
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        mOutputDescription = description;
    }

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
    mDecoder->setAbort(false);
    mDecodeThreadRunning.store(true, std::memory_order_relaxed);
    mDecodeThread = std::thread(&AudioEngine::decodeThreadLoop, this);
}

void AudioEngine::stopDecodeThread() {
    mDecodeThreadRunning.store(false, std::memory_order_relaxed);
    mDecoder->setAbort(true);  // unblock a decodeInto() waiting for buffer space
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
}

void AudioEngine::decodeThreadLoop() {
    using namespace std::chrono_literals;

    while (mDecodeThreadRunning.load(std::memory_order_relaxed)) {
        int64_t pendingSeek = mPendingSeekMs.load(std::memory_order_relaxed);
        if (pendingSeek >= 0) {
            // Only throw the queued audio away if the seek actually happened —
            // flushing after a failed seek would just skip content.
            if (mDecoder->seekTo(pendingSeek)) {
                mRingBuffer->flushFromProducer();
            }
            // A newer request that arrived meanwhile stays pending.
            int64_t expected = pendingSeek;
            mPendingSeekMs.compare_exchange_strong(expected, -1, std::memory_order_relaxed);
            continue;
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

int32_t AudioEngine::processIntoFloatBuffer(float *out, int32_t maxFrames, int32_t channelCount) {
    if (channelCount <= 0 || maxFrames <= 0) {
        return 0;
    }
    // Clamped to mReadScratch's fixed capacity (sized in tryOpenStream)
    // rather than resized here, so this stays allocation-free — required
    // for the Oboe real-time path, and harmless for the non-real-time
    // AudioTrack fallback path too (it just loops for more).
    int32_t maxFramesInScratch =
            static_cast<int32_t>(mReadScratch.size() / static_cast<size_t>(channelCount));
    int32_t framesToProcess = std::min(maxFrames, std::max(maxFramesInScratch, 0));
    size_t samplesNeeded = static_cast<size_t>(framesToProcess) * static_cast<size_t>(channelCount);

    size_t gotSamples = framesToProcess > 0
            ? mRingBuffer->read(mReadScratch.data(), samplesNeeded)
            : 0;
    size_t framesGot = gotSamples / static_cast<size_t>(channelCount);
    gotSamples = framesGot * static_cast<size_t>(channelCount);

    // Double-precision DSP chain (Dsp.h), still ahead of the float32 cast.
    mDsp.process(mReadScratch.data(), framesGot, channelCount);
    for (size_t i = 0; i < gotSamples; i++) {
        out[i] = static_cast<float>(mReadScratch[i]);
    }

    // Underrun (decoder fell behind) or nothing queued yet: silence-fill
    // rather than leaving stale/garbage data in the output buffer.
    for (size_t i = gotSamples; i < samplesNeeded; i++) {
        out[i] = 0.0f;
    }

    return framesToProcess;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    int32_t channels = stream->getChannelCount();

    int32_t framesProcessed = processIntoFloatBuffer(out, numFrames, channels);
    // Defensive: if Oboe ever asks for more frames than processIntoFloatBuffer
    // could provide (bounded by mReadScratch's fixed size), silence the
    // tail rather than leaving it untouched.
    for (int32_t f = framesProcessed; f < numFrames; f++) {
        for (int32_t c = 0; c < channels; c++) {
            out[static_cast<size_t>(f) * channels + c] = 0.0f;
        }
    }

    // "Decoder at end-of-stream + nothing queued" means the track is over — unless a
    // new track is being loaded, or a seek (which rewinds the decoder) is on its way.
    bool trackDone = !mLoading.load(std::memory_order_relaxed) &&
                     mPendingSeekMs.load(std::memory_order_relaxed) < 0 &&
                     mDecoder->isEndOfStream() && mRingBuffer->availableToRead() == 0;
    if (trackDone) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        mTrackFinishedEvent.store(true, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Stop;
    }

    return oboe::DataCallbackResult::Continue;
}

int32_t AudioEngine::pullProcessedFrames(float *out, int32_t maxFrames, int32_t channelCount) {
    int32_t framesWritten = processIntoFloatBuffer(out, maxFrames, channelCount);

    bool trackDone = mPendingSeekMs.load(std::memory_order_relaxed) < 0 &&
                     mDecoder->isEndOfStream() && mRingBuffer->availableToRead() == 0;
    if (trackDone) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        mTrackFinishedEvent.store(true, std::memory_order_relaxed);
    }

    return framesWritten;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*audioStream*/, oboe::Result error) {
    LOGW("Stream error after close: %s. Reopening if still playing.", oboe::convertToText(error));

    int32_t sampleRate = 0;
    int32_t channelCount = 0;
    {
        std::lock_guard<std::mutex> infoLock(mTrackInfoLock);
        sampleRate = mTrackInfo.outputSampleRateHz;
        channelCount = mTrackInfo.channelCount;
    }

    std::lock_guard<std::mutex> lock(mStreamLock);
    mStream.reset();

    // While paused, nothing to do: resumeTrack() reopens the stream on demand.
    if (mIsPlaying.load(std::memory_order_relaxed) && sampleRate > 0 && channelCount > 0) {
        if (openStreamLocked(sampleRate, channelCount) == oboe::Result::OK) {
            mStream->requestStart();
        } else {
            mIsPlaying.store(false, std::memory_order_relaxed);
        }
    }
}

}  // namespace resonix

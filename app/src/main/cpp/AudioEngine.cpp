#include "AudioEngine.h"

#include <android/log.h>
#include <cmath>

#define LOG_TAG "ResonixAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace resonix {

namespace {
constexpr double kTwoPi = 2.0 * M_PI;
}

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    closeStream();
}

oboe::Result AudioEngine::openStream() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setFormatConversionAllowed(true)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return result;
    }

    // Exclusive mode is a request, not a guarantee: AAudio may silently
    // grant Shared mode instead (e.g. another app holds the exclusive
    // stream, or the device doesn't support it). Log what we actually
    // got so this is visible in logcat during bring-up, since it
    // directly determines whether output is truly bit-perfect.
    LOGI("Stream opened: sharingMode=%s, sampleRate=%d, format=%s, channels=%d",
         oboe::convertToText(mStream->getSharingMode()),
         mStream->getSampleRate(),
         oboe::convertToText(mStream->getFormat()),
         mStream->getChannelCount());

    mChannelCount = mStream->getChannelCount();
    mPhaseIncrement = kTwoPi * kTestToneFrequencyHz / mStream->getSampleRate();

    return oboe::Result::OK;
}

void AudioEngine::closeStream() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mStream) {
        mStream->stop();
        mStream->close();
        mStream.reset();
    }
    mIsPlaying = false;
}

void AudioEngine::play() {
    std::lock_guard<std::mutex> lock(mLock);
    if (!mStream) {
        if (openStream() != oboe::Result::OK) {
            return;
        }
    }
    mStream->requestStart();
    mIsPlaying = true;
}

void AudioEngine::pause() {
    std::lock_guard<std::mutex> lock(mLock);
    if (mStream) {
        mStream->requestPause();
    }
    mIsPlaying = false;
}

bool AudioEngine::isPlaying() const {
    return mIsPlaying;
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {
    auto *outputBuffer = static_cast<float *>(audioData);

    for (int32_t i = 0; i < numFrames; i++) {
        auto sampleValue = static_cast<float>(kAmplitude * sin(mPhase));
        for (int32_t ch = 0; ch < mChannelCount; ch++) {
            outputBuffer[i * mChannelCount + ch] = sampleValue;
        }
        mPhase += mPhaseIncrement;
        if (mPhase >= kTwoPi) {
            mPhase -= kTwoPi;
        }
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*audioStream*/, oboe::Result error) {
    LOGW("Stream error after close: %s. Reopening if still playing.",
         oboe::convertToText(error));

    std::lock_guard<std::mutex> lock(mLock);
    mStream.reset();

    if (mIsPlaying) {
        if (openStream() == oboe::Result::OK) {
            mStream->requestStart();
        } else {
            mIsPlaying = false;
        }
    }
}

}  // namespace resonix

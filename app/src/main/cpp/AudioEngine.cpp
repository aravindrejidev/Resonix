#include "AudioEngine.h"

#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>

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
        : mRingBuffer(std::make_unique<RingBuffer>(kRingBufferCapacitySamples)) {
    mDecoders[0] = std::make_unique<AudioDecoder>();
    mDecoders[1] = std::make_unique<AudioDecoder>();
}

AudioEngine::~AudioEngine() {
    stopDecodeThread();
    std::lock_guard<std::mutex> lock(mStreamLock);
    closeStreamLocked();
}

bool AudioEngine::playTrack(const std::string &filePath) {
    return startPlayback(
            [&](TrackInfo *info) { return active().open(filePath, info); },
            filePath.c_str());
}

bool AudioEngine::playTrackFd(int fd) {
    return startPlayback(
            [&](TrackInfo *info) { return active().openFd(fd, info); },
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
    clearGaplessLocked();  // a prepared next track / pending hand-over belongs to the old queue

    // Drop whatever the previous track left queued *before* the (possibly
    // slow) open below, so its tail can't leak out as a blip while the new
    // file loads. The decode thread is stopped, so this thread is the only
    // producer; the audio callback may still be running, which is exactly
    // what flushFromProducer() is safe against.
    mRingBuffer->flushFromProducer();
    mPendingSeekMs.store(-1, std::memory_order_relaxed);
    const int64_t requestedStart = mNextStartMs.exchange(-1, std::memory_order_relaxed);
    mTrackFinishedEvent.store(false, std::memory_order_relaxed);

    {
        // Zeroed until decode below actually succeeds, so a caller
        // checking getAudioTrackInfo() after a false return can tell
        // "file wouldn't decode at all" (stays zeroed) apart from
        // "decoded fine but Oboe couldn't open a stream" (see next block).
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        mTrackInfo = TrackInfo{};
    }

    // The rate the decoder hands over at: whatever the output plan forces (the DAC's best match,
    // the bit-perfect mixer's rate); otherwise the file's own rate (Bit-perfect on) or the
    // device's native mixer rate (Bit-perfect off), so the stream stays put between songs.
    // When the Android mixer on this route was already seen to resample to some other rate, go
    // there directly rather than finding out again with every song.
    // (Never with the bit-perfect mixer: that stream is not resampled by Android and is left alone.)
    const int forcedRate = mForcedRate.load(std::memory_order_relaxed);
    const int learnedRate = mBitPerfectMixer.load(std::memory_order_relaxed)
            ? 0
            : mLearnedPlatformRate.load(std::memory_order_relaxed);
    int targetRate = learnedRate > 0
            ? learnedRate
            : (forcedRate > 0
                       ? forcedRate
                       : (mBitPerfect.load(std::memory_order_relaxed) ? 0 : mNativeRate.load(std::memory_order_relaxed)));
    active().setTargetSampleRate(targetRate);
    active().setResamplerQuality(mResamplerQuality.load(std::memory_order_relaxed));

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
        active().close();
        return false;
    }

    auto publishInfo = [&](const TrackInfo &ti) {
        {
            std::lock_guard<std::mutex> lock(mTrackInfoLock);
            mTrackInfo = ti;
        }
        mSourceBits.store(ti.bitDepth > 0 ? ti.bitDepth : 32, std::memory_order_relaxed);
        mResampled.store(ti.outputSampleRateHz != ti.sampleRateHz, std::memory_order_relaxed);
        mDecoderTargetRate.store(targetRate, std::memory_order_relaxed);
        applyReplayGain();
    };
    publishInfo(info);

    {
        std::lock_guard<std::mutex> lock(mStreamLock);
        mRingBuffer->flushFromProducer();

        bool needsReopen = !mStream ||
                mStream->getSampleRate() != info.outputSampleRateHz ||
                mStream->getChannelCount() != info.channelCount ||
                mStreamApiSetting != mOutputApi.load(std::memory_order_relaxed) ||
                mStreamExclusiveSetting != mExclusive.load(std::memory_order_relaxed) ||
                mStreamPlanGeneration != mPlanGeneration.load(std::memory_order_relaxed);

        if (needsReopen) {
            closeStreamLocked();
            if (openStreamLocked(info.outputSampleRateHz, info.channelCount) != oboe::Result::OK) {
                // mTrackInfo deliberately stays set to the successfully
                // decoded info above — getAudioTrackInfo() after this
                // false return is how a caller knows decode succeeded
                // and only the native output stream failed to open.
                return false;
            }

            // Did the stream really end up at the rate the decoder delivers? If Android's own
            // mixer is going to resample it (the stream is shared and the mixer runs at another
            // rate), it is better done here: Resonix's resampler is known, set to the user's
            // quality, and the output description can say so. The decoder is reopened for the
            // mixer's rate and the stream follows. Never for the bit-perfect mixer, which has
            // to be left alone.
            const int platformRate = (mStreamMode == kOpenStrict) ? 0 : detectPlatformRate(info.outputSampleRateHz);
            if (platformRate > 0 && platformRate != info.outputSampleRateHz) {
                LOGI("Android would resample %d -> %d Hz; converting in Resonix instead",
                     info.outputSampleRateHz, platformRate);
                closeStreamLocked();
                active().close();
                targetRate = platformRate;
                active().setTargetSampleRate(targetRate);
                active().setResamplerQuality(mResamplerQuality.load(std::memory_order_relaxed));
                TrackInfo again;
                if (!openDecoder(&again) || again.outputSampleRateHz <= 0 || again.channelCount <= 0) {
                    LOGE("Reopening the track at %d Hz failed: %s", platformRate, label);
                    return false;
                }
                info = again;
                publishInfo(info);
                if (openStreamLocked(info.outputSampleRateHz, info.channelCount) != oboe::Result::OK) {
                    return false;
                }
                mLearnedPlatformRate.store(platformRate, std::memory_order_relaxed);
                std::string note = " · Resonix resampled " + describeSampleRate(info.sampleRateHz) + " → " +
                                   describeSampleRate(info.outputSampleRateHz);
                std::lock_guard<std::mutex> infoLock(mTrackInfoLock);
                mOutputDescription += note;
            }
        }
    }

    mIsPlaying.store(true, std::memory_order_relaxed);
    // Carrying on mid-song: the decode thread's first act is the seek, and until it has data the
    // callback plays silence (and the position already reads the target).
    const int64_t startAt = requestedStart;
    if (startAt > 0) {
        mPendingSeekMs.store(startAt, std::memory_order_relaxed);
    }
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
    // Handed off to the decode thread rather than touching the decoder
    // here directly — see the threading contract in AudioEngine.h.
    mPendingSeekMs.store(std::max<int64_t>(0, positionMs), std::memory_order_relaxed);
}

int64_t AudioEngine::getCurrentPositionMs() {
    promoteIfCrossed();
    int32_t sampleRate;
    int32_t channels;
    int64_t durationMs;
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        sampleRate = mTrackInfo.outputSampleRateHz;
        channels = mTrackInfo.channelCount;
        durationMs = mTrackInfo.durationMs;
    }
    AudioDecoder &decoder = active();
    int64_t decodedMs = decoder.getCurrentPositionMs();
    if (sampleRate <= 0 || channels <= 0) {
        return decodedMs;
    }

    // A seek the decode thread hasn't carried out yet: show where we are going,
    // not where we were (otherwise the seek bar snaps back for a moment).
    int64_t pendingSeek = mPendingSeekMs.load(std::memory_order_relaxed);
    if (pendingSeek >= 0) {
        return durationMs > 0 ? std::min(pendingSeek, durationMs) : pendingSeek;
    }

    // A gapless hand-over is queued behind the song being heard: the decoder is already on the
    // next track, but the listener is still in the old one, a known distance before the boundary.
    const uint64_t boundary = mBoundaryIndex.load(std::memory_order_acquire);
    if (boundary != kNoBoundary) {
        const uint64_t readIdx = mRingBuffer->readIndex();
        if (readIdx < boundary) {
            const int64_t toBoundaryMs = static_cast<int64_t>((boundary - readIdx) / static_cast<uint64_t>(channels)) *
                                         1000 / sampleRate;
            return std::max<int64_t>(0, mOldTrackEndMs.load(std::memory_order_relaxed) - toBoundaryMs);
        }
    }

    size_t queuedSamples = mRingBuffer->availableToRead();
    if (queuedSamples == 0 && decoder.isEndOfStream() && durationMs > 0) {
        return durationMs;  // everything decoded has been played
    }
    int64_t queuedMs = static_cast<int64_t>(queuedSamples / static_cast<size_t>(channels)) * 1000 / sampleRate;
    return std::max<int64_t>(0, decodedMs - queuedMs);
}

TrackInfo AudioEngine::getAudioTrackInfo() {
    promoteIfCrossed();
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

double AudioEngine::replayGainFor(const TrackInfo &info) const {
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
    return linear;
}

void AudioEngine::applyReplayGain() {
    // The track being heard: the current one, or — once the audio thread has crossed a gapless
    // boundary but nobody has promoted it yet — the one that followed it.
    double audible;
    double next = 1.0;
    bool haveNext = false;
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        audible = replayGainFor(mPromotePending.load(std::memory_order_acquire) ? mNextInfo : mTrackInfo);
        if (mNextReady.load(std::memory_order_acquire)) {
            next = replayGainFor(mNextInfo);
            haveNext = true;
        }
    }
    mDsp.setReplayGain(audible);
    if (haveNext) {
        mNextReplayGain.store(next, std::memory_order_relaxed);
    }
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

    mLearnedPlatformRate.store(0, std::memory_order_relaxed);  // another API / sharing mode behaves differently

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

void AudioEngine::setOutputPlan(int deviceId, int forcedRateHz, int formatPref, bool bitPerfectMixer,
                                int nativeRateHz) {
    deviceId = std::max(0, deviceId);
    formatPref = std::max<int>(kFormatAuto, std::min<int>(formatPref, kPrefI32));
    const bool streamShapeChanged = mDeviceId.load(std::memory_order_relaxed) != deviceId ||
                                    mFormatPref.load(std::memory_order_relaxed) != formatPref ||
                                    mBitPerfectMixer.load(std::memory_order_relaxed) != bitPerfectMixer;
    mDeviceId.store(deviceId, std::memory_order_relaxed);
    mFormatPref.store(formatPref, std::memory_order_relaxed);
    mBitPerfectMixer.store(bitPerfectMixer, std::memory_order_relaxed);
    mForcedRate.store(std::max(0, forcedRateHz), std::memory_order_relaxed);
    mNativeRate.store(nativeRateHz > 0 ? nativeRateHz : kFixedOutputRate, std::memory_order_relaxed);
    if (streamShapeChanged) {
        mPlanGeneration.fetch_add(1, std::memory_order_relaxed);  // the next track reopens the stream
        mLearnedPlatformRate.store(0, std::memory_order_relaxed);
    }
}

void AudioEngine::setPlatformMixerRate(int hz, int routeKey) {
    hz = std::max(0, hz);
    const bool changed = mPlatformMixerRate.load(std::memory_order_relaxed) != hz ||
                         mPlatformRouteKey.load(std::memory_order_relaxed) != routeKey;
    mPlatformMixerRate.store(hz, std::memory_order_relaxed);
    mPlatformRouteKey.store(routeKey, std::memory_order_relaxed);
    if (changed) {
        mLearnedPlatformRate.store(0, std::memory_order_relaxed);  // a different device: learn again
    }
}

void AudioEngine::setFidelity(int ditherMode, int resamplerQuality) {
    mDitherMode.store(std::max<int>(kDitherOff, std::min<int>(ditherMode, kDitherAlways)), std::memory_order_relaxed);
    mResamplerQuality.store(std::max(0, std::min(resamplerQuality, 2)), std::memory_order_relaxed);
}

oboe::Result AudioEngine::openStreamLocked(int32_t sampleRate, int32_t channelCount) {
    const int api = mOutputApi.load(std::memory_order_relaxed);
    const bool exclusive = mExclusive.load(std::memory_order_relaxed);
    const int generation = mPlanGeneration.load(std::memory_order_relaxed);
    const int deviceId = mDeviceId.load(std::memory_order_relaxed);
    const int pref = mFormatPref.load(std::memory_order_relaxed);
    const bool bitPerfectMixer = mBitPerfectMixer.load(std::memory_order_relaxed);

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

    // The shape the output should take. "Auto" is 32-bit float: Android's mixer works in float, so
    // that hands it the signal unchanged. A bit-perfect mixer / an explicit choice asks for an
    // integer format; if the system can't open it, float is the safe fallback.
    int wanted = kFormatFloat;
    switch (pref) {
        case kPrefI16: wanted = kFormatI16; break;
        case kPrefI24: wanted = kFormatI24; break;
        case kPrefI32: wanted = kFormatI32; break;
        default: break;
    }

    // Attempts, most faithful first. While a bit-perfect mixer is engaged the first attempts are
    // "strict" (exact format, no conversion, no resampling anywhere). Otherwise the stream is first
    // opened "exact": Oboe does no sample-rate conversion of its own, so a stream that does not get
    // the requested rate is noticed (and handled by Resonix) instead of being resampled behind our
    // back. Only if that cannot open at all is Oboe allowed to convert the rate as well.
    struct Attempt { int deviceId; int format; int mode; };
    Attempt attempts[12];
    int count = 0;
    auto addLoose = [&](int device, int format) {
        attempts[count++] = {device, format, kOpenExact};
        attempts[count++] = {device, format, kOpenLenient};
    };
    if (bitPerfectMixer) {
        attempts[count++] = {deviceId, wanted, kOpenStrict};
        if (deviceId > 0) attempts[count++] = {0, wanted, kOpenStrict};  // the DAC vanished / refused
        addLoose(deviceId, wanted);
        if (deviceId > 0) addLoose(0, wanted);
    } else {
        addLoose(deviceId, wanted);
        if (deviceId > 0) addLoose(0, wanted);
    }
    if (wanted != kFormatFloat) addLoose(0, kFormatFloat);

    oboe::Result result = oboe::Result::ErrorInternal;
    for (int i = 0; i < count; i++) {
        const Attempt &a = attempts[i];
        for (oboe::AudioApi candidate : {first, second}) {
            result = tryOpenStream(sampleRate, channelCount, candidate, exclusive, a.deviceId, a.format, a.mode);
            if (result == oboe::Result::OK) {
                mStreamApiSetting = api;
                mStreamExclusiveSetting = exclusive;
                mStreamPlanGeneration = generation;
                return result;
            }
            LOGW("Open (api=%s, device=%d, format=%d, mode=%d) failed at %d Hz / %d ch: %s",
                 oboe::convertToText(candidate), a.deviceId, a.format, a.mode, sampleRate,
                 channelCount, oboe::convertToText(result));
        }
    }
    return result;
}

oboe::Result AudioEngine::tryOpenStream(int32_t sampleRate, int32_t channelCount, oboe::AudioApi audioApi,
                                        bool exclusive, int deviceId, int format, int mode) {
    const bool strict = (mode == kOpenStrict);
    oboe::AudioFormat oboeFormat = oboe::AudioFormat::Float;
    switch (format) {
        case kFormatI16: oboeFormat = oboe::AudioFormat::I16; break;
        case kFormatI24: oboeFormat = oboe::AudioFormat::I24; break;
        case kFormatI32: oboeFormat = oboe::AudioFormat::I32; break;
        default: break;
    }

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setFormat(oboeFormat)
            ->setSampleRate(sampleRate)
            ->setChannelCount(channelCount)
            ->setUsage(oboe::Usage::Media)
            ->setContentType(oboe::ContentType::Music)
            ->setDataCallback(this)
            ->setErrorCallback(this);

    if (strict) {
        // Bit-perfect mixer: the bytes we write are the bytes that reach the DAC. No conversion
        // by Oboe, no sample-rate conversion, the shared (non-MMAP) path the mixer is attached to.
        builder.setFormatConversionAllowed(false)
                ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::None)
                ->setPerformanceMode(oboe::PerformanceMode::None)
                ->setSharingMode(oboe::SharingMode::Shared);
    } else {
        // Format conversion stays allowed (a device that only speaks 16-bit still plays); the
        // sample rate is converted by Oboe only in the lenient last resort — see openStreamLocked().
        builder.setFormatConversionAllowed(true)
                ->setSampleRateConversionQuality(mode == kOpenLenient ? oboe::SampleRateConversionQuality::Best
                                                                      : oboe::SampleRateConversionQuality::None)
                ->setPerformanceMode(exclusive ? oboe::PerformanceMode::LowLatency
                                               : oboe::PerformanceMode::None)
                ->setSharingMode(exclusive ? oboe::SharingMode::Exclusive : oboe::SharingMode::Shared);
    }
    if (audioApi != oboe::AudioApi::Unspecified) {
        builder.setAudioApi(audioApi);
    }
    if (deviceId > 0) {
        builder.setDeviceId(deviceId);
    }

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        mStream.reset();
        return result;
    }

    mStreamMode = mode;
    // Android 14+ (AAudio only): the sample rate of the device itself, which is what the audio
    // really leaves at. Everything older reports 0 here.
    mHardwareRateHz = std::max<int32_t>(0, mStream->getHardwareSampleRate());

    // Exclusive mode is a request AAudio may not grant — logged because it
    // directly determines bit-perfect-ness. The requested sample rate can also
    // not be granted: then startPlayback() converts in the decoder, see detectPlatformRate().
    LOGI("Stream opened: api=%s, sharingMode=%s, device=%d, requested %dHz/%dch/%s, granted %dHz/%dch/%s, hardware %dHz",
         oboe::convertToText(mStream->getAudioApi()),
         oboe::convertToText(mStream->getSharingMode()),
         mStream->getDeviceId(), sampleRate, channelCount, oboe::convertToText(oboeFormat),
         mStream->getSampleRate(), mStream->getChannelCount(),
         oboe::convertToText(mStream->getFormat()), mHardwareRateHz);

    // What the audio callback must write: whatever the stream says it wants.
    int activeFormat = kFormatFloat;
    switch (mStream->getFormat()) {
        case oboe::AudioFormat::I16: activeFormat = kFormatI16; break;
        case oboe::AudioFormat::I24: activeFormat = kFormatI24; break;
        case oboe::AudioFormat::I32: activeFormat = kFormatI32; break;
        default: break;
    }
    mActiveFormat.store(activeFormat, std::memory_order_relaxed);

    size_t scratchSize = kMaxScratchFrames * static_cast<size_t>(mStream->getChannelCount());
    if (mReadScratch.size() < scratchSize) {
        mReadScratch.resize(scratchSize);
    }

    mDsp.configure(static_cast<double>(mStream->getSampleRate()), mStream->getChannelCount());

    // Buffer: start a little roomier than the minimum (music has no use for the lowest latency)
    // and let tuneBuffer() grow it if the device ever underruns.
    mBurstFrames = mStream->getFramesPerBurst();
    mCapacityFrames = mStream->getBufferCapacityInFrames();
    mLastXRunCount = 0;
    mCallbacksSinceCheck = 0;
    if (mBurstFrames > 0 && mCapacityFrames > 0) {
        mStream->setBufferSizeInFrames(std::min(mCapacityFrames, mBurstFrames * 3));
    }

    std::string description;
    if (mStream->getAudioApi() == oboe::AudioApi::AAudio) {
        description = std::string("AAudio · ") +
                (mStream->getSharingMode() == oboe::SharingMode::Exclusive ? "Exclusive" : "Shared");
    } else {
        description = "OpenSL ES";
    }
    description += " · " + describeSampleRate(mStream->getSampleRate());
    switch (activeFormat) {
        case kFormatI16: description += " · 16-bit"; break;
        case kFormatI24: description += " · 24-bit"; break;
        case kFormatI32: description += " · 32-bit"; break;
        default: description += " · float"; break;
    }
    if (strict) {
        description += " · Bit-perfect";
    } else if (mHardwareRateHz > 0 && mHardwareRateHz == mStream->getSampleRate()) {
        description += " · device " + describeSampleRate(mHardwareRateHz);  // confirmed by Android, not assumed
    }
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        mOutputDescription = description;
    }

    return oboe::Result::OK;
}

int AudioEngine::detectPlatformRate(int requestedRate) const {
    if (!mStream) return 0;
    // 1. The stream itself came back at another rate (only without Oboe's own conversion).
    const int granted = mStream->getSampleRate();
    if (granted > 0 && granted != requestedRate) return granted;
    // 2. Android 14+ tells us the rate of the device: if it is not ours, the mixer converts.
    if (mHardwareRateHz > 0) return mHardwareRateHz != granted ? mHardwareRateHz : 0;
    // 3. Older Android cannot say. A *shared* stream is mixed at the route's mixer rate, which is
    //    known for the built-in outputs (Kotlin passes it); an exclusive (MMAP) stream is not mixed.
    const int mixer = mPlatformMixerRate.load(std::memory_order_relaxed);
    if (mixer > 0 && mixer != granted && mStream->getSharingMode() == oboe::SharingMode::Shared) return mixer;
    return 0;
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
    mDecoders[0]->setAbort(false);
    mDecoders[1]->setAbort(false);
    mDecodeThreadRunning.store(true, std::memory_order_relaxed);
    mDecodeThread = std::thread(&AudioEngine::decodeThreadLoop, this);
}

void AudioEngine::stopDecodeThread() {
    mDecodeThreadRunning.store(false, std::memory_order_relaxed);
    active().setAbort(true);  // unblock a decodeInto() waiting for buffer space
    if (mDecodeThread.joinable()) {
        mDecodeThread.join();
    }
}

void AudioEngine::decodeThreadLoop() {
    using namespace std::chrono_literals;

    while (mDecodeThreadRunning.load(std::memory_order_relaxed)) {
        retireIfDone();

        int64_t pendingSeek = mPendingSeekMs.load(std::memory_order_relaxed);
        if (pendingSeek >= 0) {
            // A seek always means "in the song being heard". If the decoder has already moved on to
            // the next track (gapless), go back to the old one first; the ring then holds the old
            // song's tail followed by the next song, so all of it is discarded.
            if (revertGaplessIfPending()) {
                mRingBuffer->flushFromProducer();
            }
            // Only throw the queued audio away if the seek actually happened —
            // flushing after a failed seek would just skip content.
            if (active().seekTo(pendingSeek)) {
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

        bool more = active().decodeInto(*mRingBuffer);
        if (!more) {
            // End of stream reached and flushed. If the next track is prepared, carry straight on
            // with it (gapless); otherwise nothing is left to decode until the next
            // playTrack()/seekTo().
            if (!trySwitchToNext()) {
                std::this_thread::sleep_for(20ms);
            }
        }
    }
}

// ---- Gapless -------------------------------------------------------------------------------

bool AudioEngine::prepareNextFd(int fd) {
    if (fd < 0) return false;
    promoteIfCrossed();
    if (mLoading.load(std::memory_order_relaxed)) return false;

    std::lock_guard<std::mutex> opLock(mNextOpLock);
    if (mBoundaryIndex.load(std::memory_order_acquire) != kNoBoundary ||
        mPromotePending.load(std::memory_order_acquire)) {
        return false;  // a hand-over is already under way: wait for it to be reported
    }

    TrackInfo current;
    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        current = mTrackInfo;
    }
    if (current.outputSampleRateHz <= 0 || current.channelCount <= 0) {
        return false;  // nothing is loaded
    }

    // The idle slot may still hold an older prepared track or the decoder of the song before.
    mNextReady.store(false, std::memory_order_release);
    mRetiredDecoder.store(-1, std::memory_order_relaxed);
    AudioDecoder &next = *mDecoders[1 - mActive.load(std::memory_order_acquire)];
    next.close();
    next.setAbort(false);
    next.setTargetSampleRate(mDecoderTargetRate.load(std::memory_order_relaxed));
    next.setResamplerQuality(mResamplerQuality.load(std::memory_order_relaxed));

    TrackInfo info;
    if (!next.openFd(fd, &info)) {
        next.close();
        return false;
    }
    if (info.outputSampleRateHz <= 0) {
        info.outputSampleRateHz = info.sampleRateHz;
    }
    // Same stream or no gapless: a different rate or channel count needs the output stream
    // reopened, which is exactly the gap this is meant to avoid.
    if (info.outputSampleRateHz != current.outputSampleRateHz || info.channelCount != current.channelCount) {
        LOGI("Next track is %d Hz / %d ch, stream is %d Hz / %d ch — not gapless",
             info.outputSampleRateHz, info.channelCount, current.outputSampleRateHz, current.channelCount);
        next.close();
        return false;
    }

    {
        std::lock_guard<std::mutex> lock(mTrackInfoLock);
        mNextInfo = info;
        mNextReplayGain.store(replayGainFor(info), std::memory_order_relaxed);
    }
    mNextSourceBits.store(info.bitDepth > 0 ? info.bitDepth : 32, std::memory_order_relaxed);
    mNextResampled.store(info.outputSampleRateHz != info.sampleRateHz, std::memory_order_relaxed);
    mNextReady.store(true, std::memory_order_release);
    return true;
}

bool AudioEngine::cancelNext() {
    std::lock_guard<std::mutex> opLock(mNextOpLock);
    if (mBoundaryIndex.load(std::memory_order_acquire) != kNoBoundary ||
        mPromotePending.load(std::memory_order_acquire)) {
        return false;  // too late: the hand-over has happened
    }
    if (mNextReady.exchange(false, std::memory_order_acq_rel)) {
        mDecoders[1 - mActive.load(std::memory_order_acquire)]->close();
    }
    return true;
}

bool AudioEngine::consumeGaplessAdvance() {
    promoteIfCrossed();
    return mGaplessAdvanced.exchange(false, std::memory_order_acq_rel);
}

bool AudioEngine::trySwitchToNext() {
    if (!mNextReady.load(std::memory_order_acquire)) return false;
    std::unique_lock<std::mutex> lock(mNextOpLock, std::try_to_lock);
    if (!lock.owns_lock() || !mNextReady.load(std::memory_order_acquire)) {
        return false;  // a prepare is still opening the file: try again in a moment
    }
    const int current = mActive.load(std::memory_order_relaxed);
    mOldTrackEndMs.store(mDecoders[current]->getCurrentPositionMs(), std::memory_order_relaxed);
    mRetiredDecoder.store(current, std::memory_order_relaxed);
    // Everything of the old track is in the ring by now: that is where the boundary lies. The
    // boundary is published first, so a reader can never pass it without seeing it.
    mBoundaryIndex.store(mRingBuffer->writeIndex(), std::memory_order_release);
    mActive.store(1 - current, std::memory_order_release);
    mNextReady.store(false, std::memory_order_release);
    return true;
}

bool AudioEngine::revertGaplessIfPending() {
    std::lock_guard<std::mutex> lock(mNextOpLock);
    uint64_t boundary = mBoundaryIndex.load(std::memory_order_acquire);
    if (boundary == kNoBoundary) return false;
    // Whoever clears the boundary first wins: if the audio thread got there, the next track is
    // already being heard and the seek belongs to it.
    if (!mBoundaryIndex.compare_exchange_strong(boundary, kNoBoundary, std::memory_order_acq_rel)) return false;
    const int retired = mRetiredDecoder.load(std::memory_order_relaxed);
    const int current = mActive.load(std::memory_order_relaxed);
    if (retired < 0 || retired == current) return false;  // cannot happen; leave things as they are
    mActive.store(retired, std::memory_order_release);
    mDecoders[current]->close();  // the next track will have to be prepared again
    mRetiredDecoder.store(-1, std::memory_order_relaxed);
    mNextReady.store(false, std::memory_order_release);
    return true;
}

void AudioEngine::retireIfDone() {
    if (mRetiredDecoder.load(std::memory_order_relaxed) < 0 ||
        mBoundaryIndex.load(std::memory_order_acquire) != kNoBoundary) {
        return;
    }
    std::unique_lock<std::mutex> lock(mNextOpLock, std::try_to_lock);
    if (!lock.owns_lock()) return;
    const int retired = mRetiredDecoder.load(std::memory_order_relaxed);
    if (retired < 0 || mBoundaryIndex.load(std::memory_order_acquire) != kNoBoundary) return;
    mDecoders[retired]->close();  // its song has been heard to the end
    mRetiredDecoder.store(-1, std::memory_order_relaxed);
}

void AudioEngine::clearGaplessLocked() {
    std::lock_guard<std::mutex> lock(mNextOpLock);
    mBoundaryIndex.store(kNoBoundary, std::memory_order_release);
    mPromotePending.store(false, std::memory_order_release);
    mGaplessAdvanced.store(false, std::memory_order_release);
    mNextReady.store(false, std::memory_order_release);
    mRetiredDecoder.store(-1, std::memory_order_relaxed);
    mDecoders[1 - mActive.load(std::memory_order_acquire)]->close();
    std::lock_guard<std::mutex> infoLock(mTrackInfoLock);
    mNextInfo = TrackInfo{};
}

void AudioEngine::onBoundaryCrossed(uint64_t boundary) {
    uint64_t expected = boundary;
    if (!mBoundaryIndex.compare_exchange_strong(expected, kNoBoundary, std::memory_order_acq_rel)) {
        return;  // a seek went back to the old track first
    }
    // From this sample on, the listener hears the next track: switch what depends on the track.
    // Atomics only — this runs on the real-time audio thread.
    mDsp.setReplayGain(mNextReplayGain.load(std::memory_order_relaxed));
    mSourceBits.store(mNextSourceBits.load(std::memory_order_relaxed), std::memory_order_relaxed);
    mResampled.store(mNextResampled.load(std::memory_order_relaxed), std::memory_order_relaxed);
    mPromotePending.store(true, std::memory_order_release);
}

void AudioEngine::promoteIfCrossed() {
    if (!mPromotePending.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lock(mTrackInfoLock);
    if (!mPromotePending.load(std::memory_order_relaxed)) return;  // another thread was first
    mTrackInfo = mNextInfo;
    mPromotePending.store(false, std::memory_order_release);
    mGaplessAdvanced.store(true, std::memory_order_release);
}

int32_t AudioEngine::renderBlock(void *out, int32_t maxFrames, int32_t channelCount, int format) {
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

    // Read and process in one or two pieces: if a gapless boundary (the first sample of the next
    // track) lies inside this block, the part before it is processed with the old track's settings
    // and the part after it with the new one's, so ReplayGain changes at exactly the right sample.
    size_t gotSamples = 0;
    bool neutral = true;
    auto readPiece = [&](size_t want) -> size_t {
        size_t got = mRingBuffer->read(mReadScratch.data() + gotSamples, want);
        const size_t frames = got / static_cast<size_t>(channelCount);
        got = frames * static_cast<size_t>(channelCount);
        // Double-precision DSP chain (Dsp.h), still ahead of the final quantiser.
        mDsp.process(mReadScratch.data() + gotSamples, frames, channelCount);
        neutral = neutral && mDsp.lastBlockWasNeutral();
        gotSamples += got;
        return got;
    };

    if (framesToProcess > 0) {
        size_t firstPiece = samplesNeeded;
        bool crossInside = false;
        const uint64_t boundary = mBoundaryIndex.load(std::memory_order_acquire);
        if (boundary != kNoBoundary) {
            const uint64_t readIdx = mRingBuffer->readIndex();
            if (readIdx >= boundary) {
                onBoundaryCrossed(boundary);  // the next track starts with this very block
            } else if (boundary - readIdx < samplesNeeded) {
                firstPiece = static_cast<size_t>(boundary - readIdx);
                firstPiece -= firstPiece % static_cast<size_t>(channelCount);  // whole frames
                crossInside = firstPiece > 0;
            }
        }
        const size_t got1 = readPiece(firstPiece);
        if (crossInside && got1 == firstPiece) {
            onBoundaryCrossed(boundary);
            readPiece(samplesNeeded - firstPiece);
        }
    } else {
        mDsp.process(mReadScratch.data(), 0, channelCount);
        neutral = mDsp.lastBlockWasNeutral();
    }

    // Dither only where it helps: into a 16- or 24-bit output, and only when the samples are not
    // already exactly on that output's grid. An untouched 16-bit file played at its own rate to a
    // 16-bit stream is bit-for-bit what the file contains — adding noise there would be a loss.
    bool dither = false;
    if (format == kFormatI16 || format == kFormatI24) {
        const int mode = mDitherMode.load(std::memory_order_relaxed);
        const bool onGrid = neutral &&
                            !mResampled.load(std::memory_order_relaxed) &&
                            mSourceBits.load(std::memory_order_relaxed) <= bitsOfFormat(format);
        dither = (mode == kDitherAlways) || (mode == kDitherAuto && !onGrid);
    }
    mQuantizer.convert(mReadScratch.data(), out, gotSamples, format, dither);

    // Underrun (decoder fell behind) or nothing queued yet: silence-fill
    // rather than leaving stale/garbage data in the output buffer.
    const size_t bytes = bytesPerSample(format);
    if (samplesNeeded > gotSamples) {
        std::memset(static_cast<uint8_t *>(out) + gotSamples * bytes, 0, (samplesNeeded - gotSamples) * bytes);
    }

    return framesToProcess;
}

void AudioEngine::tuneBuffer(oboe::AudioStream *stream) {
    // Cheap enough for the callback, but there is no point asking every few milliseconds.
    if (++mCallbacksSinceCheck < 32 || mBurstFrames <= 0) {
        return;
    }
    mCallbacksSinceCheck = 0;
    oboe::ResultWithValue<int32_t> xruns = stream->getXRunCount();
    if (xruns.error() != oboe::Result::OK || xruns.value() <= mLastXRunCount) {
        return;
    }
    mLastXRunCount = xruns.value();
    const int32_t current = stream->getBufferSizeInFrames();
    const int32_t grown = std::min(mCapacityFrames, current + mBurstFrames);
    if (grown > current) {
        stream->setBufferSizeInFrames(grown);
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *stream, void *audioData, int32_t numFrames) {
    const int32_t channels = stream->getChannelCount();
    const int format = mActiveFormat.load(std::memory_order_relaxed);

    int32_t framesProcessed = renderBlock(audioData, numFrames, channels, format);
    // Defensive: if Oboe ever asks for more frames than renderBlock could provide
    // (bounded by mReadScratch's fixed size), silence the tail rather than
    // leaving it untouched.
    if (framesProcessed < numFrames && channels > 0) {
        const size_t bytes = bytesPerSample(format);
        std::memset(static_cast<uint8_t *>(audioData) + static_cast<size_t>(framesProcessed) * channels * bytes, 0,
                    static_cast<size_t>(numFrames - framesProcessed) * channels * bytes);
    }

    tuneBuffer(stream);

    // "Decoder at end-of-stream + nothing queued" means the track is over — unless a
    // new track is being loaded, or a seek (which rewinds the decoder) is on its way.
    bool trackDone = !mLoading.load(std::memory_order_relaxed) &&
                     mPendingSeekMs.load(std::memory_order_relaxed) < 0 &&
                     !mNextReady.load(std::memory_order_acquire) &&  // a prepared track is about to take over
                     active().isEndOfStream() && mRingBuffer->availableToRead() == 0;
    if (trackDone) {
        mIsPlaying.store(false, std::memory_order_relaxed);
        mTrackFinishedEvent.store(true, std::memory_order_relaxed);
        return oboe::DataCallbackResult::Stop;
    }

    return oboe::DataCallbackResult::Continue;
}

int32_t AudioEngine::pullProcessedFrames(float *out, int32_t maxFrames, int32_t channelCount) {
    int32_t framesWritten = renderBlock(out, maxFrames, channelCount, kFormatFloat);

    bool trackDone = mPendingSeekMs.load(std::memory_order_relaxed) < 0 &&
                     !mNextReady.load(std::memory_order_acquire) &&
                     active().isEndOfStream() && mRingBuffer->availableToRead() == 0;
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

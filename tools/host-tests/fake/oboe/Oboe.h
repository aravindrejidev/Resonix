// A host-side stand-in for Oboe, only as much as AudioEngine uses. The "audio hardware" is driven by
// the test: it calls FakeOboe::pump() instead of a real-time callback thread, so runs are deterministic.
#pragma once
#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>
#include <mutex>

namespace oboe {
enum class Result : int32_t { OK = 0, ErrorInternal = -1, ErrorInvalidRate = -2, ErrorUnavailable = -3 };
enum class DataCallbackResult : int32_t { Continue = 0, Stop = 1 };
enum class AudioApi : int32_t { Unspecified = 0, OpenSLES = 1, AAudio = 2 };
enum class AudioFormat : int32_t { Invalid = -1, Unspecified = 0, I16 = 1, Float = 2, I24 = 3, I32 = 4 };
enum class Direction : int32_t { Output = 0, Input = 1 };
enum class Usage : int32_t { Media = 1 };
enum class ContentType : int32_t { Music = 2 };
enum class PerformanceMode : int32_t { None = 10, LowLatency = 12 };
enum class SharingMode : int32_t { Exclusive = 0, Shared = 1 };
enum class SampleRateConversionQuality : int32_t { None = 0, Best = 5 };

inline const char *convertToText(Result r) { return r == Result::OK ? "OK" : "Error"; }
inline const char *convertToText(AudioApi a) { return a == AudioApi::AAudio ? "AAudio" : (a == AudioApi::OpenSLES ? "OpenSLES" : "Unspecified"); }
inline const char *convertToText(SharingMode m) { return m == SharingMode::Exclusive ? "Exclusive" : "Shared"; }
inline const char *convertToText(AudioFormat f) {
    switch (f) { case AudioFormat::I16: return "I16"; case AudioFormat::I24: return "I24"; case AudioFormat::I32: return "I32"; case AudioFormat::Float: return "Float"; default: return "?"; }
}

template <typename T> class ResultWithValue {
public:
    ResultWithValue(Result r, T v) : mR(r), mV(v) {}
    Result error() const { return mR; }
    T value() const { return mV; }
private:
    Result mR; T mV;
};

class AudioStream;
class AudioStreamDataCallback {
public:
    virtual ~AudioStreamDataCallback() = default;
    virtual DataCallbackResult onAudioReady(AudioStream *s, void *data, int32_t frames) = 0;
};
class AudioStreamErrorCallback {
public:
    virtual ~AudioStreamErrorCallback() = default;
    virtual void onErrorAfterClose(AudioStream *, Result) {}
};

// What the pretend platform does with a stream request. Tests set these before playing.
struct FakePlatform {
    int mixerRate = 48000;           // what a shared stream is really mixed at
    bool reportHardwareRate = true;  // Android 14+ AAudio: getHardwareSampleRate() works
    bool grantExclusive = false;     // exclusive (MMAP) is granted, the stream is then not mixed
    bool grantRequestedRate = true;  // false: the stream comes back at the mixer rate (no Oboe SRC)
    std::vector<int> openedRates;    // every stream the engine opened, in order
    std::vector<int> openedModes;    // 0 = SRC None, 1 = SRC Best
};
inline FakePlatform &platform() { static FakePlatform p; return p; }

class AudioStream {
public:
    virtual ~AudioStream() = default;
    AudioApi getAudioApi() const { return mApi; }
    int32_t getBufferCapacityInFrames() const { return 4096; }
    int32_t getBufferSizeInFrames() const { return mBufferSize; }
    Result setBufferSizeInFrames(int32_t n) { mBufferSize = n; return Result::OK; }
    int32_t getChannelCount() const { return mChannels; }
    int32_t getDeviceId() const { return 0; }
    AudioFormat getFormat() const { return mFormat; }
    int32_t getFramesPerBurst() const { return 192; }
    int32_t getHardwareSampleRate() const { return mHardwareRate; }
    int32_t getSampleRate() const { return mSampleRate; }
    SharingMode getSharingMode() const { return mSharing; }
    Result requestStart() { mStarted = true; return Result::OK; }
    Result requestPause() { mStarted = false; return Result::OK; }
    Result stop() { mStarted = false; return Result::OK; }
    Result close() { mClosed = true; return Result::OK; }
    ResultWithValue<int32_t> getXRunCount() { return ResultWithValue<int32_t>(Result::OK, 0); }

    // --- test side ---
    AudioApi mApi = AudioApi::AAudio;
    int32_t mChannels = 2, mSampleRate = 48000, mHardwareRate = 0, mBufferSize = 576;
    AudioFormat mFormat = AudioFormat::Float;
    SharingMode mSharing = SharingMode::Shared;
    AudioStreamDataCallback *mCallback = nullptr;
    bool mStarted = false, mClosed = false;
};

class AudioStreamBuilder {
public:
    AudioStreamBuilder *setDirection(Direction) { return this; }
    AudioStreamBuilder *setFormat(AudioFormat f) { mFormat = f; return this; }
    AudioStreamBuilder *setSampleRate(int32_t r) { mRate = r; return this; }
    AudioStreamBuilder *setChannelCount(int32_t c) { mChannels = c; return this; }
    AudioStreamBuilder *setUsage(Usage) { return this; }
    AudioStreamBuilder *setContentType(ContentType) { return this; }
    AudioStreamBuilder *setDataCallback(AudioStreamDataCallback *cb) { mCb = cb; return this; }
    AudioStreamBuilder *setErrorCallback(AudioStreamErrorCallback *) { return this; }
    AudioStreamBuilder *setFormatConversionAllowed(bool) { return this; }
    AudioStreamBuilder *setSampleRateConversionQuality(SampleRateConversionQuality q) { mSrc = q; return this; }
    AudioStreamBuilder *setPerformanceMode(PerformanceMode) { return this; }
    AudioStreamBuilder *setSharingMode(SharingMode m) { mSharing = m; return this; }
    AudioStreamBuilder *setAudioApi(AudioApi a) { mApi = a; return this; }
    AudioStreamBuilder *setDeviceId(int32_t) { return this; }

    Result openStream(std::shared_ptr<AudioStream> &out) {
        FakePlatform &p = platform();
        p.openedRates.push_back(mRate);
        p.openedModes.push_back(mSrc == SampleRateConversionQuality::None ? 0 : 1);
        auto s = std::make_shared<AudioStream>();
        s->mApi = (mApi == AudioApi::Unspecified) ? AudioApi::AAudio : mApi;
        s->mChannels = mChannels;
        s->mFormat = mFormat;
        s->mCallback = mCb;
        const bool exclusive = (mSharing == SharingMode::Exclusive) && p.grantExclusive;
        s->mSharing = exclusive ? SharingMode::Exclusive : SharingMode::Shared;
        if (exclusive) {
            s->mSampleRate = mRate;
            s->mHardwareRate = p.reportHardwareRate ? mRate : 0;
        } else {
            s->mSampleRate = p.grantRequestedRate ? mRate : p.mixerRate;   // AAudio shared: AudioFlinger resamples
            s->mHardwareRate = p.reportHardwareRate ? p.mixerRate : 0;
        }
        out = s;
        registry() = s;
        return Result::OK;
    }
    static std::shared_ptr<AudioStream> &registry() { static std::shared_ptr<AudioStream> r; return r; }
private:
    AudioFormat mFormat = AudioFormat::Float;
    int32_t mRate = 48000, mChannels = 2;
    AudioStreamDataCallback *mCb = nullptr;
    SampleRateConversionQuality mSrc = SampleRateConversionQuality::None;
    SharingMode mSharing = SharingMode::Shared;
    AudioApi mApi = AudioApi::Unspecified;
};
}  // namespace oboe

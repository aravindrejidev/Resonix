#ifndef RESONIX_AUDIO_ENGINE_H
#define RESONIX_AUDIO_ENGINE_H

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "AudioDecoder.h"
#include "Dsp.h"
#include "RingBuffer.h"

namespace resonix {

// Owns the full playback pipeline: AudioDecoder (FFmpeg) on a dedicated
// decode thread -> RingBuffer (lock-free, double-precision) -> Oboe
// output stream, whose callback runs the double-precision DSP chain
// (see Dsp.h: bass shelf, stereo width, balance, DVC x ReplayGain x preamp
// gain, peak limiter) and converts to 32-bit float right before handing
// samples to Oboe. The future EQ plugs in ahead of the limiter.
//
// Threading contract:
//   - AudioDecoder's open()/seekTo()/decodeInto() are only ever called
//     from the internal decode thread (or, for open(), from the calling
//     JNI thread while the decode thread is confirmed stopped/joined).
//   - The Oboe callback thread only ever calls RingBuffer::read() and
//     never touches AudioDecoder directly.
//   - seekTo() from the JNI thread does not call into AudioDecoder
//     itself; it posts a pending-seek request the decode thread picks
//     up, keeping all AudioDecoder access on that one thread.
class AudioEngine : public oboe::AudioStreamDataCallback,
                     public oboe::AudioStreamErrorCallback {
public:
    // Values match the Kotlin enums in AppSettings (OutputEngine / ReplayGainMode).
    enum OutputApi : int { kApiAuto = 0, kApiAAudio = 1, kApiOpenSL = 2 };
    enum ReplayGainMode : int { kRgOff = 0, kRgTrack = 1, kRgAlbum = 2 };

    // With "bit-perfect" off every track is converted to this one rate, so the
    // output stream is never reopened between songs.
    static constexpr int kFixedOutputRate = 48000;

    AudioEngine();
    ~AudioEngine() override;

    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    bool playTrack(const std::string &filePath);

    // Plays straight from an already-open file descriptor (the engine dup()s
    // it, so the caller may close its copy right after this returns). This is
    // the path the app uses: SAF / MediaStore files can't be re-opened by path.
    bool playTrackFd(int fd);

    void pauseTrack();
    void resumeTrack();
    void seekTo(int64_t positionMs);

    // Position of the audio being *heard* — the decoder runs several seconds
    // ahead of the speaker (ring buffer), so its own position is corrected
    // by whatever is still queued. While a seek is pending this is the seek
    // target, so the UI jumps there immediately.
    int64_t getCurrentPositionMs() const;
    TrackInfo getAudioTrackInfo() const;
    bool isPlaying() const { return mIsPlaying.load(std::memory_order_relaxed); }

    // Master gain (Direct Volume Control level x audio-focus ducking), linear,
    // applied in the double-precision DSP stage — independent of Android's
    // stream-volume ceiling. 1.0 = unity.
    void setGain(double linearGain);
    double getGain() const { return mGain.load(std::memory_order_relaxed); }
    static constexpr double kMaxGain = 4.0;  // +12.04 dB

    // --- Settings -> Audio engine ---------------------------------------------
    // Output API / exclusive mode: an open stream is reopened right away (same
    // sample rate, so the buffered PCM stays valid). Bit-perfect picks the
    // stream's sample rate for the *next* track.
    void setOutputConfig(int api, bool exclusive, bool bitPerfect);
    void setReplayGainMode(int mode);
    void setLimiterEnabled(bool enabled);

    // --- Tools -> Sound shaping -------------------------------------------------
    void setPreampDb(double db) { mDsp.setPreampDb(db); }
    void setBassBoost(double amount) { mDsp.setBassBoost(amount); }
    void setStereoWidth(double width) { mDsp.setStereoWidth(width); }
    void setBalance(double balance) { mDsp.setBalance(balance); }

    // e.g. "AAudio · Exclusive · 96 kHz" — what the output stream really is.
    // Empty until a stream has been opened. Cheap and never blocks on the
    // stream lock, so it is safe to poll from the UI thread.
    std::string getOutputDescription() const;

    // True exactly once per track that reached end-of-stream on its own
    // (not a manual pauseTrack()), then resets — call periodically from
    // Kotlin to drive playback-queue auto-advance.
    bool consumeTrackFinishedEvent();

    // Output Plugin System fallback path: pulls up to maxFrames of
    // fully DSP-processed float32 audio into a caller-provided buffer —
    // the same processing onAudioReady uses, just driven by an external
    // pull (a Kotlin AudioTrack write loop) instead of Oboe's callback.
    int32_t pullProcessedFrames(float *out, int32_t maxFrames, int32_t channelCount);

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback — called on a new thread, safe to
    // reopen the stream directly here.
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    // Shared by playTrack() / playTrackFd(): [openDecoder] opens the file on
    // mDecoder and fills the TrackInfo; everything after that (output stream,
    // ring buffer, decode thread) is identical for both sources.
    bool startPlayback(const std::function<bool(TrackInfo *)> &openDecoder, const char *label);

    // Opens the output stream according to the user's API / exclusive choice,
    // falling back to the other API if the preferred one cannot open at all
    // (a real, if rare, device-specific failure mode).
    oboe::Result openStreamLocked(int32_t sampleRate, int32_t channelCount);
    oboe::Result tryOpenStream(int32_t sampleRate, int32_t channelCount,
                               oboe::AudioApi audioApi, bool exclusive);
    void closeStreamLocked();

    // Makes sure a stream exists for the loaded track and is running; reopens
    // it if the device dropped it while we were paused. Needs mStreamLock.
    bool ensureStreamStartedLocked();

    // Combines the track's REPLAYGAIN_* tags with the current mode into the
    // single multiplier the DSP chain uses.
    void applyReplayGain();

    // Core DSP: reads from the ring buffer, runs the DSP chain, writes float32
    // into `out`, silence-fills any underrun. Shared by onAudioReady and
    // pullProcessedFrames so the two output paths can never drift apart.
    // Returns frames written (clamped to mReadScratch's fixed capacity).
    int32_t processIntoFloatBuffer(float *out, int32_t maxFrames, int32_t channelCount);
    void startDecodeThread();
    void stopDecodeThread();
    void decodeThreadLoop();

    std::unique_ptr<AudioDecoder> mDecoder;
    std::unique_ptr<RingBuffer> mRingBuffer;
    static constexpr size_t kRingBufferCapacitySamples = 192000 * 2 * 2;  // ~2s @ 192kHz stereo
    static constexpr size_t kLowWaterMarkSamples = kRingBufferCapacitySamples / 4;

    std::shared_ptr<oboe::AudioStream> mStream;
    mutable std::mutex mStreamLock;
    // What the currently open stream was opened with (guarded by mStreamLock).
    int mStreamApiSetting = -1;
    bool mStreamExclusiveSetting = true;

    std::thread mDecodeThread;
    std::atomic<bool> mDecodeThreadRunning{false};
    std::atomic<int64_t> mPendingSeekMs{-1};

    std::atomic<bool> mIsPlaying{false};
    std::atomic<double> mGain{1.0};
    std::atomic<bool> mTrackFinishedEvent{false};
    // True while startPlayback() is swapping tracks. The audio callback must
    // not read "decoder at end-of-stream + empty buffer" as "track finished"
    // during that window — the old track's end-of-stream flag is still set
    // while its buffer has just been flushed.
    std::atomic<bool> mLoading{false};

    // User settings (any thread).
    std::atomic<int> mOutputApi{kApiAuto};
    std::atomic<bool> mExclusive{true};
    std::atomic<bool> mBitPerfect{true};
    std::atomic<int> mReplayGainMode{kRgTrack};
    std::atomic<bool> mLimiterEnabled{true};

    // process() is only ever called from the audio callback thread (or the
    // pull path). configure() only happens inside tryOpenStream(), after the
    // previous stream was closed, so there is never a concurrent callback.
    DspChain mDsp;

    mutable std::mutex mTrackInfoLock;
    TrackInfo mTrackInfo;
    std::string mOutputDescription;  // guarded by mTrackInfoLock

    std::vector<double> mReadScratch;
};

}  // namespace resonix

#endif  // RESONIX_AUDIO_ENGINE_H

#ifndef RESONIX_AUDIO_ENGINE_H
#define RESONIX_AUDIO_ENGINE_H

#include <atomic>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <oboe/Oboe.h>

#include "AudioDecoder.h"
#include "RingBuffer.h"

namespace resonix {

// Owns the full playback pipeline: AudioDecoder (FFmpeg) on a dedicated
// decode thread -> RingBuffer (lock-free, double-precision) -> Oboe
// AAudio Exclusive-mode callback, which applies the double-precision
// DSP stage (currently just gain; the future EQ plugs in at the same
// point) and converts to 32-bit float right before handing samples to
// Oboe.
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
    AudioEngine();
    ~AudioEngine() override;

    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    bool playTrack(const std::string &filePath);
    void pauseTrack();
    void resumeTrack();
    void seekTo(int64_t positionMs);

    int64_t getCurrentPositionMs() const;
    TrackInfo getAudioTrackInfo() const;
    bool isPlaying() const { return mIsPlaying.load(std::memory_order_relaxed); }

    // True exactly once per track that reached end-of-stream on its own
    // (not a manual pauseTrack()), then resets — call periodically from
    // Kotlin to drive playback-queue auto-advance.
    bool consumeTrackFinishedEvent();

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream, void *audioData, int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback — called on a new thread, safe to
    // reopen the stream directly here (see Phase 1 AudioEngine notes).
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    oboe::Result openStreamLocked(int32_t sampleRate, int32_t channelCount);
    void closeStreamLocked();
    void startDecodeThread();
    void stopDecodeThread();
    void decodeThreadLoop();

    std::unique_ptr<AudioDecoder> mDecoder;
    std::unique_ptr<RingBuffer> mRingBuffer;
    static constexpr size_t kRingBufferCapacitySamples = 192000 * 2 * 2;  // ~2s @ 192kHz stereo
    static constexpr size_t kLowWaterMarkSamples = kRingBufferCapacitySamples / 4;

    std::shared_ptr<oboe::AudioStream> mStream;
    mutable std::mutex mStreamLock;

    std::thread mDecodeThread;
    std::atomic<bool> mDecodeThreadRunning{false};
    std::atomic<int64_t> mPendingSeekMs{-1};

    std::atomic<bool> mIsPlaying{false};
    std::atomic<double> mGain{1.0};
    std::atomic<bool> mTrackFinishedEvent{false};

    mutable std::mutex mTrackInfoLock;
    TrackInfo mTrackInfo;

    std::vector<double> mReadScratch;
};

}  // namespace resonix

#endif  // RESONIX_AUDIO_ENGINE_H

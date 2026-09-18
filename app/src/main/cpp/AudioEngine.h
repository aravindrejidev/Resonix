#ifndef RESONIX_AUDIO_ENGINE_H
#define RESONIX_AUDIO_ENGINE_H

#include <atomic>
#include <memory>
#include <mutex>

#include <oboe/Oboe.h>

namespace resonix {

// Owns a single Oboe output stream configured for AAudio Exclusive
// Mode, 32-bit float samples, low-latency performance mode.
//
// Phase 1 scope: there is no decoder/media source yet, so onAudioReady
// renders a 440 Hz sine test tone. This proves the full native audio
// path (JNI -> AudioEngine -> Oboe -> AAudio -> DAC) end to end before
// Phase 2 wires in real file playback. Play/Pause in the UI toggles
// this test tone.
class AudioEngine : public oboe::AudioStreamDataCallback,
                     public oboe::AudioStreamErrorCallback {
public:
    AudioEngine();
    ~AudioEngine() override;

    // Disallow copying; this class owns a live native audio stream.
    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    void play();
    void pause();
    bool isPlaying() const;

    // oboe::AudioStreamDataCallback
    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

    // oboe::AudioStreamErrorCallback
    // Called on a new thread (not the audio callback thread), so it is
    // safe to reopen the stream here directly. Handles the common case
    // of the active audio route changing (e.g. headphones/DAC plugged
    // or unplugged) while the exclusive-mode stream is open.
    void onErrorAfterClose(oboe::AudioStream *audioStream, oboe::Result error) override;

private:
    oboe::Result openStream();
    void closeStream();

    std::shared_ptr<oboe::AudioStream> mStream;
    mutable std::mutex mLock;
    std::atomic<bool> mIsPlaying{false};

    double mPhase = 0.0;
    double mPhaseIncrement = 0.0;
    int32_t mChannelCount = 2;

    static constexpr float kAmplitude = 0.2f;
    static constexpr double kTestToneFrequencyHz = 440.0;
};

}  // namespace resonix

#endif  // RESONIX_AUDIO_ENGINE_H

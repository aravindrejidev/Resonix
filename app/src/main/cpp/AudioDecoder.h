#ifndef RESONIX_AUDIO_DECODER_H
#define RESONIX_AUDIO_DECODER_H

#include <atomic>
#include <cstdint>
#include <string>
#include <vector>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libswresample/swresample.h>
}

#include "RingBuffer.h"

namespace resonix {

struct FdSource;  // custom-IO state for openFd(); defined in AudioDecoder.cpp

// Metadata about the currently open track, as actually decoded — not
// read from file tags. sampleRateHz/bitDepth/channelCount describe the
// file itself; outputSampleRateHz is the rate of the PCM the decoder hands
// to the ring buffer (identical to sampleRateHz unless AudioEngine asked
// the decoder to resample via setTargetSampleRate()), which is what the
// output stream is opened for.
struct TrackInfo {
    int sampleRateHz = 0;
    int outputSampleRateHz = 0;
    int bitDepth = 0;
    int channelCount = 0;
    int64_t bitrateBps = 0;
    int64_t durationMs = 0;
    std::string formatName;
    // Container tags (ID3v2 for MP3, Vorbis comments for FLAC/Opus,
    // iTunes-style atoms for M4A/ALAC, ...) — empty string / 0 if the
    // file has no such tag, not guessed from anything else.
    std::string title;
    std::string artist;
    std::string album;
    int year = 0;
    // REPLAYGAIN_TRACK_* / REPLAYGAIN_ALBUM_* tags (or Opus R128_*_GAIN,
    // converted to the ReplayGain reference level). hasTrackGain /
    // hasAlbumGain say whether the file carried a value at all; the gain
    // fields are 0.0 and the peaks 1.0 when it did not. AudioEngine picks
    // between them according to the ReplayGain setting.
    bool hasTrackGain = false;
    bool hasAlbumGain = false;
    double trackGainDb = 0.0;
    double trackPeakLinear = 1.0;
    double albumGainDb = 0.0;
    double albumPeakLinear = 1.0;
};

// Wraps libavformat + libavcodec + libswresample to decode one audio
// file into interleaved double-precision PCM. Not thread-safe by
// design: open()/seekTo()/close() and decodeInto() are all meant to be
// called from a single dedicated decode thread (see AudioEngine). Only
// getCurrentPositionMs() is safe to call from another thread, since it
// just reads an atomic.
class AudioDecoder {
public:
    AudioDecoder();
    ~AudioDecoder();

    AudioDecoder(const AudioDecoder &) = delete;
    AudioDecoder &operator=(const AudioDecoder &) = delete;

    // Opens filePath and locates + opens the best audio stream. Fills
    // outInfo on success. Call from the decode thread, not the UI thread
    // and not the Oboe callback thread — this does file I/O.
    bool open(const std::string &filePath, TrackInfo *outInfo);

    // Same as open(), but reads straight from an already-open file
    // descriptor instead of re-opening a path. This is what makes Storage
    // Access Framework / MediaStore files work: the fd that
    // ContentResolver.openFileDescriptor() hands out is usable by this
    // process, but re-opening it through "/proc/self/fd/N" is a brand-new
    // open() that Android's storage layer denies (EACCES) for files the app
    // has no direct path access to. The decoder dup()s [fd], so the caller
    // may close its own copy as soon as this returns.
    bool openFd(int fd, TrackInfo *outInfo);

    // 0 (default) = hand the PCM over at the file's own sample rate. Any
    // other value makes libswresample convert to that rate. Takes effect on
    // the next open()/openFd().
    void setTargetSampleRate(int hz) { mTargetSampleRate = hz > 0 ? hz : 0; }

    // Asks a decodeInto() that is currently waiting for ring-buffer space
    // to give up, so the decode thread can be joined promptly (pause with a
    // full buffer + a new track request used to be able to hang there).
    void setAbort(bool abort) { mAbort.store(abort, std::memory_order_relaxed); }

    void close();

    // Decodes and pushes into ringBuffer until either the buffer is full
    // or end-of-stream is reached. Returns false once end-of-stream has
    // been reached and there is nothing further to decode (the decode
    // thread should then stop calling this and let the ring buffer
    // drain). Safe to call repeatedly in a tight-ish loop; it does not
    // busy-spin internally.
    bool decodeInto(RingBuffer &ringBuffer);

    // Seeks to positionMs and resets PTS tracking. The caller must clear
    // (reset()) the ring buffer around this call so stale pre-seek audio
    // isn't played.
    bool seekTo(int64_t positionMs);

    // End of the audio decoded so far (the stream time just after the last sample pushed
    // into the ring buffer) — AudioEngine subtracts what is still queued to get the position
    // of what is actually being heard.
    int64_t getCurrentPositionMs() const {
        return mCurrentPositionMs.load(std::memory_order_relaxed);
    }

    int getOutputSampleRate() const { return mOutputSampleRate; }
    int getOutputChannelCount() const { return mOutputChannelCount; }
    bool isEndOfStream() const { return mEndOfStream.load(std::memory_order_relaxed); }

private:
    bool finishOpen(TrackInfo *outInfo, const char *label);
    bool openBestAudioStream();
    bool setupResampler();
    void releaseFfmpegState();
    void pushConvertedFrame(RingBuffer &ringBuffer);
    void writeToRing(RingBuffer &ringBuffer, const double *samples, size_t sampleCount);
    void drainResampler(RingBuffer &ringBuffer);
    const char *findTag(const char *key) const;

    AVFormatContext *mFormatCtx = nullptr;
    AVCodecContext *mCodecCtx = nullptr;
    SwrContext *mSwrCtx = nullptr;
    AVIOContext *mAvio = nullptr;      // only for openFd()
    FdSource *mFdSource = nullptr;     // only for openFd(); owns the dup()ed fd
    AVPacket *mPacket = nullptr;
    AVFrame *mFrame = nullptr;

    int mStreamIndex = -1;
    int mTargetSampleRate = 0;
    int mOutputSampleRate = 0;
    int mOutputChannelCount = 0;

    // Decode-thread-only state. After a seek the container lands on the keyframe *before* the
    // requested time; mSeekTargetSec makes pushConvertedFrame() drop the audio up to it so
    // playback resumes at exactly the position that was asked for.
    double mSeekTargetSec = -1.0;
    // Where the audio really begins on the stream's timeline (MP3 encoder delay ≈ 25 ms,
    // broadcast-style MKV/TS often 1 s or more). Positions and seeks are relative to it.
    double mStartTimeSec = 0.0;
    // Running position for streams without timestamps.
    double mFallbackPositionSec = 0.0;

    std::vector<double> mConvertScratch;
    std::atomic<int64_t> mCurrentPositionMs{0};
    std::atomic<bool> mEndOfStream{false};
    std::atomic<bool> mAbort{false};
};

}  // namespace resonix

#endif  // RESONIX_AUDIO_DECODER_H

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

// Metadata about the currently open track, as actually decoded — not
// read from file tags. sampleRateHz/bitDepth/channelCount describe what
// came out of the decoder, which is what AudioEngine actually asks Oboe
// to open a stream for.
struct TrackInfo {
    int sampleRateHz = 0;
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
    // REPLAYGAIN_TRACK_GAIN / REPLAYGAIN_TRACK_PEAK tags, 0.0 / 1.0 (no
    // adjustment, unknown peak) when the file has none. trackPeakLinear
    // is stored for reference; the DSP chain relies on the Peak Limiter
    // rather than this value to stay safe regardless of gain source.
    double trackGainDb = 0.0;
    double trackPeakLinear = 1.0;
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

    int64_t getCurrentPositionMs() const {
        return mCurrentPositionMs.load(std::memory_order_relaxed);
    }

    int getOutputSampleRate() const { return mOutputSampleRate; }
    int getOutputChannelCount() const { return mOutputChannelCount; }
    bool isEndOfStream() const { return mEndOfStream.load(std::memory_order_relaxed); }

private:
    bool openBestAudioStream();
    bool setupResampler();
    void releaseFfmpegState();
    void pushConvertedFrame(RingBuffer &ringBuffer);

    AVFormatContext *mFormatCtx = nullptr;
    AVCodecContext *mCodecCtx = nullptr;
    SwrContext *mSwrCtx = nullptr;
    AVPacket *mPacket = nullptr;
    AVFrame *mFrame = nullptr;

    int mStreamIndex = -1;
    int mOutputSampleRate = 0;
    int mOutputChannelCount = 0;

    std::vector<double> mConvertScratch;
    std::atomic<int64_t> mCurrentPositionMs{0};
    std::atomic<bool> mEndOfStream{false};
};

}  // namespace resonix

#endif  // RESONIX_AUDIO_DECODER_H

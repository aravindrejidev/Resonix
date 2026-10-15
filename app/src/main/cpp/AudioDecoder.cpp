#include "AudioDecoder.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <cstdlib>
#include <thread>

#define LOG_TAG "ResonixDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace resonix {

namespace {

// FLAC/ALAC/WAV report their true source bit depth via
// bits_per_raw_sample. Lossy codecs (MP3/AAC/Opus) don't have a
// meaningful native bit depth, so for those we fall back to the
// decoder's own working sample format width.
int sampleFormatToBitDepth(AVSampleFormat fmt) {
    switch (av_get_packed_sample_fmt(fmt)) {
        case AV_SAMPLE_FMT_U8:  return 8;
        case AV_SAMPLE_FMT_S16: return 16;
        case AV_SAMPLE_FMT_S32: return 32;
        case AV_SAMPLE_FMT_FLT: return 32;
        case AV_SAMPLE_FMT_S64: return 64;
        case AV_SAMPLE_FMT_DBL: return 64;
        default: return 0;
    }
}

constexpr int kIoBufferSize = 64 * 1024;

}  // namespace

// State behind the AVIOContext used by openFd(). Reads use pread() with our
// own cursor, so they neither depend on nor disturb the descriptor's shared
// file offset. Non-regular files (pipes / sockets some providers hand out)
// fall back to plain sequential read() and are reported as non-seekable.
struct FdSource {
    int fd = -1;
    bool seekable = false;
    int64_t size = -1;
    int64_t pos = 0;
};

namespace {

int readFromFd(void *opaque, uint8_t *buf, int bufSize) {
    auto *src = static_cast<FdSource *>(opaque);
    ssize_t n;
    do {
        n = src->seekable ? pread64(src->fd, buf, static_cast<size_t>(bufSize), src->pos)
                          : read(src->fd, buf, static_cast<size_t>(bufSize));
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        return AVERROR(errno);
    }
    if (n == 0) {
        return AVERROR_EOF;
    }
    src->pos += n;
    return static_cast<int>(n);
}

int64_t seekInFd(void *opaque, int64_t offset, int whence) {
    auto *src = static_cast<FdSource *>(opaque);
    if (whence & AVSEEK_SIZE) {
        return src->size >= 0 ? src->size : AVERROR(ENOSYS);
    }
    if (!src->seekable) {
        return AVERROR(ESPIPE);
    }
    whence &= ~AVSEEK_FORCE;
    int64_t target;
    switch (whence) {
        case SEEK_SET:
            target = offset;
            break;
        case SEEK_CUR:
            target = src->pos + offset;
            break;
        case SEEK_END:
            if (src->size < 0) {
                return AVERROR(ENOSYS);
            }
            target = src->size + offset;
            break;
        default:
            return AVERROR(EINVAL);
    }
    if (target < 0) {
        return AVERROR(EINVAL);
    }
    src->pos = target;
    return target;
}

}  // namespace

AudioDecoder::AudioDecoder() {
    mPacket = av_packet_alloc();
    mFrame = av_frame_alloc();
}

AudioDecoder::~AudioDecoder() {
    close();
    av_packet_free(&mPacket);
    av_frame_free(&mFrame);
}

bool AudioDecoder::open(const std::string &filePath, TrackInfo *outInfo) {
    close();  // safety: release anything left from a previous track first

    if (avformat_open_input(&mFormatCtx, filePath.c_str(), nullptr, nullptr) < 0) {
        LOGE("avformat_open_input failed for %s", filePath.c_str());
        return false;
    }
    return finishOpen(outInfo, filePath.c_str());
}

bool AudioDecoder::openFd(int fd, TrackInfo *outInfo) {
    close();

    if (fd < 0) {
        LOGE("openFd: invalid file descriptor");
        return false;
    }
    int owned = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    if (owned < 0) {
        LOGE("openFd: dup failed (errno %d)", errno);
        return false;
    }

    mFdSource = new FdSource();
    mFdSource->fd = owned;
    struct stat st;
    if (fstat(owned, &st) == 0 && S_ISREG(st.st_mode)) {
        mFdSource->seekable = true;
        mFdSource->size = static_cast<int64_t>(st.st_size);
    }

    auto *ioBuffer = static_cast<unsigned char *>(av_malloc(kIoBufferSize));
    if (ioBuffer == nullptr) {
        releaseFfmpegState();
        return false;
    }
    mAvio = avio_alloc_context(ioBuffer, kIoBufferSize, 0, mFdSource, readFromFd, nullptr,
                               mFdSource->seekable ? seekInFd : nullptr);
    if (mAvio == nullptr) {
        av_free(ioBuffer);
        releaseFfmpegState();
        return false;
    }

    mFormatCtx = avformat_alloc_context();
    if (mFormatCtx == nullptr) {
        releaseFfmpegState();
        return false;
    }
    mFormatCtx->pb = mAvio;
    mFormatCtx->flags |= AVFMT_FLAG_CUSTOM_IO;

    // With custom IO the url is only a probing hint; none is needed.
    if (avformat_open_input(&mFormatCtx, nullptr, nullptr, nullptr) < 0) {
        LOGE("avformat_open_input failed for fd (seekable=%d, size=%lld)",
             mFdSource->seekable ? 1 : 0, static_cast<long long>(mFdSource->size));
        releaseFfmpegState();  // mFormatCtx was already freed by FFmpeg; frees our IO + fd
        return false;
    }
    return finishOpen(outInfo, "fd");
}

bool AudioDecoder::finishOpen(TrackInfo *outInfo, const char *label) {
    if (avformat_find_stream_info(mFormatCtx, nullptr) < 0) {
        LOGE("avformat_find_stream_info failed");
        releaseFfmpegState();
        return false;
    }

    if (!openBestAudioStream()) {
        releaseFfmpegState();
        return false;
    }

    if (!setupResampler()) {
        releaseFfmpegState();
        return false;
    }

    mCurrentPositionMs.store(0, std::memory_order_relaxed);
    mEndOfStream = false;

    if (outInfo != nullptr) {
        AVStream *stream = mFormatCtx->streams[mStreamIndex];
        AVCodecParameters *params = stream->codecpar;
        outInfo->sampleRateHz = mOutputSampleRate;
        outInfo->channelCount = mOutputChannelCount;
        outInfo->bitDepth = params->bits_per_raw_sample > 0
                ? params->bits_per_raw_sample
                : sampleFormatToBitDepth(mCodecCtx->sample_fmt);
        outInfo->bitrateBps = params->bit_rate;

        int64_t durationMs = 0;
        if (mFormatCtx->duration > 0) {
            durationMs = mFormatCtx->duration * 1000 / AV_TIME_BASE;
        } else if (stream->duration > 0 && stream->time_base.den > 0) {
            // Container has no overall duration — fall back to the stream's own.
            durationMs = static_cast<int64_t>(stream->duration * av_q2d(stream->time_base) * 1000.0);
        }
        outInfo->durationMs = durationMs;

        // The codec's own name ("mp3", "aac", "flac", "pcm_s16le"), not the name of the
        // decoder implementation FFmpeg happened to pick ("mp3float").
        const AVCodecDescriptor *descriptor = avcodec_descriptor_get(params->codec_id);
        outInfo->formatName = (descriptor != nullptr && descriptor->name != nullptr)
                ? descriptor->name
                : "unknown";

        // "Bit depth" only means something for lossless / PCM audio. A lossy codec's
        // decoder merely outputs float or 16-bit samples — reporting that as the
        // file's bit depth made every MP3 look like a 32-bit hi-res file.
        bool lossyOnly = descriptor != nullptr &&
                (descriptor->props & AV_CODEC_PROP_LOSSY) != 0 &&
                (descriptor->props & AV_CODEC_PROP_LOSSLESS) == 0;
        if (lossyOnly) {
            outInfo->bitDepth = 0;
        }

        auto readTag = [this](const char *key) -> std::string {
            AVDictionaryEntry *entry = av_dict_get(mFormatCtx->metadata, key, nullptr, 0);
            return (entry != nullptr && entry->value != nullptr) ? std::string(entry->value) : "";
        };
        outInfo->title = readTag("title");
        outInfo->artist = readTag("artist");
        outInfo->album = readTag("album");
        std::string dateTag = readTag("date");
        if (!dateTag.empty()) {
            // "date" can be a bare year ("2023") or a full date
            // ("2023-05-12", "2023-05-12T00:00:00Z") — atoi reads the
            // leading digits either way and stops at the first non-digit.
            outInfo->year = std::atoi(dateTag.c_str());
        }

        std::string gainTag = readTag("REPLAYGAIN_TRACK_GAIN");
        if (!gainTag.empty()) {
            // Typically formatted like "-6.50 dB" — atof reads the
            // leading signed float and stops at the first non-numeric
            // character, so the " dB" suffix is harmlessly ignored.
            outInfo->trackGainDb = std::atof(gainTag.c_str());
        }
        std::string peakTag = readTag("REPLAYGAIN_TRACK_PEAK");
        if (!peakTag.empty()) {
            outInfo->trackPeakLinear = std::atof(peakTag.c_str());
        }
    }

    LOGI("Opened %s: %dHz, %d ch, decoder-reported %d-bit", label,
         mOutputSampleRate, mOutputChannelCount,
         outInfo != nullptr ? outInfo->bitDepth : -1);

    return true;
}

bool AudioDecoder::openBestAudioStream() {
    const AVCodec *decoder = nullptr;
    int streamIndex = av_find_best_stream(mFormatCtx, AVMEDIA_TYPE_AUDIO, -1, -1, &decoder, 0);
    if (streamIndex < 0 || decoder == nullptr) {
        LOGE("No audio stream found");
        return false;
    }
    mStreamIndex = streamIndex;

    mCodecCtx = avcodec_alloc_context3(decoder);
    if (mCodecCtx == nullptr) {
        LOGE("avcodec_alloc_context3 failed");
        return false;
    }

    if (avcodec_parameters_to_context(mCodecCtx, mFormatCtx->streams[streamIndex]->codecpar) < 0) {
        LOGE("avcodec_parameters_to_context failed");
        return false;
    }

    if (avcodec_open2(mCodecCtx, decoder, nullptr) < 0) {
        LOGE("avcodec_open2 failed");
        return false;
    }

    return true;
}

bool AudioDecoder::setupResampler() {
    // Output: interleaved double, source's own sample rate and channel
    // count carried straight through — no deliberate resampling here.
    // This is the "bit-perfect" contract: AudioEngine reopens the Oboe
    // stream to match mOutputSampleRate/mOutputChannelCount, rather than
    // this decoder converting the source down to some fixed rate.
    mOutputSampleRate = mCodecCtx->sample_rate;
    mOutputChannelCount = mCodecCtx->ch_layout.nb_channels;

    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, mOutputChannelCount);

    int ret = swr_alloc_set_opts2(
            &mSwrCtx,
            &outLayout, AV_SAMPLE_FMT_DBL, mOutputSampleRate,
            &mCodecCtx->ch_layout, mCodecCtx->sample_fmt, mCodecCtx->sample_rate,
            0, nullptr);
    av_channel_layout_uninit(&outLayout);

    if (ret < 0 || mSwrCtx == nullptr) {
        LOGE("swr_alloc_set_opts2 failed");
        return false;
    }

    if (swr_init(mSwrCtx) < 0) {
        LOGE("swr_init failed");
        return false;
    }

    return true;
}

void AudioDecoder::pushConvertedFrame(RingBuffer &ringBuffer) {
    int64_t pts = mFrame->best_effort_timestamp != AV_NOPTS_VALUE
            ? mFrame->best_effort_timestamp
            : mFrame->pts;
    if (pts != AV_NOPTS_VALUE) {
        double seconds = pts * av_q2d(mFormatCtx->streams[mStreamIndex]->time_base);
        mCurrentPositionMs.store(static_cast<int64_t>(seconds * 1000), std::memory_order_relaxed);
    } else if (mOutputSampleRate > 0) {
        // No timestamps in this stream — keep time by counting decoded samples.
        mCurrentPositionMs.fetch_add(
                static_cast<int64_t>(mFrame->nb_samples) * 1000 / mOutputSampleRate,
                std::memory_order_relaxed);
    }

    int outSamples = swr_get_out_samples(mSwrCtx, mFrame->nb_samples);
    if (outSamples <= 0) {
        return;
    }
    size_t neededCapacity = static_cast<size_t>(outSamples) * mOutputChannelCount;
    if (mConvertScratch.size() < neededCapacity) {
        mConvertScratch.resize(neededCapacity);
    }

    auto *outPtr = reinterpret_cast<uint8_t *>(mConvertScratch.data());
    int converted = swr_convert(mSwrCtx, &outPtr, outSamples,
                                 const_cast<const uint8_t **>(mFrame->data), mFrame->nb_samples);
    if (converted <= 0) {
        return;
    }

    size_t sampleCount = static_cast<size_t>(converted) * mOutputChannelCount;
    size_t written = 0;
    // Backpressure: if the ring buffer is momentarily full, retry rather
    // than dropping audio. Only ever called from the decode thread, so a
    // short busy-wait here does not affect real-time audio callback timing.
    while (written < sampleCount) {
        size_t n = ringBuffer.write(mConvertScratch.data() + written, sampleCount - written);
        written += n;
        if (n == 0) {
            if (mAbort.load(std::memory_order_relaxed)) {
                return;  // being stopped — the buffer is about to be flushed anyway
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(2));
        }
    }
}

bool AudioDecoder::decodeInto(RingBuffer &ringBuffer) {
    if (mEndOfStream || mFormatCtx == nullptr) {
        return false;
    }

    int readResult = av_read_frame(mFormatCtx, mPacket);
    if (readResult < 0) {
        // End of file: flush the decoder for any delayed frames it's
        // still holding, then mark end-of-stream.
        avcodec_send_packet(mCodecCtx, nullptr);
        while (avcodec_receive_frame(mCodecCtx, mFrame) == 0) {
            pushConvertedFrame(ringBuffer);
        }
        mEndOfStream = true;
        return false;
    }

    if (mPacket->stream_index != mStreamIndex) {
        av_packet_unref(mPacket);
        return true;
    }

    int sendResult = avcodec_send_packet(mCodecCtx, mPacket);
    av_packet_unref(mPacket);
    if (sendResult < 0 && sendResult != AVERROR(EAGAIN)) {
        LOGW("avcodec_send_packet error: %d", sendResult);
        return true;
    }

    while (avcodec_receive_frame(mCodecCtx, mFrame) == 0) {
        pushConvertedFrame(ringBuffer);
    }

    return true;
}

bool AudioDecoder::seekTo(int64_t positionMs) {
    if (mFormatCtx == nullptr || mStreamIndex < 0) {
        return false;
    }

    AVStream *stream = mFormatCtx->streams[mStreamIndex];
    int64_t targetTs = static_cast<int64_t>((positionMs / 1000.0) / av_q2d(stream->time_base));

    if (av_seek_frame(mFormatCtx, mStreamIndex, targetTs, AVSEEK_FLAG_BACKWARD) < 0) {
        LOGW("av_seek_frame failed for position %lld ms", static_cast<long long>(positionMs));
        return false;
    }

    avcodec_flush_buffers(mCodecCtx);
    mCurrentPositionMs.store(positionMs, std::memory_order_relaxed);
    mEndOfStream = false;
    return true;
}

void AudioDecoder::releaseFfmpegState() {
    if (mSwrCtx != nullptr) {
        swr_free(&mSwrCtx);
    }
    if (mCodecCtx != nullptr) {
        avcodec_free_context(&mCodecCtx);
    }
    if (mFormatCtx != nullptr) {
        avformat_close_input(&mFormatCtx);
    }
    if (mAvio != nullptr) {
        // With AVFMT_FLAG_CUSTOM_IO FFmpeg never frees the IO context; and it
        // may have swapped the buffer, so free the *current* one.
        av_freep(&mAvio->buffer);
        avio_context_free(&mAvio);
    }
    if (mFdSource != nullptr) {
        if (mFdSource->fd >= 0) {
            ::close(mFdSource->fd);
        }
        delete mFdSource;
        mFdSource = nullptr;
    }
    mStreamIndex = -1;
    mOutputSampleRate = 0;
    mOutputChannelCount = 0;
}

void AudioDecoder::close() {
    releaseFfmpegState();
}

}  // namespace resonix

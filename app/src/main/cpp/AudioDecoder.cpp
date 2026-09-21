#include "AudioDecoder.h"

#include <android/log.h>
#include <cstdlib>

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
        AVCodecParameters *params = mFormatCtx->streams[mStreamIndex]->codecpar;
        outInfo->sampleRateHz = mOutputSampleRate;
        outInfo->channelCount = mOutputChannelCount;
        outInfo->bitDepth = params->bits_per_raw_sample > 0
                ? params->bits_per_raw_sample
                : sampleFormatToBitDepth(mCodecCtx->sample_fmt);
        outInfo->bitrateBps = params->bit_rate;
        outInfo->durationMs = mFormatCtx->duration > 0
                ? mFormatCtx->duration * 1000 / AV_TIME_BASE
                : 0;
        const AVCodec *codec = avcodec_find_decoder(params->codec_id);
        outInfo->formatName = (codec != nullptr && codec->name != nullptr) ? codec->name : "unknown";

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

    LOGI("Opened %s: %dHz, %d ch, decoder-reported %d-bit", filePath.c_str(),
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
    if (mFrame->pts != AV_NOPTS_VALUE) {
        double seconds = mFrame->pts * av_q2d(mFormatCtx->streams[mStreamIndex]->time_base);
        mCurrentPositionMs.store(static_cast<int64_t>(seconds * 1000), std::memory_order_relaxed);
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
        written += ringBuffer.write(mConvertScratch.data() + written, sampleCount - written);
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
    mStreamIndex = -1;
    mOutputSampleRate = 0;
    mOutputChannelCount = 0;
}

void AudioDecoder::close() {
    releaseFfmpegState();
}

}  // namespace resonix

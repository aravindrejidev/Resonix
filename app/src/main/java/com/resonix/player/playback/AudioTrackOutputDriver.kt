package com.resonix.player.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.resonix.player.audio.NativeAudioEngine
import kotlin.concurrent.thread

/**
 * Last-resort output path: drives an android.media.AudioTrack write
 * loop, pulling already-DSP-processed float32 audio from the native
 * engine's ring buffer via [NativeAudioEngine.pullSamples] instead of
 * Oboe's real-time callback. Only used when Oboe itself couldn't open
 * any stream at all (see OutputPluginManager) — goes through the
 * normal Android mixer, so it does not have AAudio Exclusive mode's
 * bit-perfect/low-latency properties. The goal here is "still makes
 * sound reliably," not "still bit-perfect."
 *
 * Mono/stereo only — a reasonable limit for a fallback path; the
 * primary Oboe path has no such restriction.
 */
class AudioTrackOutputDriver(private val engine: NativeAudioEngine) {

    private var audioTrack: AudioTrack? = null
    private var pullThread: Thread? = null
    private var channelCount = 2

    @Volatile
    private var running = false

    /** Starts AudioTrack output for the given format — call once
     * engine.playTrack() has already succeeded in decoding a file, using
     * the sample rate/channel count from engine.getAudioTrackInfo(). */
    fun start(sampleRateHz: Int, channelCount: Int) {
        stop()
        this.channelCount = channelCount.coerceIn(1, 2)
        val channelMask = if (this.channelCount == 1) {
            AudioFormat.CHANNEL_OUT_MONO
        } else {
            AudioFormat.CHANNEL_OUT_STEREO
        }

        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_FLOAT
        )
        if (minBufferBytes <= 0) return

        // Several times the minimum — this path is about reliability,
        // not squeezing out the lowest possible latency.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBufferBytes * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        track.play()
        running = true
        pullThread = thread(name = "ResonixAudioTrackOutput") { pullLoop() }
    }

    private fun pullLoop() {
        val framesPerChunk = 4096
        val scratch = FloatArray(framesPerChunk * channelCount)
        val track = audioTrack ?: return

        while (running) {
            val framesRead = engine.pullSamples(scratch, framesPerChunk, channelCount)
            if (framesRead <= 0) {
                Thread.sleep(5)
                continue
            }
            // AudioTrack.write() blocks once its internal buffer is
            // full, which is also what naturally pauses this loop while
            // the track itself is paused — no separate pause-handling
            // needed here.
            track.write(scratch, 0, framesRead * channelCount, AudioTrack.WRITE_BLOCKING)
        }
    }

    fun pause() {
        try {
            audioTrack?.pause()
        } catch (_: IllegalStateException) {
            // track already stopped/released; nothing to pause
        }
    }

    fun resume() {
        try {
            audioTrack?.play()
        } catch (_: IllegalStateException) {
            // track not in a resumable state; start() should be used instead
        }
    }

    fun stop() {
        running = false
        pullThread?.join(500)
        pullThread = null
        audioTrack?.let { track ->
            try {
                track.stop()
            } catch (_: IllegalStateException) {
                // already stopped
            }
            track.release()
        }
        audioTrack = null
    }
}

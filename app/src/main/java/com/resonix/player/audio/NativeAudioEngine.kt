package com.resonix.player.audio

import kotlin.math.pow

/**
 * Exact decoded properties of the currently loaded track — what FFmpeg
 * actually decoded it as, not file tags. sampleRateHz/channelCount describe
 * the file; with "Bit-perfect output" on they are also what the Oboe stream
 * was reopened to match.
 */
data class AudioTrackInfo(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val bitrateBps: Long,
    val durationMs: Long,
    val format: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: Int = 0,
    val trackGainDb: Double = 0.0,
    val trackPeakLinear: Double = 1.0,
    val albumGainDb: Double = 0.0
)

/**
 * Thin Kotlin bridge to the native engine (AudioEngine.cpp). One
 * instance owns one native AudioEngine. [release] MUST be called
 * exactly once when this object is no longer needed (e.g. from a
 * Composable's DisposableEffect.onDispose), or the native decode
 * thread and Oboe stream leak.
 */
class NativeAudioEngine {

    private var nativeHandle: Long = 0L

    init {
        nativeHandle = nativeCreate()
    }

    /** Opens filePath, reopens the Oboe stream to match its native sample
     * rate/channel count if needed, and starts playback. Returns false if
     * the file couldn't be opened or no audio stream was found in it. */
    fun playTrack(filePath: String): Boolean {
        return if (nativeHandle != 0L) nativePlayTrack(nativeHandle, filePath) else false
    }

    /**
     * Same as [playTrack], but reads from an already-open file descriptor —
     * the way to play a content:// document (SAF folder or MediaStore), which
     * can't be re-opened by path. The engine dup()s the descriptor, so the
     * caller may close its own copy as soon as this returns.
     */
    fun playTrackFd(fd: Int): Boolean {
        return if (nativeHandle != 0L) nativePlayTrackFd(nativeHandle, fd) else false
    }

    fun pauseTrack() {
        if (nativeHandle != 0L) nativePauseTrack(nativeHandle)
    }

    fun resumeTrack() {
        if (nativeHandle != 0L) nativeResumeTrack(nativeHandle)
    }

    fun seekTo(positionMs: Long) {
        if (nativeHandle != 0L) nativeSeekTo(nativeHandle, positionMs)
    }

    fun getCurrentPositionMs(): Long {
        return if (nativeHandle != 0L) nativeGetCurrentPositionMs(nativeHandle) else 0L
    }

    fun isPlaying(): Boolean {
        return nativeHandle != 0L && nativeIsPlaying(nativeHandle)
    }

    /** True exactly once per track that finished on its own (reached the
     * end of the file, not a manual pauseTrack()) — poll this alongside
     * position updates to drive playback-queue auto-advance. */
    fun consumeTrackFinishedEvent(): Boolean {
        return nativeHandle != 0L && nativeConsumeTrackFinished(nativeHandle)
    }

    fun getAudioTrackInfo(): AudioTrackInfo {
        if (nativeHandle == 0L) {
            return AudioTrackInfo(0, 0, 0, 0L, 0L, "")
        }
        return AudioTrackInfo(
            sampleRateHz = nativeGetSampleRate(nativeHandle),
            bitDepth = nativeGetBitDepth(nativeHandle),
            channelCount = nativeGetChannelCount(nativeHandle),
            bitrateBps = nativeGetBitrateBps(nativeHandle),
            durationMs = nativeGetDurationMs(nativeHandle),
            format = nativeGetFormatName(nativeHandle),
            title = nativeGetTitle(nativeHandle),
            artist = nativeGetArtist(nativeHandle),
            album = nativeGetAlbum(nativeHandle),
            year = nativeGetYear(nativeHandle),
            trackGainDb = nativeGetTrackGainDb(nativeHandle),
            trackPeakLinear = nativeGetTrackPeak(nativeHandle),
            albumGainDb = nativeGetAlbumGainDb(nativeHandle)
        )
    }

    /** Master gain (Direct Volume Control level x audio-focus ducking):
     * linear multiplier in the native 64-bit DSP stage, applied before the
     * float32 cast Oboe sees — independent of Android's stream volume.
     * 1.0 = unity, clamped natively to [0, 4.0]. */
    fun setGain(linearGain: Double) {
        if (nativeHandle != 0L) nativeSetGain(nativeHandle, linearGain)
    }

    /** Same as [setGain] but expressed in decibels (0dB = unity). */
    fun setGainDb(db: Double) {
        setGain(10.0.pow(db / 20.0))
    }

    fun getGain(): Double {
        return if (nativeHandle != 0L) nativeGetGain(nativeHandle) else 1.0
    }

    /**
     * Settings -> Audio engine. [api]: 0 = Auto, 1 = AAudio, 2 = OpenSL ES.
     * An open output stream is reopened immediately for a changed API / exclusive
     * flag; [bitPerfect] (stream follows each file's sample rate vs. fixed 48 kHz)
     * applies from the next track.
     */
    fun setOutputConfig(api: Int, exclusive: Boolean, bitPerfect: Boolean) {
        if (nativeHandle != 0L) nativeSetOutputConfig(nativeHandle, api, exclusive, bitPerfect)
    }

    /** 0 = Off, 1 = Track, 2 = Album. */
    fun setReplayGainMode(mode: Int) {
        if (nativeHandle != 0L) nativeSetReplayGainMode(nativeHandle, mode)
    }

    fun setLimiterEnabled(enabled: Boolean) {
        if (nativeHandle != 0L) nativeSetLimiterEnabled(nativeHandle, enabled)
    }

    /**
     * Tools -> Sound shaping. [preampDb] in dB, [bassBoost] 0..1, [stereoWidth] 0..2
     * (1 = untouched image, 0 = mono), [balance] -1 (left) .. +1 (right).
     */
    fun setSoundShaping(preampDb: Double, bassBoost: Double, stereoWidth: Double, balance: Double) {
        if (nativeHandle != 0L) nativeSetSoundShaping(nativeHandle, preampDb, bassBoost, stereoWidth, balance)
    }

    /**
     * Where and in what shape the audio leaves the app (see AudioEngine::setOutputPlan). Set before
     * each track and whenever the output device changes. [deviceId] 0 = whatever Android routes to;
     * [forcedRateHz] 0 = follow the Bit-perfect setting; [formatPref] OutputFormat.nativeValue;
     * [bitPerfectMixer] the Android 14 bit-perfect mixer is engaged for this stream; [nativeRateHz]
     * the device's own mixer rate.
     */
    fun setOutputPlan(deviceId: Int, forcedRateHz: Int, formatPref: Int, bitPerfectMixer: Boolean, nativeRateHz: Int) {
        if (nativeHandle != 0L) nativeSetOutputPlan(nativeHandle, deviceId, forcedRateHz, formatPref, bitPerfectMixer, nativeRateHz)
    }

    /** The next [playTrackFd]/[playTrack] starts at [positionMs] instead of the beginning. */
    fun setNextStartPosition(positionMs: Long) {
        if (nativeHandle != 0L) nativeSetNextStartPosition(nativeHandle, positionMs)
    }

    /** [ditherMode]: 0 Off, 1 Auto, 2 Always. [resamplerQuality]: 0 Standard, 1 High, 2 Maximum. */
    fun setFidelity(ditherMode: Int, resamplerQuality: Int) {
        if (nativeHandle != 0L) nativeSetFidelity(nativeHandle, ditherMode, resamplerQuality)
    }

    /** What the output stream really is, e.g. "AAudio · Exclusive · 96 kHz". Empty before the first song. */
    fun getOutputDescription(): String {
        return if (nativeHandle != 0L) nativeGetOutputDescription(nativeHandle) else ""
    }

    /** Output Plugin System fallback: pulls up to maxFrames of fully
     * DSP-processed (gain/ReplayGain/limiter) float32 audio, interleaved,
     * into [outBuffer] (sized at least maxFrames * channelCount). Returns
     * frames actually written. Used by AudioTrackOutputDriver when Oboe
     * itself couldn't be used; not needed for normal Oboe playback. */
    fun pullSamples(outBuffer: FloatArray, maxFrames: Int, channelCount: Int): Int {
        return if (nativeHandle != 0L) {
            nativePullSamples(nativeHandle, outBuffer, maxFrames, channelCount)
        } else {
            0
        }
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePlayTrack(handle: Long, filePath: String): Boolean
    private external fun nativePlayTrackFd(handle: Long, fd: Int): Boolean
    private external fun nativePauseTrack(handle: Long)
    private external fun nativeResumeTrack(handle: Long)
    private external fun nativeSeekTo(handle: Long, positionMs: Long)
    private external fun nativeGetCurrentPositionMs(handle: Long): Long
    private external fun nativeIsPlaying(handle: Long): Boolean
    private external fun nativeConsumeTrackFinished(handle: Long): Boolean
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeGetBitDepth(handle: Long): Int
    private external fun nativeGetChannelCount(handle: Long): Int
    private external fun nativeGetBitrateBps(handle: Long): Long
    private external fun nativeGetDurationMs(handle: Long): Long
    private external fun nativeGetFormatName(handle: Long): String
    private external fun nativeGetTitle(handle: Long): String
    private external fun nativeGetArtist(handle: Long): String
    private external fun nativeGetAlbum(handle: Long): String
    private external fun nativeGetYear(handle: Long): Int
    private external fun nativeGetTrackGainDb(handle: Long): Double
    private external fun nativeGetTrackPeak(handle: Long): Double
    private external fun nativeSetGain(handle: Long, linearGain: Double)
    private external fun nativeGetGain(handle: Long): Double
    private external fun nativeSetOutputConfig(handle: Long, api: Int, exclusive: Boolean, bitPerfect: Boolean)
    private external fun nativeSetReplayGainMode(handle: Long, mode: Int)
    private external fun nativeSetLimiterEnabled(handle: Long, enabled: Boolean)
    private external fun nativeGetOutputDescription(handle: Long): String
    private external fun nativeSetSoundShaping(
        handle: Long,
        preampDb: Double,
        bassBoost: Double,
        stereoWidth: Double,
        balance: Double
    )
    private external fun nativeGetAlbumGainDb(handle: Long): Double
    private external fun nativeSetOutputPlan(
        handle: Long,
        deviceId: Int,
        forcedRateHz: Int,
        formatPref: Int,
        bitPerfectMixer: Boolean,
        nativeRateHz: Int
    )
    private external fun nativeSetFidelity(handle: Long, ditherMode: Int, resamplerQuality: Int)
    private external fun nativeSetNextStartPosition(handle: Long, positionMs: Long)
    private external fun nativePullSamples(
        handle: Long,
        outBuffer: FloatArray,
        maxFrames: Int,
        channelCount: Int
    ): Int

    companion object {
        init {
            // Must match add_library(resonix_audio ...) in CMakeLists.txt
            System.loadLibrary("resonix_audio")
        }
    }
}

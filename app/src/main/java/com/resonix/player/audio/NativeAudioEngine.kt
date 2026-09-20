package com.resonix.player.audio

/**
 * Exact decoded properties of the currently loaded track — what FFmpeg
 * actually decoded it as, not file tags. sampleRateHz/channelCount are
 * also what the Oboe stream was reopened to match (see AudioEngine's
 * bit-perfect reopen-per-track behavior).
 */
data class AudioTrackInfo(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val bitrateBps: Long,
    val durationMs: Long,
    val format: String
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
            format = nativeGetFormatName(nativeHandle)
        )
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

    companion object {
        init {
            // Must match add_library(resonix_audio ...) in CMakeLists.txt
            System.loadLibrary("resonix_audio")
        }
    }
}

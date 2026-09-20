package com.resonix.player.audio

/**
 * Opens a file just long enough to read its true decoded format (not
 * file tags) via the same FFmpeg path playback uses, then closes it.
 * Independent of NativeAudioEngine by design — a library scan using
 * this can safely run while a track is playing through a separate
 * NativeAudioEngine instance. Reuse one TrackProber across a whole
 * scan; call [release] when the scan is done.
 */
class TrackProber {

    private var nativeHandle: Long = 0L

    init {
        nativeHandle = nativeCreate()
    }

    fun probe(filePath: String): AudioTrackInfo? {
        if (nativeHandle == 0L || !nativeProbe(nativeHandle, filePath)) {
            return null
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
    private external fun nativeProbe(handle: Long, filePath: String): Boolean
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeGetBitDepth(handle: Long): Int
    private external fun nativeGetChannelCount(handle: Long): Int
    private external fun nativeGetBitrateBps(handle: Long): Long
    private external fun nativeGetDurationMs(handle: Long): Long
    private external fun nativeGetFormatName(handle: Long): String

    companion object {
        init {
            System.loadLibrary("resonix_audio")
        }
    }
}

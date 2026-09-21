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
            format = nativeGetFormatName(nativeHandle),
            title = nativeGetTitle(nativeHandle),
            artist = nativeGetArtist(nativeHandle),
            album = nativeGetAlbum(nativeHandle),
            year = nativeGetYear(nativeHandle),
            trackGainDb = nativeGetTrackGainDb(nativeHandle),
            trackPeakLinear = nativeGetTrackPeak(nativeHandle)
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
    private external fun nativeGetTitle(handle: Long): String
    private external fun nativeGetArtist(handle: Long): String
    private external fun nativeGetAlbum(handle: Long): String
    private external fun nativeGetYear(handle: Long): Int
    private external fun nativeGetTrackGainDb(handle: Long): Double
    private external fun nativeGetTrackPeak(handle: Long): Double

    companion object {
        init {
            System.loadLibrary("resonix_audio")
        }
    }
}

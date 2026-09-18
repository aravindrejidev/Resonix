package com.resonix.player.audio

/**
 * Thin Kotlin bridge to the native Oboe engine (see AudioEngine.cpp).
 *
 * One instance owns one native AudioEngine. [release] MUST be called
 * exactly once when this object is no longer needed (e.g. from a
 * Composable's DisposableEffect.onDispose), or the native stream and
 * its memory leak.
 */
class NativeAudioEngine {

    private var nativeHandle: Long = 0L

    init {
        nativeHandle = nativeCreate()
    }

    fun play() {
        if (nativeHandle != 0L) nativePlay(nativeHandle)
    }

    fun pause() {
        if (nativeHandle != 0L) nativePause(nativeHandle)
    }

    fun isPlaying(): Boolean {
        return nativeHandle != 0L && nativeIsPlaying(nativeHandle)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativePlay(handle: Long)
    private external fun nativePause(handle: Long)
    private external fun nativeIsPlaying(handle: Long): Boolean

    companion object {
        init {
            // Must match add_library(resonix_audio ...) in CMakeLists.txt
            System.loadLibrary("resonix_audio")
        }
    }
}

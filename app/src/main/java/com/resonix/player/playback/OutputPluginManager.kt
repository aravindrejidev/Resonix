package com.resonix.player.playback

import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.NativeAudioEngine

/**
 * Selects between output backends: native (Oboe, AAudio-preferred with
 * an automatic OpenSL ES retry inside AudioEngine::openStreamLocked) as
 * the default, falling back to [AudioTrackOutputDriver] only if native
 * output couldn't be opened at all. Exposes the same method surface
 * [NativeAudioEngine] does, so anything currently holding a
 * NativeAudioEngine reference (PlaybackQueueManager, PlaybackService,
 * ResonixPlayer) can switch to holding this instead with just a
 * constructor-parameter type change — nothing else about how they call
 * it needs to change. Chromecast output is intentionally not included
 * here.
 */
class OutputPluginManager {

    private val engine = NativeAudioEngine()
    private val audioTrackFallback = AudioTrackOutputDriver(engine)
    private var usingFallback = false

    fun playTrack(filePath: String): Boolean {
        val nativeStarted = engine.playTrack(filePath)
        if (nativeStarted) {
            if (usingFallback) {
                audioTrackFallback.stop()
                usingFallback = false
            }
            return true
        }

        // engine.playTrack() already tried AAudio then, internally,
        // OpenSL ES forced (see AudioEngine::openStreamLocked) — both
        // failed. getAudioTrackInfo() still reflects the file's decoded
        // format if decode itself succeeded (see the reset-then-set
        // pattern in AudioEngine::playTrack), which is how this tells
        // "decode failed too, nothing to fall back to" apart from
        // "decode was fine, only native output failed."
        val info = engine.getAudioTrackInfo()
        if (info.sampleRateHz <= 0) {
            return false
        }

        audioTrackFallback.start(info.sampleRateHz, info.channelCount)
        usingFallback = true
        return true
    }

    fun pauseTrack() {
        if (usingFallback) audioTrackFallback.pause() else engine.pauseTrack()
    }

    fun resumeTrack() {
        if (usingFallback) audioTrackFallback.resume() else engine.resumeTrack()
    }

    /** Same decoder either way — the fallback only replaces the last
     * "hand samples to the OS" step, not decoding or seeking. */
    fun seekTo(positionMs: Long) = engine.seekTo(positionMs)

    fun getCurrentPositionMs(): Long = engine.getCurrentPositionMs()

    fun isPlaying(): Boolean = engine.isPlaying()

    fun getAudioTrackInfo(): AudioTrackInfo = engine.getAudioTrackInfo()

    fun consumeTrackFinishedEvent(): Boolean = engine.consumeTrackFinishedEvent()

    fun setGain(linearGain: Double) = engine.setGain(linearGain)

    fun setGainDb(db: Double) = engine.setGainDb(db)

    fun getGain(): Double = engine.getGain()

    /** True if the last playTrack() had to fall back to AudioTrack
     * because no native (AAudio/OpenSL ES) stream could be opened —
     * useful if you want to surface this in the UI. */
    fun isUsingFallbackOutput(): Boolean = usingFallback

    fun release() {
        audioTrackFallback.stop()
        engine.release()
    }
}

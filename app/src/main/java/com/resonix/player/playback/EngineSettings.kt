package com.resonix.player.playback

import com.resonix.player.audio.EngineBand
import com.resonix.player.audio.EqLayout
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.ui.SettingsState

/**
 * Pushes the user's Settings / Tools choices into the native engine. Called once when the
 * service starts and again on every settings change; every call is cheap and idempotent (the
 * engine only reopens its output stream when the API or exclusive flag really changed).
 *
 * The graphic and the parametric equalizer travel together as one cascade (see EqLayout).
 */
internal object EngineSettings {

    // The equalizer is the costly part of an apply (it measures the combined response for the
    // headroom); skip it when nothing about it changed — e.g. while the volume slider is dragged.
    private var lastEngine: NativeAudioEngine? = null
    private var lastEqKey: Int = 0

    private fun applyEq(engine: NativeAudioEngine, s: SettingsState) {
        val bands: List<EngineBand> = EqLayout.build(s.eqEnabled, s.eqBands, s.peqEnabled, s.peqBands)
        val enabled = s.eqEnabled || s.peqEnabled
        val key = listOf(enabled, s.eqAutoHeadroom, bands).hashCode()
        synchronized(this) {
            if (lastEngine === engine && lastEqKey == key) return
            lastEngine = engine
            lastEqKey = key
        }
        engine.setEq(enabled, s.eqAutoHeadroom, bands)
    }

    fun apply(engine: NativeAudioEngine, s: SettingsState) {
        applyEq(engine, s)
        engine.setOutputConfig(
            api = s.outputEngine.ordinal,        // Auto / AAudio / OpenSL ES
            exclusive = s.exclusiveMode,
            bitPerfect = s.bitPerfect,
        )
        engine.setReplayGainMode(s.replayGain.ordinal)  // Off / Track / Album
        engine.setLimiterEnabled(s.peakLimiter)
        engine.setFidelity(
            ditherMode = s.dither.ordinal,                // Off / Auto / Always
            resamplerQuality = s.resamplerQuality.ordinal, // Standard / High / Maximum
        )
        engine.setSoundShaping(
            preampDb = s.preamp.toDouble(),
            bassBoost = s.bassBoost.toDouble(),
            stereoWidth = s.stereoWidth.toDouble() * 2.0,  // UI: 0.5 = original image → engine: 1.0
            balance = s.balance.toDouble(),
        )
    }

    /**
     * Direct volume control: the gain of the engine for a level (0 … 1). A squared taper, so the
     * lower half of the slider has useful resolution (50 % ≈ −12 dB, 10 % ≈ −40 dB).
     */
    fun gainForLevel(level: Float): Double = VolumeMath.gainForLevel(level)
}

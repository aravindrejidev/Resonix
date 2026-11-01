package com.resonix.player.playback

import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.ui.SettingsState

/**
 * Pushes the user's Settings / Tools choices into the native engine. Called once when the
 * service starts and again on every settings change; every call is cheap and idempotent (the
 * engine only reopens its output stream when the API or exclusive flag really changed).
 *
 * The equalizer fields are deliberately not applied here — the EQ is not wired up yet.
 */
internal object EngineSettings {

    fun apply(engine: NativeAudioEngine, s: SettingsState) {
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

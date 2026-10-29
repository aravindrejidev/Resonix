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
        engine.setSoundShaping(
            preampDb = s.preamp.toDouble(),
            bassBoost = s.bassBoost.toDouble(),
            stereoWidth = s.stereoWidth.toDouble() * 2.0,  // UI: 0.5 = original image → engine: 1.0
            balance = s.balance.toDouble(),
        )
    }

    /**
     * Direct volume control: the level inside the engine. A squared taper, so the lower half
     * of the slider has useful resolution (50 % ≈ −12 dB, 10 % ≈ −40 dB). Off = unity — the
     * system volume then does all the work.
     */
    fun directVolumeGain(s: SettingsState): Double {
        if (!s.directVolume) return 1.0
        val level = s.directVolumeLevel.toDouble().coerceIn(0.0, 1.0)
        return level * level
    }
}

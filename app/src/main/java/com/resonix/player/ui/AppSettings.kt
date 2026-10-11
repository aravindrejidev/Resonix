package com.resonix.player.ui

import android.content.Context
import com.resonix.player.ui.theme.AccentColor
import com.resonix.player.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How songs inside a folder are ordered on Home. */
enum class SortKey(val label: String) {
    TITLE("Title"),
    ARTIST("Artist"),
    DATE_ADDED("Date added"),
    DURATION("Length"),
}

/** Which audio backend to prefer. Stored only — NativeAudioEngine doesn't read it yet. */
enum class OutputEngine(val label: String) {
    AUTO("Auto"),
    AAUDIO("AAudio"),
    OPENSL("OpenSL ES"),
}

/** Stored only — NativeAudioEngine doesn't read it yet. */
enum class ReplayGainMode(val label: String) {
    OFF("Off"),
    TRACK("Track"),
    ALBUM("Album"),
}

const val EQ_BAND_COUNT = 10

/**
 * Everything the user can change from Settings and Tools. One immutable object so a screen
 * can read what it needs with a single collect, and update it with `copy`.
 *
 * Fields marked "UI only" are saved and shown, but nothing in the audio engine reads them yet —
 * they are the hook points for wiring the engine later.
 */
data class SettingsState(
    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: AccentColor = AccentColor.CRIMSON,
    val haptics: Boolean = true,

    // Home / lists
    val showQualityBadges: Boolean = true,
    val showAlbumArt: Boolean = true,
    val sortKey: SortKey = SortKey.TITLE,
    val sortAscending: Boolean = true,
    /** Which folder chip Home was showing last (a ScannedFolder rootUri, "" for the device library, null = never chosen). */
    val lastSourceKey: String? = null,

    // Playback
    val keepScreenOn: Boolean = true,

    // Audio engine — UI only
    val outputEngine: OutputEngine = OutputEngine.AUTO,
    val exclusiveMode: Boolean = true,
    val bitPerfect: Boolean = true,
    val directVolume: Boolean = false,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val peakLimiter: Boolean = true,

    // Tools — UI only
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    /** One gain per band in dB, −12 … +12. */
    val eqBands: List<Float> = List(EQ_BAND_COUNT) { 0f },
    /** dB, −12 … +12. */
    val preamp: Float = 0f,
    /** 0 … 1. */
    val bassBoost: Float = 0f,
    /** 0 … 1, where 0.5 is the original stereo image. */
    val stereoWidth: Float = 0.5f,
    /** −1 (left) … +1 (right). */
    val balance: Float = 0f,
)

/**
 * SharedPreferences-backed store for [SettingsState]. Deliberately dependency-free (no DataStore)
 * so the project needs no new Gradle dependency for the UI.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    fun update(transform: (SettingsState) -> SettingsState) {
        val current = _state.value
        val next = transform(current)
        if (next == current) return
        _state.value = next
        save(next)
    }

    private fun load(): SettingsState {
        val d = SettingsState()
        val bands = prefs.getString(KEY_EQ_BANDS, null)
            ?.split(',')
            ?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size == EQ_BAND_COUNT }
            ?: d.eqBands
        return SettingsState(
            themeMode = parseEnum(prefs.getString(KEY_THEME, null), d.themeMode),
            accent = parseEnum(prefs.getString(KEY_ACCENT, null), d.accent),
            haptics = prefs.getBoolean(KEY_HAPTICS, d.haptics),
            showQualityBadges = prefs.getBoolean(KEY_BADGES, d.showQualityBadges),
            showAlbumArt = prefs.getBoolean(KEY_ART, d.showAlbumArt),
            sortKey = parseEnum(prefs.getString(KEY_SORT, null), d.sortKey),
            sortAscending = prefs.getBoolean(KEY_SORT_ASC, d.sortAscending),
            lastSourceKey = prefs.getString(KEY_SOURCE, null),
            keepScreenOn = prefs.getBoolean(KEY_KEEP_ON, d.keepScreenOn),
            outputEngine = parseEnum(prefs.getString(KEY_OUTPUT, null), d.outputEngine),
            exclusiveMode = prefs.getBoolean(KEY_EXCLUSIVE, d.exclusiveMode),
            bitPerfect = prefs.getBoolean(KEY_BIT_PERFECT, d.bitPerfect),
            directVolume = prefs.getBoolean(KEY_DVC, d.directVolume),
            replayGain = parseEnum(prefs.getString(KEY_REPLAY_GAIN, null), d.replayGain),
            peakLimiter = prefs.getBoolean(KEY_LIMITER, d.peakLimiter),
            eqEnabled = prefs.getBoolean(KEY_EQ_ON, d.eqEnabled),
            eqPreset = prefs.getString(KEY_EQ_PRESET, d.eqPreset) ?: d.eqPreset,
            eqBands = bands,
            preamp = prefs.getFloat(KEY_PREAMP, d.preamp),
            bassBoost = prefs.getFloat(KEY_BASS, d.bassBoost),
            stereoWidth = prefs.getFloat(KEY_WIDTH, d.stereoWidth),
            balance = prefs.getFloat(KEY_BALANCE, d.balance),
        )
    }

    private fun save(s: SettingsState) {
        prefs.edit()
            .putString(KEY_THEME, s.themeMode.name)
            .putString(KEY_ACCENT, s.accent.name)
            .putBoolean(KEY_HAPTICS, s.haptics)
            .putBoolean(KEY_BADGES, s.showQualityBadges)
            .putBoolean(KEY_ART, s.showAlbumArt)
            .putString(KEY_SORT, s.sortKey.name)
            .putBoolean(KEY_SORT_ASC, s.sortAscending)
            .putString(KEY_SOURCE, s.lastSourceKey)
            .putBoolean(KEY_KEEP_ON, s.keepScreenOn)
            .putString(KEY_OUTPUT, s.outputEngine.name)
            .putBoolean(KEY_EXCLUSIVE, s.exclusiveMode)
            .putBoolean(KEY_BIT_PERFECT, s.bitPerfect)
            .putBoolean(KEY_DVC, s.directVolume)
            .putString(KEY_REPLAY_GAIN, s.replayGain.name)
            .putBoolean(KEY_LIMITER, s.peakLimiter)
            .putBoolean(KEY_EQ_ON, s.eqEnabled)
            .putString(KEY_EQ_PRESET, s.eqPreset)
            .putString(KEY_EQ_BANDS, s.eqBands.joinToString(","))
            .putFloat(KEY_PREAMP, s.preamp)
            .putFloat(KEY_BASS, s.bassBoost)
            .putFloat(KEY_WIDTH, s.stereoWidth)
            .putFloat(KEY_BALANCE, s.balance)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "resonix_ui_settings"
        const val KEY_THEME = "theme_mode"
        const val KEY_ACCENT = "accent"
        const val KEY_HAPTICS = "haptics"
        const val KEY_BADGES = "show_quality_badges"
        const val KEY_ART = "show_album_art"
        const val KEY_SORT = "sort_key"
        const val KEY_SORT_ASC = "sort_ascending"
        const val KEY_SOURCE = "last_source_key"
        const val KEY_KEEP_ON = "keep_screen_on"
        const val KEY_OUTPUT = "output_engine"
        const val KEY_EXCLUSIVE = "exclusive_mode"
        const val KEY_BIT_PERFECT = "bit_perfect"
        const val KEY_DVC = "direct_volume"
        const val KEY_REPLAY_GAIN = "replay_gain"
        const val KEY_LIMITER = "peak_limiter"
        const val KEY_EQ_ON = "eq_enabled"
        const val KEY_EQ_PRESET = "eq_preset"
        const val KEY_EQ_BANDS = "eq_bands"
        const val KEY_PREAMP = "preamp_db"
        const val KEY_BASS = "bass_boost"
        const val KEY_WIDTH = "stereo_width"
        const val KEY_BALANCE = "balance"
    }
}

private inline fun <reified T : Enum<T>> parseEnum(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

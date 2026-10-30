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

/** Which audio backend to prefer. The ordinal is what the native engine receives (0 = Auto, 1 = AAudio, 2 = OpenSL ES). */
enum class OutputEngine(val label: String) {
    AUTO("Auto"),
    AAUDIO("AAudio"),
    OPENSL("OpenSL ES"),
}

/** The ordinal is what the native engine receives (0 = Off, 1 = Track, 2 = Album). */
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
 * The audio-engine and sound-shaping fields are applied to the native engine by PlaybackService
 * (see EngineSettings.kt). Only the equalizer fields are still "UI only": saved and shown, but
 * nothing in the engine reads them yet.
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
    /** Now Playing: the cover fills the top of the screen (tinted background) instead of sitting on a plate. */
    val fullFrameArt: Boolean = true,

    // Audio engine
    val outputEngine: OutputEngine = OutputEngine.AUTO,
    val exclusiveMode: Boolean = true,
    val bitPerfect: Boolean = true,
    val directVolume: Boolean = false,
    /** 0 … 1 — the level of Direct volume control (only used while that switch is on). */
    val directVolumeLevel: Float = 1f,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val peakLimiter: Boolean = true,

    // Tools — equalizer (UI only for now)
    val eqEnabled: Boolean = false,
    val eqPreset: String = "Flat",
    /** One gain per band in dB, −12 … +12. */
    val eqBands: List<Float> = List(EQ_BAND_COUNT) { 0f },

    // Tools — sound shaping (applied by the engine)
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
 *
 * One instance per process ([get]): the screens edit it and PlaybackService listens to it, so a
 * change in Settings reaches the audio engine immediately.
 */
class AppSettings private constructor(context: Context) {

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
            fullFrameArt = prefs.getBoolean(KEY_FULL_FRAME_ART, d.fullFrameArt),
            outputEngine = parseEnum(prefs.getString(KEY_OUTPUT, null), d.outputEngine),
            exclusiveMode = prefs.getBoolean(KEY_EXCLUSIVE, d.exclusiveMode),
            bitPerfect = prefs.getBoolean(KEY_BIT_PERFECT, d.bitPerfect),
            directVolume = prefs.getBoolean(KEY_DVC, d.directVolume),
            directVolumeLevel = prefs.getFloat(KEY_DVC_LEVEL, d.directVolumeLevel).coerceIn(0f, 1f),
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
            .putBoolean(KEY_FULL_FRAME_ART, s.fullFrameArt)
            .putString(KEY_OUTPUT, s.outputEngine.name)
            .putBoolean(KEY_EXCLUSIVE, s.exclusiveMode)
            .putBoolean(KEY_BIT_PERFECT, s.bitPerfect)
            .putBoolean(KEY_DVC, s.directVolume)
            .putFloat(KEY_DVC_LEVEL, s.directVolumeLevel)
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

    companion object {
        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(context.applicationContext).also { instance = it }
            }

        private const val PREFS_NAME = "resonix_ui_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_ACCENT = "accent"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_BADGES = "show_quality_badges"
        private const val KEY_ART = "show_album_art"
        private const val KEY_SORT = "sort_key"
        private const val KEY_SORT_ASC = "sort_ascending"
        private const val KEY_SOURCE = "last_source_key"
        private const val KEY_KEEP_ON = "keep_screen_on"
        private const val KEY_FULL_FRAME_ART = "full_frame_art"
        private const val KEY_OUTPUT = "output_engine"
        private const val KEY_EXCLUSIVE = "exclusive_mode"
        private const val KEY_BIT_PERFECT = "bit_perfect"
        private const val KEY_DVC = "direct_volume"
        private const val KEY_DVC_LEVEL = "direct_volume_level"
        private const val KEY_REPLAY_GAIN = "replay_gain"
        private const val KEY_LIMITER = "peak_limiter"
        private const val KEY_EQ_ON = "eq_enabled"
        private const val KEY_EQ_PRESET = "eq_preset"
        private const val KEY_EQ_BANDS = "eq_bands"
        private const val KEY_PREAMP = "preamp_db"
        private const val KEY_BASS = "bass_boost"
        private const val KEY_WIDTH = "stereo_width"
        private const val KEY_BALANCE = "balance"
    }
}

private inline fun <reified T : Enum<T>> parseEnum(name: String?, default: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: default

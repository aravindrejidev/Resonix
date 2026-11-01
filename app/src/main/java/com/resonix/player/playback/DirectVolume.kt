package com.resonix.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.os.Build
import kotlin.math.roundToInt

/**
 * The system-facing half of Direct volume control (the arithmetic is in [VolumeMath]).
 *
 * DVC means the *engine* sets the loudness, in 64-bit precision with dither, so that Android's own
 * volume can stay out of the signal path. Three things make that real:
 *
 *  1. **Fold** — the current system attenuation is moved into the engine's level once, so switching
 *     DVC on never changes how loud the music is.
 *  2. **Park** — while music plays, Android's music volume sits at maximum (and is put back to a
 *     step of equal loudness when playback stops, so another app is never blasted).
 *  3. **Remote volume** — the hardware volume keys control the engine's level instead of the
 *     parked system volume (a media session in "remote playback" mode).
 */
internal class DirectVolume(
    context: Context,
    private val session: MediaSession,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True while Android's music volume is held at maximum by us. */
    var parked: Boolean = prefs.contains(KEY_RESTORE_INDEX)
        private set

    private var provider: VolumeProvider? = null

    // --- fold ----------------------------------------------------------------------------

    /** [level] with Android's current music attenuation moved into it (unchanged if the system is at maximum). */
    fun fold(level: Float, deviceType: Int): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (index >= max) return level
        val db = systemDb(index, max, deviceType)
        return if (db != null) {
            VolumeMath.preservedLevel(level, db)
        } else {
            // No dB curve available (old Android): assume a straight amplitude ramp.
            (level * (index.toFloat() / max)).coerceIn(VolumeMath.MIN_LEVEL, 1f)
        }
    }

    // --- park / unpark ------------------------------------------------------------------------

    /** Raises Android's music volume to maximum; the engine level already compensates. */
    fun park(level: Float, deviceType: Int) {
        if (parked) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        // Where it should go back to (also if this process dies while parked): the step that sounds
        // as loud as the engine's level on its own.
        prefs.edit().putInt(KEY_RESTORE_INDEX, indexForLevel(level, max, deviceType) ?: index).apply()
        if (index < max) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
            } catch (_: SecurityException) {
                prefs.edit().remove(KEY_RESTORE_INDEX).apply()
                return
            }
        }
        parked = true
    }

    /** Puts Android's music volume to the step that sounds as loud as the engine's [level] alone. */
    fun unpark(level: Float, deviceType: Int) {
        if (!parked) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = indexForLevel(level, max, deviceType) ?: prefs.getInt(KEY_RESTORE_INDEX, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, max), 0)
        } catch (_: SecurityException) {
        }
        prefs.edit().remove(KEY_RESTORE_INDEX).apply()
        parked = false
    }

    /**
     * The output device changed while the volume was parked: that device keeps its parked (maximum)
     * volume, which this service can no longer reach. Remember it so it is put right when that kind
     * of device comes back and DVC is not in charge of it.
     */
    fun forget(deviceType: Int) {
        if (!parked) return
        prefs.edit()
            .putInt(KEY_STALE_TYPE, deviceType)
            .putInt(KEY_STALE_INDEX, prefs.getInt(KEY_RESTORE_INDEX, -1))
            .remove(KEY_RESTORE_INDEX)
            .apply()
        parked = false
    }

    /** A device that was left parked earlier is the current one again, and DVC is off: restore its volume. */
    fun fixStale(deviceType: Int) {
        if (!prefs.contains(KEY_STALE_TYPE) || prefs.getInt(KEY_STALE_TYPE, -1) != deviceType) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = prefs.getInt(KEY_STALE_INDEX, -1)
        if (index in 0 until max) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            } catch (_: SecurityException) {
            }
        }
        prefs.edit().remove(KEY_STALE_TYPE).remove(KEY_STALE_INDEX).apply()
    }

    /** After a crash while parked: put the volume back to what was saved. */
    fun restoreLeftover() {
        if (!prefs.contains(KEY_RESTORE_INDEX)) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = prefs.getInt(KEY_RESTORE_INDEX, max).coerceIn(0, max)
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        } catch (_: SecurityException) {
        }
        prefs.edit().remove(KEY_RESTORE_INDEX).apply()
        parked = false
    }

    private fun indexForLevel(level: Float, max: Int, deviceType: Int): Int? {
        val dbs = ArrayList<Float>(max + 1)
        for (i in 0..max) dbs.add(systemDb(i, max, deviceType) ?: return null)
        return VolumeMath.systemIndexFor(level, dbs)
    }

    /** Attenuation of music-volume step [index] relative to [max], in dB; null if Android can't say. */
    private fun systemDb(index: Int, max: Int, deviceType: Int): Float? {
        if (Build.VERSION.SDK_INT < 28) return null
        return try {
            val at = audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, index, deviceType)
            val top = audioManager.getStreamVolumeDb(AudioManager.STREAM_MUSIC, max, deviceType)
            if (at.isNaN() || top.isNaN() || top.isInfinite()) null else at - top
        } catch (e: Exception) {
            null
        }
    }

    // --- hardware volume keys ----------------------------------------------------------------

    /** Routes the volume keys to [onLevel] (0 … 1) for as long as this media session is the active one. */
    fun installRemote(level: Float, onLevel: (Float) -> Unit) {
        val existing = provider
        if (existing != null) {
            updateRemote(level)
            return
        }
        val created = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, REMOTE_STEPS, (level * REMOTE_STEPS).roundToInt()) {
            override fun onSetVolumeTo(volume: Int) {
                onLevel((volume.coerceIn(0, REMOTE_STEPS)) / REMOTE_STEPS.toFloat())
            }

            override fun onAdjustVolume(direction: Int) {
                if (direction != 1 && direction != -1) return  // mute toggles etc. are not DVC's business
                val current = currentVolume / REMOTE_STEPS.toFloat()
                // Finer steps where the level is low: the engine's gain is a square law.
                val step = if (current < 0.2f) 0.02f else 0.04f
                onLevel((current + direction * step).coerceIn(0f, 1f))
            }
        }
        provider = created
        session.setPlaybackToRemote(created)
    }

    fun updateRemote(level: Float) {
        provider?.currentVolume = (level.coerceIn(0f, 1f) * REMOTE_STEPS).roundToInt()
    }

    fun removeRemote() {
        if (provider == null) return
        provider = null
        session.setPlaybackToLocal(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
    }

    private companion object {
        const val PREFS = "resonix_dvc_state"
        const val KEY_RESTORE_INDEX = "restore_index"
        const val KEY_STALE_TYPE = "stale_device_type"
        const val KEY_STALE_INDEX = "stale_index"
        const val REMOTE_STEPS = 100
    }
}

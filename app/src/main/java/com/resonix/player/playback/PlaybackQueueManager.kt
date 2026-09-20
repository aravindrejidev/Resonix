package com.resonix.player.playback

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.data.Track
import com.resonix.player.data.TrackDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "PlaybackQueueManager"

/**
 * Owns "play this folder" as an ordered (or shuffled) queue fed to the
 * native engine one track at a time. Call [checkForAutoAdvance]
 * periodically from wherever position is already being polled (e.g.
 * the same loop MainActivity uses for getCurrentPositionMs()) — it
 * consumes AudioEngine's track-finished event and advances the queue
 * when a track ends on its own.
 *
 * One instance is meant to be reused for the app's whole session; call
 * [release] when it's no longer needed (e.g. from onDispose alongside
 * releasing the NativeAudioEngine it wraps).
 */
class PlaybackQueueManager(
    context: Context,
    private val engine: NativeAudioEngine,
    private val trackDao: TrackDao
) {
    private val appContext = context.applicationContext

    private var queue: List<Track> = emptyList()
    private var originalOrder: List<Track> = emptyList()
    private var currentIndex: Int = -1
    private var shuffled: Boolean = false
    private var openFd: ParcelFileDescriptor? = null

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    val hasNext: Boolean get() = currentIndex + 1 < queue.size
    val hasPrevious: Boolean get() = currentIndex > 0

    /** Loads every track directly inside [folderPath] (see
     * TrackDao.getTracksInFolder — not recursive into subfolders) and
     * starts playing from the first one. */
    suspend fun playFolder(folderPath: String, shuffle: Boolean = false) {
        val tracks = trackDao.getTracksInFolder(folderPath)
        originalOrder = tracks
        shuffled = shuffle
        queue = if (shuffle) tracks.shuffled() else tracks
        currentIndex = 0
        playCurrent()
    }

    /** Re-shuffles (or un-shuffles) the remaining queue in place, keeping
     * whatever's currently playing as the current position. */
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffled || queue.isEmpty()) return
        val current = queue.getOrNull(currentIndex)
        queue = if (enabled) originalOrder.shuffled() else originalOrder
        shuffled = enabled
        currentIndex = current?.let { queue.indexOf(it) }?.takeIf { it >= 0 } ?: 0
    }

    fun playNext(): Boolean {
        if (!hasNext) return false
        currentIndex++
        playCurrent()
        return true
    }

    fun playPrevious(): Boolean {
        if (!hasPrevious) return false
        currentIndex--
        playCurrent()
        return true
    }

    /** Call periodically (e.g. alongside position polling). Advances the
     * queue exactly once per track that finished on its own. */
    fun checkForAutoAdvance() {
        if (engine.consumeTrackFinishedEvent()) {
            playNext()
        }
    }

    private fun playCurrent() {
        val track = queue.getOrNull(currentIndex) ?: return
        try {
            openFd?.close()
            val pfd = appContext.contentResolver.openFileDescriptor(track.contentUri.toUri(), "r")
            openFd = pfd
            if (pfd != null) {
                engine.playTrack("/proc/self/fd/${pfd.fd}")
                _currentTrack.value = track
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open ${track.contentUri}", e)
        }
    }

    fun release() {
        openFd?.close()
        openFd = null
    }
}

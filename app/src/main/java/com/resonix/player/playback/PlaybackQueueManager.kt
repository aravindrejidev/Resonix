package com.resonix.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.data.Track
import com.resonix.player.data.TrackDao
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "PlaybackQueueManager"

/** After this many unplayable tracks in a row, stop skipping ahead instead of racing through a broken folder. */
private const val MAX_CONSECUTIVE_FAILURES = 3

/**
 * What is loaded right now, plus its neighbours in the queue.
 *
 * [info] is what the decoder actually found in the file, which beats the values the
 * library stored at scan time (those can be 0 / empty for files that scanned badly).
 */
data class NowPlaying(
    val track: Track,
    val previous: Track?,
    val next: Track?,
    val info: AudioTrackInfo,
)

/**
 * Owns "play this folder" as an ordered (or shuffled) queue fed to the
 * native engine one track at a time. Call [checkForAutoAdvance]
 * periodically — it consumes AudioEngine's track-finished event and
 * advances the queue when a track ends on its own.
 *
 * Threading: every public member is meant to be called on the main thread.
 * Opening a file (and the audio output stream) can take hundreds of
 * milliseconds, so that work runs on a private single-thread executor and
 * the result is posted back; a load that was superseded by a newer request
 * (user skipped again, picked another song) is dropped. Files are handed to
 * the engine as an open file descriptor — never re-opened by path, which
 * Android denies for content:// documents.
 *
 * One instance is meant to be reused for the app's whole session; call
 * [release] when it's no longer needed.
 */
class PlaybackQueueManager(
    context: Context,
    private val engine: NativeAudioEngine,
    private val trackDao: TrackDao
) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "resonix-track-loader")
    }

    private var queue: List<Track> = emptyList()
    private var originalOrder: List<Track> = emptyList()
    private var currentIndex: Int = -1
    private var shuffled: Boolean = false
    private var consecutiveFailures: Int = 0
    private var queueFinished: Boolean = false
    private var released: Boolean = false

    /** Bumped for every load request; only the newest request's result is applied. */
    @Volatile
    private var loadToken: Int = 0

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _isLoading = MutableStateFlow(false)

    /** True from the moment a track is requested until it is actually playing (or failed). */
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val hasNext: Boolean get() = currentIndex + 1 < queue.size
    val hasPrevious: Boolean get() = currentIndex > 0

    /** Loads every track directly inside [folderPath] under
     * [rootFolderUri] (see TrackDao.getTracksInFolder — not recursive
     * into subfolders) and starts playing. Pass "" for rootFolderUri for
     * MediaStore-sourced tracks (they have no folder concept, so this
     * plays them all together as one queue).
     *
     * [startContentUri] starts from that track (the one the user tapped);
     * when shuffling, that track simply leads the queue. [sortKey] /
     * [sortAscending] give the queue the same order as the list on screen
     * (SortKey names: TITLE, ARTIST, DATE_ADDED, DURATION). */
    suspend fun playFolder(
        rootFolderUri: String,
        folderPath: String,
        shuffle: Boolean = false,
        startContentUri: String? = null,
        sortKey: String? = null,
        sortAscending: Boolean = true,
    ) {
        val tracks = orderTracks(trackDao.getTracksInFolder(rootFolderUri, folderPath), sortKey, sortAscending)
        if (tracks.isEmpty()) {
            AppMessages.post("There are no songs in this folder")
            return
        }
        originalOrder = tracks
        shuffled = shuffle
        val start = startContentUri?.let { uri -> tracks.firstOrNull { it.contentUri == uri } }
        queue = when {
            !shuffle -> tracks
            start != null -> listOf(start) + (tracks - start).shuffled()
            else -> tracks.shuffled()
        }
        currentIndex = if (shuffle || start == null) 0 else tracks.indexOf(start)
        consecutiveFailures = 0
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
        publishNeighbours()
    }

    fun playNext(): Boolean {
        if (!hasNext) return false
        currentIndex++
        consecutiveFailures = 0
        playCurrent()
        return true
    }

    fun playPrevious(): Boolean {
        if (!hasPrevious) return false
        currentIndex--
        consecutiveFailures = 0
        playCurrent()
        return true
    }

    /** Resumes playback — or, if the queue already played out to its end, starts the current track again. */
    fun resumeOrRestart() {
        if (queueFinished && queue.getOrNull(currentIndex) != null) {
            consecutiveFailures = 0
            playCurrent()
        } else {
            engine.resumeTrack()
        }
    }

    /** Call periodically (e.g. alongside position polling). Advances the
     * queue exactly once per track that finished on its own. */
    fun checkForAutoAdvance() {
        if (!engine.consumeTrackFinishedEvent()) return
        if (_isLoading.value) return  // a load is in flight; its result decides what plays
        if (!playNext()) queueFinished = true  // last track done — the engine has stopped itself
    }

    /** Must match the comparators HomeScreen uses to sort the song list. */
    private fun orderTracks(tracks: List<Track>, sortKey: String?, ascending: Boolean): List<Track> {
        val comparator: Comparator<Track> = when (sortKey) {
            "ARTIST" -> compareBy<Track> { it.artist.lowercase() }.thenBy { it.title.lowercase() }
            "DATE_ADDED" -> compareBy { it.dateAddedSec }
            "DURATION" -> compareBy { it.durationMs }
            else -> compareBy { it.title.lowercase() }  // TITLE, and the default
        }
        return if (ascending) tracks.sortedWith(comparator) else tracks.sortedWith(comparator.reversed())
    }

    private fun playCurrent() {
        val index = currentIndex
        val track = queue.getOrNull(index) ?: return
        val token = ++loadToken
        queueFinished = false
        _isLoading.value = true
        loader.execute {
            if (token != loadToken) return@execute  // superseded while it was still waiting in line
            val outcome = loadAndPlay(track)
            mainHandler.post { onLoaded(token, index, track, outcome) }
        }
    }

    private sealed interface LoadOutcome {
        class Playing(val info: AudioTrackInfo) : LoadOutcome
        class Failed(val reason: String) : LoadOutcome
    }

    /** Runs on the loader thread. */
    private fun loadAndPlay(track: Track): LoadOutcome {
        return try {
            val pfd = appContext.contentResolver.openFileDescriptor(track.contentUri.toUri(), "r")
                ?: return LoadOutcome.Failed("the file could not be opened")
            val started = pfd.use { engine.playTrackFd(it.fd) }  // engine dup()s the fd
            if (started) {
                LoadOutcome.Playing(engine.getAudioTrackInfo())
            } else if (engine.getAudioTrackInfo().sampleRateHz > 0) {
                LoadOutcome.Failed("the audio output could not be opened")
            } else {
                LoadOutcome.Failed("this file could not be decoded")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "No permission for ${track.contentUri}", e)
            LoadOutcome.Failed("no permission to read it — add its folder again")
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Missing ${track.contentUri}", e)
            LoadOutcome.Failed("the file is missing — rescan the folder")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play ${track.contentUri}", e)
            LoadOutcome.Failed("something went wrong opening it")
        }
    }

    /** Main thread. */
    private fun onLoaded(token: Int, index: Int, track: Track, outcome: LoadOutcome) {
        if (released || token != loadToken) return  // a newer request has taken over
        _isLoading.value = false
        when (outcome) {
            is LoadOutcome.Playing -> {
                consecutiveFailures = 0
                _nowPlaying.value = NowPlaying(
                    track = track,
                    previous = queue.getOrNull(index - 1),
                    next = queue.getOrNull(index + 1),
                    info = outcome.info,
                )
            }
            is LoadOutcome.Failed -> {
                consecutiveFailures++
                AppMessages.post("Can't play “${track.title}”: ${outcome.reason}")
                if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
                    playNextAfterFailure()
                }
            }
        }
    }

    private fun playNextAfterFailure() {
        if (!hasNext) {
            queueFinished = true
            return
        }
        currentIndex++
        playCurrent()  // consecutiveFailures is deliberately left alone: it keeps counting across skips
    }

    private fun publishNeighbours() {
        val playing = _nowPlaying.value ?: return
        val index = queue.indexOf(playing.track)
        if (index < 0) return
        _nowPlaying.value = playing.copy(
            previous = queue.getOrNull(index - 1),
            next = queue.getOrNull(index + 1),
        )
    }

    fun release() {
        released = true
        loadToken++
        mainHandler.removeCallbacksAndMessages(null)
        loader.shutdownNow()
    }
}

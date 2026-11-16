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
import java.util.concurrent.TimeUnit

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
 * Gapless: once a song is playing, the one after it is opened in the engine ([NativeAudioEngine.prepareNextFd])
 * so playback runs straight from the end of one into the start of the next. That only happens when
 * [canPrepareNext] allows it (the setting is on and both songs would use the same output stream); the
 * engine refuses on its own if the decoded sample rate or channel count differs. When the engine reports
 * that it has crossed into the prepared song ([checkForAutoAdvance]) the queue position and [nowPlaying]
 * move on without loading anything.
 *
 * One instance is meant to be reused for the app's whole session; call
 * [release] when it's no longer needed.
 */
class PlaybackQueueManager(
    context: Context,
    private val engine: NativeAudioEngine,
    private val trackDao: TrackDao,
    /**
     * Called on the loader thread right before a song is handed to the engine — the place to decide
     * where and how it should leave the app (output device, sample rate, bit-perfect mode).
     */
    private val beforePlay: (Track) -> Unit = {},
    /** Whether [next] may be opened ahead of time to follow [current] without a gap. */
    private val canPrepareNext: (current: Track, next: Track) -> Boolean = { _, _ -> true },
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

    /** Pause was pressed while a song was still loading; applied the moment it starts. */
    private var pauseWhenLoaded: Boolean = false

    /** Where the song being (re)loaded should carry on from; -1 = from the start. */
    @Volatile
    private var resumeAtMs: Long = -1L

    /** Bumped for every load request; only the newest request's result is applied. */
    @Volatile
    private var loadToken: Int = 0

    /** The song the engine last accepted as the gapless follower of the current one. */
    @Volatile
    private var preparedNext: Track? = null

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    private val _queueState = MutableStateFlow(QueueSnapshot())

    /** The queue and the position in it, for the Queue screen. */
    val queueState: StateFlow<QueueSnapshot> = _queueState.asStateFlow()

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
        startQueue(tracks, shuffle, startContentUri?.let { uri -> tracks.indexOfFirst { it.contentUri == uri } } ?: -1)
    }

    /**
     * Plays exactly these songs as the queue — a playlist, say. [startIndex] is the song to begin with
     * (-1 = the first); when shuffling, that song simply leads the queue.
     */
    fun playTracks(tracks: List<Track>, shuffle: Boolean = false, startIndex: Int = -1) {
        if (tracks.isEmpty()) {
            AppMessages.post("There are no songs to play")
            return
        }
        startQueue(tracks, shuffle, startIndex)
    }

    private fun startQueue(tracks: List<Track>, shuffle: Boolean, startIndex: Int) {
        originalOrder = tracks
        shuffled = shuffle
        val start = startIndex.takeIf { it in tracks.indices }
        queue = when {
            !shuffle -> tracks
            start != null -> listOf(tracks[start]) + tracks.filterIndexed { i, _ -> i != start }.shuffled()
            else -> tracks.shuffled()
        }
        currentIndex = if (shuffle || start == null) 0 else start
        consecutiveFailures = 0
        playCurrent()
    }

    // --- Editing the queue ----------------------------------------------------------------

    /** Jumps to the song at [index] of the queue. */
    fun playAt(index: Int) {
        if (index !in queue.indices) return
        currentIndex = index
        consecutiveFailures = 0
        playCurrent()
    }

    /** Puts [track] right after the song that is playing. With nothing playing it just starts. */
    fun enqueueNext(track: Track) = insertIntoQueue(track, currentIndex + 1)

    /** Puts [track] at the very end of the queue. With nothing playing it just starts. */
    fun enqueue(track: Track) = insertIntoQueue(track, queue.size)

    private fun insertIntoQueue(track: Track, at: Int) {
        if (queue.isEmpty() || currentIndex !in queue.indices) {
            playTracks(listOf(track))
            return
        }
        val position = at.coerceIn(0, queue.size)
        val current = queue[currentIndex]
        queue = queue.toMutableList().also { it.add(position, track) }
        val anchor = originalOrder.indexOfFirst { it === current }
        originalOrder = originalOrder.toMutableList().also {
            it.add(if (position <= currentIndex + 1 && anchor >= 0) anchor + 1 else it.size, track)
        }
        if (queueFinished) {
            // The queue had played out and the engine is stopped: what was just added is what to play.
            currentIndex = position
            playCurrent()
            return
        }
        publishQueue()
        if (position == currentIndex + 1) {
            publishNeighbours()
            schedulePrepareNext()  // the song that follows has changed
        }
    }

    /** Takes the song at [index] out of the queue. The one that is playing stays. */
    fun removeFromQueue(index: Int) {
        if (index !in queue.indices || index == currentIndex) return
        val removed = queue[index]
        val nextChanged = index == currentIndex + 1
        queue = queue.toMutableList().also { it.removeAt(index) }
        if (index < currentIndex) currentIndex--
        val inOriginal = originalOrder.indexOfFirst { it === removed }
        if (inOriginal >= 0) originalOrder = originalOrder.toMutableList().also { it.removeAt(inOriginal) }
        publishQueue()
        publishNeighbours()
        if (nextChanged) schedulePrepareNext()
    }

    /** Drops everything after the song that is playing. */
    fun clearUpcoming() {
        if (currentIndex !in queue.indices || currentIndex + 1 >= queue.size) return
        queue = queue.subList(0, currentIndex + 1).toList()
        originalOrder = queue
        publishQueue()
        publishNeighbours()
        schedulePrepareNext()  // nothing follows any more: let go of the prepared song
    }

    private fun publishQueue() {
        _queueState.value = QueueSnapshot(queue, currentIndex)
    }

    /** Re-shuffles (or un-shuffles) the remaining queue in place, keeping
     * whatever's currently playing as the current position. */
    fun setShuffle(enabled: Boolean) {
        if (enabled == shuffled || queue.isEmpty()) return
        val current = queue.getOrNull(currentIndex)
        queue = if (enabled) originalOrder.shuffled() else originalOrder
        shuffled = enabled
        currentIndex = current?.let { queue.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        publishQueue()
        publishNeighbours()
        schedulePrepareNext()  // the song that follows has changed
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
        pauseWhenLoaded = false
        if (queueFinished && queue.getOrNull(currentIndex) != null) {
            consecutiveFailures = 0
            playCurrent()
        } else {
            engine.resumeTrack()
        }
    }

    /**
     * Loads the current song again and carries on from [positionMs] — used when the output device
     * changed (a DAC was plugged in) so the rest of the song goes out through the new route.
     */
    fun reloadCurrent(positionMs: Long, startPaused: Boolean) {
        if (queue.getOrNull(currentIndex) == null) return
        playCurrent(resumeAt = positionMs.coerceAtLeast(0L))
        if (startPaused) pauseWhenLoaded = true
    }

    /** Pauses; if a song is still loading it will start paused. */
    fun pause() {
        if (_isLoading.value) pauseWhenLoaded = true
        engine.pauseTrack()
    }

    /**
     * Jumps to [positionMs] in the current song. If the queue had already played out to its
     * end (the engine stopped itself), seeking brings playback back to life from that point.
     */
    fun seekTo(positionMs: Long) {
        val duration = _nowPlaying.value?.let { it.info.durationMs.takeIf { d -> d > 0L } ?: it.track.durationMs } ?: 0L
        val target = if (duration > 0L) positionMs.coerceIn(0L, (duration - 100L).coerceAtLeast(0L)) else positionMs.coerceAtLeast(0L)
        engine.seekTo(target)
        if (queueFinished && !_isLoading.value) {
            queueFinished = false
            engine.resumeTrack()
        }
        // A seek back into a song whose end was already handed over to the next one drops that
        // hand-over inside the engine. Once it has settled, prepare the next song again if needed.
        mainHandler.postDelayed({
            if (!released && !_isLoading.value && !engine.isNextReady()) schedulePrepareNext()
        }, 600L)
    }

    /** Call periodically (e.g. alongside position polling). Advances the
     * queue exactly once per track that finished on its own. */
    fun checkForAutoAdvance() {
        if (engine.consumeGaplessAdvance()) onGaplessAdvance()
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

    private fun playCurrent(resumeAt: Long = -1L) {
        val index = currentIndex
        val track = queue.getOrNull(index) ?: return
        publishQueue()
        resumeAtMs = resumeAt  // set before the load starts (the loader thread reads it); a later request overrides it
        val token = ++loadToken
        queueFinished = false
        pauseWhenLoaded = false  // a fresh request means "play this"; pause() may set it again afterwards
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
            try {
                beforePlay(track)
            } catch (e: Exception) {
                Log.w(TAG, "Planning the output for ${track.title} failed", e)
            }
            val resume = resumeAtMs
            if (resume > 0L) engine.setNextStartPosition(resume)
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
        resumeAtMs = -1L
        when (outcome) {
            is LoadOutcome.Playing -> {
                consecutiveFailures = 0
                if (pauseWhenLoaded) {
                    pauseWhenLoaded = false
                    engine.pauseTrack()
                }
                // The queue may have been edited while this song was loading, so go by where it is now.
                val at = if (queue.getOrNull(currentIndex)?.contentUri == track.contentUri) currentIndex else index
                _nowPlaying.value = NowPlaying(
                    track = track,
                    previous = queue.getOrNull(at - 1),
                    next = queue.getOrNull(at + 1),
                    info = outcome.info,
                )
                schedulePrepareNext()
            }
            is LoadOutcome.Failed -> {
                pauseWhenLoaded = false
                consecutiveFailures++
                AppMessages.post("Can't play “${track.title}”: ${outcome.reason}")
                if (consecutiveFailures < MAX_CONSECUTIVE_FAILURES) {
                    playNextAfterFailure()
                } else {
                    queueFinished = true  // gave up: Play will try this song again
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

    /**
     * The Gapless setting changed: prepare the next song now, or drop the prepared one. Main thread.
     */
    fun refreshGapless() {
        schedulePrepareNext()
    }

    /**
     * The engine has played through the end of the song into the prepared one: move the queue and
     * the "now playing" state on, exactly as if that song had just been loaded.
     */
    private fun onGaplessAdvance() {
        val track = preparedNext
        preparedNext = null
        if (released || _isLoading.value || track == null) return  // a load in flight decides what plays
        // The prepared song is the one right after the current position (it can appear twice in a queue).
        val index = (currentIndex + 1 until queue.size).firstOrNull { queue[it].contentUri == track.contentUri }
            ?: queue.indexOfFirst { it.contentUri == track.contentUri }
        if (index < 0) return
        currentIndex = index
        consecutiveFailures = 0
        queueFinished = false
        publishQueue()
        _nowPlaying.value = NowPlaying(
            track = queue[index],
            previous = queue.getOrNull(index - 1),
            next = queue.getOrNull(index + 1),
            info = engine.getAudioTrackInfo(),
        )
        schedulePrepareNext()
    }

    /** Opens the song after the current one in the engine, on the loader thread, if that can be gapless. */
    private fun schedulePrepareNext() {
        if (released) return
        val current = queue.getOrNull(currentIndex) ?: return
        val next = queue.getOrNull(currentIndex + 1)
        val token = loadToken
        val allowed = next != null && try {
            canPrepareNext(current, next)
        } catch (e: Exception) {
            Log.w(TAG, "Deciding on gapless playback failed", e)
            false
        }
        loader.execute {
            if (released || token != loadToken) return@execute  // another song was requested meanwhile
            if (next == null || !allowed) {
                engine.cancelNext()  // the engine may still hold the follower of an older queue order
                return@execute
            }
            try {
                val pfd = appContext.contentResolver.openFileDescriptor(next.contentUri.toUri(), "r") ?: return@execute
                if (pfd.use { engine.prepareNextFd(it.fd) }) preparedNext = next
            } catch (e: Exception) {
                Log.w(TAG, "Preparing ${next.title} to follow gaplessly failed", e)
            }
        }
    }

    private fun publishNeighbours() {
        val playing = _nowPlaying.value ?: return
        // While another song is loading, currentIndex already points at it: find the one on screen.
        val index = if (queue.getOrNull(currentIndex)?.contentUri == playing.track.contentUri) {
            currentIndex
        } else {
            queue.indexOfFirst { it.contentUri == playing.track.contentUri }
        }
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
        // The loader may be inside a native call right now; the engine must not be
        // destroyed underneath it.
        try {
            loader.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

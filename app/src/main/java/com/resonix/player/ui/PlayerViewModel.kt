package com.resonix.player.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.resonix.player.data.AppDatabase
import com.resonix.player.data.FolderScanner
import com.resonix.player.data.PlaylistSummary
import com.resonix.player.data.PlaylistTrack
import com.resonix.player.data.MediaStoreScanner
import com.resonix.player.data.ScannedFolder
import com.resonix.player.data.Track
import com.resonix.player.audio.displayFormatName
import com.resonix.player.playback.AudioRouteMonitor
import com.resonix.player.playback.OutputRoute
import com.resonix.player.playback.PlaybackController
import com.resonix.player.playback.PlaybackSnapshot
import com.resonix.player.playback.PlaybackStateBus
import com.resonix.player.playback.QueueSnapshot
import com.resonix.player.playback.SleepTimerBus
import com.resonix.player.playback.SleepTimerState
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PlayerViewModel"

/**
 * Bump this whenever the library scanner learns to read something it used to miss. On the next
 * launch every saved folder is re-scanned once, so songs scanned by an older version (0:00
 * durations, "Unknown artist", no quality badges) are repaired without the user doing anything.
 */
private const val SCANNER_VERSION = 2

data class NowPlayingUiState(
    val hasTrack: Boolean = false,
    /** contentUri of the track that is loaded (the MediaItem id) — lets lists highlight it. */
    val contentUri: String = "",
    val title: String = "",
    val artist: String = "",
    val albumArtUri: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
    val format: String = "",
    val channelCount: Int = 0,
    val bitrateKbps: Int = 0,
    /** Where the song comes from (its folder), for the "Playing from …" line. */
    val source: String = "",
    /** What the output stream really is, e.g. "AAudio · Exclusive · 96 kHz" (empty before the first song). */
    val outputInfo: String = ""
)

/**
 * Talks to PlaybackService in two directions, and never touches
 * NativeAudioEngine / PlaybackQueueManager directly:
 *  - commands (play, pause, next, seek, "play this folder") go out through PlaybackController —
 *    plain calls into the service, which lives in the same process and is started on demand;
 *  - what is playing comes back through PlaybackStateBus.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    /** UI preferences (theme, accent, sort order, Tools values …) — the same store the service reads. */
    val settings = AppSettings.get(application)

    /** Where the music is going (AUX / USB DAC / Bluetooth …) and what that device can do. */
    val outputRoute: StateFlow<OutputRoute> = AudioRouteMonitor.get(application).route

    /** The sleep timer runs inside the service, so it survives the screens going away. */
    val sleepTimer: StateFlow<SleepTimerState?> = SleepTimerBus.state

    /** What is queued, in play order, and which song is playing — for the Queue screen. */
    val queue: StateFlow<QueueSnapshot> = PlaybackStateBus.queue

    private val _playlists = MutableStateFlow<List<PlaylistSummary>>(emptyList())

    /** Every playlist, by name, with how many songs it holds. */
    val playlists: StateFlow<List<PlaylistSummary>> = _playlists.asStateFlow()

    private val _uiState = MutableStateFlow(NowPlayingUiState())
    val uiState: StateFlow<NowPlayingUiState> = _uiState.asStateFlow()

    private val _libraryTracks = MutableStateFlow<List<Track>>(emptyList())
    val libraryTracks: StateFlow<List<Track>> = _libraryTracks.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _scannedFolders = MutableStateFlow<List<ScannedFolder>>(emptyList())
    val scannedFolders: StateFlow<List<ScannedFolder>> = _scannedFolders.asStateFlow()

    /** rootUri of whichever folder is currently being (re)scanned, if any. */
    private val _scanningFolderUri = MutableStateFlow<String?>(null)
    val scanningFolderUri: StateFlow<String?> = _scanningFolderUri.asStateFlow()

    /** Scans never overlap — they would fight over the same Room rows. */
    private val scanMutex = Mutex()

    init {
        val context = getApplication<Application>()
        viewModelScope.launch {
            AppDatabase.getInstance(context).trackDao().getAllTracks().collect {
                _libraryTracks.value = it
            }
        }
        viewModelScope.launch {
            AppDatabase.getInstance(context).scannedFolderDao().getAllFolders().collect {
                _scannedFolders.value = it
            }
        }
        viewModelScope.launch {
            AppDatabase.getInstance(context).playlistDao().observePlaylists().collect {
                _playlists.value = it
            }
        }

        // What is playing, whether it is playing, and where it is — straight from the service.
        viewModelScope.launch {
            PlaybackStateBus.snapshot.collect { applySnapshot(it) }
        }

        rescanLibraryOnceAfterScannerUpgrade()
    }

    private fun applySnapshot(snapshot: PlaybackSnapshot) {
        val playing = snapshot.playing
        if (playing == null) {
            _uiState.value = NowPlayingUiState(isPlaying = snapshot.isPlaying, outputInfo = snapshot.outputInfo)
            return
        }
        val track = playing.track
        val info = playing.info
        val decoderKnows = info.sampleRateHz > 0
        _uiState.value = NowPlayingUiState(
            hasTrack = true,
            contentUri = track.contentUri,
            title = track.title,
            artist = track.artist,
            albumArtUri = track.albumArtUri,
            isPlaying = snapshot.isPlaying,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            sampleRateHz = if (decoderKnows) info.sampleRateHz else track.sampleRateHz,
            bitDepth = if (decoderKnows) info.bitDepth else track.bitDepth,
            format = displayFormatName(info.format.ifBlank { track.format }),
            channelCount = if (decoderKnows) info.channelCount else 0,
            bitrateKbps = ((if (decoderKnows && info.bitrateBps > 0L) info.bitrateBps else track.bitrateBps) / 1000L).toInt(),
            source = track.folderName,
            outputInfo = snapshot.outputInfo,
        )
    }

    fun playPause() = PlaybackController.togglePlayPause()

    fun skipNext() = PlaybackController.next()

    fun skipPrevious() = PlaybackController.previous()

    fun seekTo(positionMs: Long) = PlaybackController.seekTo(positionMs)

    /**
     * Plays every track in [track]'s folder as a queue, starting from this one. Note:
     * MediaStore-sourced tracks don't carry folder info (folderPath is blank for them), so
     * tapping one currently queues all MediaStore-sourced tracks together rather than a
     * specific folder — only folder-scanned tracks get true per-folder queues right now.
     */
    fun playTrackFolder(track: Track, shuffle: Boolean = false) {
        playFolder(track.rootFolderUri, track.folderPath, shuffle, startContentUri = track.contentUri)
    }

    /** Plays every track directly inside [folderPath] of a scanned root
     * (folderPath "" means "the root itself", not "MediaStore" —
     * rootFolderUri is what keeps those apart, see TrackDao). */
    fun playFolder(
        rootFolderUri: String,
        folderPath: String,
        shuffle: Boolean = false,
        startContentUri: String? = null,
    ) {
        // Same order as the list the user is looking at.
        val sort = settings.state.value
        PlaybackController.playFolder(
            context = getApplication(),
            rootFolderUri = rootFolderUri,
            folderPath = folderPath,
            shuffle = shuffle,
            startContentUri = startContentUri,
            sortKey = sort.sortKey.name,
            sortAscending = sort.sortAscending,
        )
    }

    // --- Queue ------------------------------------------------------------------------------

    fun playQueueIndex(index: Int) = PlaybackController.playQueueIndex(index)

    fun removeFromQueue(index: Int) = PlaybackController.removeFromQueue(index)

    fun clearUpcoming() = PlaybackController.clearUpcoming()

    fun playNext(track: Track) {
        PlaybackController.enqueueNext(getApplication(), track)
        AppMessages.post("“${track.title}” plays next")
    }

    fun addToQueue(track: Track) {
        PlaybackController.enqueue(getApplication(), track)
        AppMessages.post("Added “${track.title}” to the queue")
    }

    // --- Playlists --------------------------------------------------------------------------

    private val playlistDao get() = AppDatabase.getInstance(getApplication()).playlistDao()

    /** The songs of one playlist, in order, as long as the screen collects it. */
    fun playlistTracks(playlistId: Long): Flow<List<PlaylistTrack>> = playlistDao.observeTracks(playlistId)

    /**
     * Makes a playlist called [name] (an existing one of that name is reused, whatever its case) and
     * puts [tracks] at its end. Returns through [onDone] with the playlist's id.
     */
    fun createPlaylist(name: String, tracks: List<Track> = emptyList(), onDone: (Long) -> Unit = {}) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                val existing = playlistDao.findIdByName(clean)
                val id = if (existing != null) {
                    playlistDao.appendTracks(existing, tracks.map { it.contentUri })
                    existing
                } else {
                    playlistDao.createWithTracks(clean, System.currentTimeMillis() / 1000L, tracks.map { it.contentUri })
                }
                if (tracks.isNotEmpty()) AppMessages.post(addedMessage(tracks, clean))
                onDone(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Creating playlist failed", e)
                AppMessages.post("Couldn't create the playlist")
            }
        }
    }

    fun addToPlaylist(playlistId: Long, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        viewModelScope.launch {
            try {
                playlistDao.appendTracks(playlistId, tracks.map { it.contentUri })
                AppMessages.post(addedMessage(tracks, playlistDao.getName(playlistId) ?: "the playlist"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Adding to playlist failed", e)
                AppMessages.post("Couldn't add to the playlist")
            }
        }
    }

    private fun addedMessage(tracks: List<Track>, playlistName: String): String =
        if (tracks.size == 1) "Added “${tracks[0].title}” to “$playlistName”" else "Added ${tracks.size} songs to “$playlistName”"

    fun removeFromPlaylist(entryId: Long) {
        viewModelScope.launch {
            try {
                playlistDao.deleteEntry(entryId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Removing from playlist failed", e)
            }
        }
    }

    fun renamePlaylist(playlistId: Long, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            try {
                val other = playlistDao.findIdByName(clean)
                if (other != null && other != playlistId) {
                    AppMessages.post("There is already a playlist called “$clean”")
                } else {
                    playlistDao.rename(playlistId, clean)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Renaming playlist failed", e)
            }
        }
    }

    fun deletePlaylist(playlistId: Long) {
        viewModelScope.launch {
            try {
                playlistDao.deletePlaylist(playlistId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Deleting playlist failed", e)
                AppMessages.post("Couldn't delete the playlist")
            }
        }
    }

    /** Plays a playlist from [startIndex] (-1 = the first song), in order or shuffled. */
    fun playPlaylist(playlistId: Long, shuffle: Boolean, startIndex: Int = -1) {
        viewModelScope.launch {
            try {
                val tracks = playlistDao.getTracks(playlistId)
                if (tracks.isEmpty()) {
                    AppMessages.post("This playlist has no songs yet")
                } else {
                    PlaybackController.playTracks(getApplication(), tracks, shuffle, startIndex)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Playing playlist failed", e)
                AppMessages.post("Couldn't start playback")
            }
        }
    }

    fun scanLibrary() {
        viewModelScope.launch { scanDeviceLibrary(announce = true) }
    }

    /** Call with the Uri from an OpenDocumentTree picker result. Persists
     * access so this folder can be re-scanned later without picking it
     * again, then runs the first scan. */
    fun addFolder(treeUri: Uri) {
        val context = getApplication<Application>()
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            // Scanning still works for this session; it just may not survive a restart.
            Log.w(TAG, "Couldn't persist access to $treeUri", e)
        }
        rescanFolder(treeUri.toString())
    }

    fun rescanFolder(rootUri: String) {
        viewModelScope.launch { scanFolderNow(rootUri) }
    }

    private suspend fun scanFolderNow(rootUri: String) {
        scanMutex.withLock {
            _scanningFolderUri.value = rootUri
            try {
                FolderScanner(getApplication()).scanFolder(Uri.parse(rootUri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scanning $rootUri failed", e)
                AppMessages.post("Couldn't scan that folder")
            } finally {
                _scanningFolderUri.value = null
            }
        }
    }

    private suspend fun scanDeviceLibrary(announce: Boolean) {
        scanMutex.withLock {
            _isScanning.value = true
            try {
                MediaStoreScanner(getApplication()).scan()
                if (announce) AppMessages.post("Device library scanned")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Scanning the device library failed", e)
                AppMessages.post("Couldn't scan the device library")
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** See [SCANNER_VERSION]. */
    private fun rescanLibraryOnceAfterScannerUpgrade() {
        val context = getApplication<Application>()
        val prefs = context.getSharedPreferences("resonix_library", Context.MODE_PRIVATE)
        if (prefs.getInt("scanner_version", 0) >= SCANNER_VERSION) return

        viewModelScope.launch {
            val database = AppDatabase.getInstance(context)
            val folders = database.scannedFolderDao().getAllFolders().first()
            for (folder in folders) {
                scanFolderNow(folder.rootUri)
            }
            val hadDeviceLibrary = database.trackDao().getAllTracks().first().any { it.rootFolderUri.isEmpty() }
            if (hadDeviceLibrary && canReadDeviceAudio(context)) {
                scanDeviceLibrary(announce = false)
            }
            prefs.edit().putInt("scanner_version", SCANNER_VERSION).apply()
        }
    }

    private fun canReadDeviceAudio(context: Context): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Tracks under one scanned root, newest-folder-structure first —
     * what the Repo screen shows when a folder is opened. */
    fun tracksUnderRoot(rootUri: String) =
        AppDatabase.getInstance(getApplication()).trackDao().getTracksUnderRootFlow(rootUri)

    /** Pauses playback after [minutes]; 0 cancels. The timer itself lives in PlaybackService. */
    fun setSleepTimer(minutes: Int) {
        PlaybackController.setSleepTimer(getApplication(), minutes)
    }
}

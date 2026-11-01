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
import com.resonix.player.data.MediaStoreScanner
import com.resonix.player.data.ScannedFolder
import com.resonix.player.data.Track
import com.resonix.player.audio.displayFormatName
import com.resonix.player.playback.AudioRouteMonitor
import com.resonix.player.playback.OutputRoute
import com.resonix.player.playback.PlaybackController
import com.resonix.player.playback.PlaybackSnapshot
import com.resonix.player.playback.PlaybackStateBus
import com.resonix.player.playback.SleepTimerBus
import com.resonix.player.playback.SleepTimerState
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.CancellationException
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

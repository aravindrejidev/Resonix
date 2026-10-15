package com.resonix.player.ui

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.resonix.player.data.AppDatabase
import com.resonix.player.data.FolderScanner
import com.resonix.player.data.MediaStoreScanner
import com.resonix.player.data.ScannedFolder
import com.resonix.player.data.Track
import com.resonix.player.playback.PlaybackService
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    val format: String = ""
)

/** A running sleep timer: playback is paused when [endsAtElapsedMs] (SystemClock.elapsedRealtime) is reached. */
data class SleepTimerState(val totalMinutes: Int, val endsAtElapsedMs: Long)

/**
 * Connects the UI to PlaybackService purely through a Media3
 * MediaController — never touches NativeAudioEngine/PlaybackQueueManager
 * directly, so playback keeps working the same way whether the app is
 * foregrounded or not. Custom hi-res fields (sample rate, bit depth,
 * format) ride through MediaMetadata's extras Bundle — see
 * ResonixPlayer's Track.toMediaItemData() — since the standard Player
 * interface has no such fields of its own.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private var mediaController: MediaController? = null

    /** A "play this folder" request that arrived before the service connection was up. */
    private var pendingPlayRequest: Bundle? = null

    /** UI preferences (theme, accent, sort order, Tools values …). */
    val settings = AppSettings(application)

    private val _sleepTimer = MutableStateFlow<SleepTimerState?>(null)
    val sleepTimer: StateFlow<SleepTimerState?> = _sleepTimer.asStateFlow()
    private var sleepJob: Job? = null

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

    private val playerListener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: androidx.media3.common.MediaMetadata) {
            mediaController?.let { refreshTrack(it) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaController?.let { refreshTrack(it) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            mediaController?.let { refreshPlaying(it) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            mediaController?.let { refreshPlaying(it) }
        }
    }

    init {
        connectToPlaybackService()

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

        // Media3 doesn't push continuous position updates on its own —
        // polling matches how the rest of this app already tracks position.
        viewModelScope.launch {
            while (true) {
                mediaController?.let { controller ->
                    _uiState.value = _uiState.value.copy(
                        positionMs = controller.currentPosition.coerceAtLeast(0),
                        durationMs = controller.duration.coerceAtLeast(0),
                        isPlaying = isControllerPlaying(controller),
                    )
                }
                delay(250)
            }
        }

        rescanLibraryOnceAfterScannerUpgrade()
    }

    private fun connectToPlaybackService() {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                try {
                    val controller = controllerFuture.get()
                    mediaController = controller
                    controller.addListener(playerListener)
                    // The listener only reports *changes* — pick up what is already playing
                    // when the app is reopened while the service kept going in the background.
                    refreshTrack(controller)
                    refreshPlaying(controller)
                    pendingPlayRequest?.let { request ->
                        pendingPlayRequest = null
                        sendPlayRequest(controller, request)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Could not connect to the playback service", e)
                    AppMessages.post("Couldn't connect to the audio service")
                }
            },
            MoreExecutors.directExecutor()
        )
    }

    /** The UI shows "playing" from the moment play is requested, including while a file is still loading. */
    private fun isControllerPlaying(controller: MediaController): Boolean =
        controller.playWhenReady && controller.playbackState != Player.STATE_IDLE

    private fun refreshPlaying(controller: MediaController) {
        _uiState.value = _uiState.value.copy(isPlaying = isControllerPlaying(controller))
    }

    private fun refreshTrack(controller: MediaController) {
        val item = controller.currentMediaItem ?: return
        val metadata = item.mediaMetadata
        val extras = metadata.extras
        _uiState.value = _uiState.value.copy(
            hasTrack = true,
            contentUri = item.mediaId,
            title = metadata.title?.toString() ?: "",
            artist = metadata.artist?.toString() ?: "",
            albumArtUri = extras?.getString("albumArtUri") ?: "",
            sampleRateHz = extras?.getInt("sampleRateHz") ?: 0,
            bitDepth = extras?.getInt("bitDepth") ?: 0,
            format = extras?.getString("format") ?: ""
        )
    }

    fun playPause() {
        val controller = mediaController ?: return
        if (isControllerPlaying(controller)) controller.pause() else controller.play()
    }

    fun skipNext() = mediaController?.seekToNext()

    fun skipPrevious() = mediaController?.seekToPrevious()

    fun seekTo(positionMs: Long) {
        mediaController?.seekTo(positionMs)
    }

    /**
     * Plays every track in [track]'s folder as a queue, starting from
     * this one. The standard Player interface has no "play this folder"
     * concept, so this goes through the PLAY_FOLDER custom session
     * command PlaybackService declares. Note: MediaStore-sourced tracks
     * don't carry folder info (folderPath is blank for them), so tapping
     * one currently queues all MediaStore-sourced tracks together rather
     * than a specific folder — only folder-scanned tracks get true
     * per-folder queues right now.
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
        val request = Bundle().apply {
            putString(PlaybackService.ARG_ROOT_FOLDER_URI, rootFolderUri)
            putString(PlaybackService.ARG_FOLDER_PATH, folderPath)
            putBoolean(PlaybackService.ARG_SHUFFLE, shuffle)
            if (startContentUri != null) putString(PlaybackService.ARG_START_URI, startContentUri)
            // Same order as the list the user is looking at.
            val sort = settings.state.value
            putString(PlaybackService.ARG_SORT_KEY, sort.sortKey.name)
            putBoolean(PlaybackService.ARG_SORT_ASCENDING, sort.sortAscending)
        }
        val controller = mediaController
        if (controller == null) {
            pendingPlayRequest = request  // sent as soon as the service connection is up
            return
        }
        sendPlayRequest(controller, request)
    }

    private fun sendPlayRequest(controller: MediaController, request: Bundle) {
        val result = controller.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_PLAY_FOLDER, Bundle.EMPTY),
            request
        )
        result.addListener(
            {
                try {
                    val code = result.get().resultCode
                    if (code != SessionResult.RESULT_SUCCESS) {
                        Log.w(TAG, "Play command rejected: $code")
                        AppMessages.post("Couldn't start playback (error $code)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Play command failed", e)
                    AppMessages.post("Couldn't start playback")
                }
            },
            MoreExecutors.directExecutor()
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

    /**
     * Pauses playback after [minutes]; 0 cancels. Lives in the ViewModel, so it survives rotation
     * and the app being backgrounded, but not the app being swiped away.
     */
    fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) {
            _sleepTimer.value = null
            return
        }
        val durationMs = minutes * 60_000L
        _sleepTimer.value = SleepTimerState(minutes, SystemClock.elapsedRealtime() + durationMs)
        sleepJob = viewModelScope.launch {
            delay(durationMs)
            mediaController?.pause()
            _sleepTimer.value = null
        }
    }

    override fun onCleared() {
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        super.onCleared()
    }
}

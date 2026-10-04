package com.resonix.player.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.resonix.player.data.AppDatabase
import com.resonix.player.data.FolderScanner
import com.resonix.player.data.MediaStoreScanner
import com.resonix.player.data.ScannedFolder
import com.resonix.player.data.Track
import com.resonix.player.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NowPlayingUiState(
    val hasTrack: Boolean = false,
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

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.value = _uiState.value.copy(isPlaying = isPlaying)
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val extras = mediaMetadata.extras
            _uiState.value = _uiState.value.copy(
                hasTrack = true,
                title = mediaMetadata.title?.toString() ?: "",
                artist = mediaMetadata.artist?.toString() ?: "",
                albumArtUri = extras?.getString("albumArtUri") ?: "",
                sampleRateHz = extras?.getInt("sampleRateHz") ?: 0,
                bitDepth = extras?.getInt("bitDepth") ?: 0,
                format = extras?.getString("format") ?: ""
            )
        }
    }

    init {
        val context = getApplication<Application>()
        val sessionToken = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(context, sessionToken).buildAsync()
        controllerFuture.addListener(
            {
                val controller = controllerFuture.get()
                mediaController = controller
                controller.addListener(playerListener)
            },
            MoreExecutors.directExecutor()
        )

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
                        durationMs = controller.duration.coerceAtLeast(0)
                    )
                }
                delay(250)
            }
        }
    }

    fun playPause() {
        val controller = mediaController ?: return
        if (controller.isPlaying) controller.pause() else controller.play()
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
        playFolder(track.rootFolderUri, track.folderPath, shuffle)
    }

    /** Used by the Repo screen to play every track under a scanned root
     * (folderPath "" there means "the root itself", not "MediaStore" —
     * rootFolderUri is what keeps those apart, see TrackDao). */
    fun playFolder(rootFolderUri: String, folderPath: String, shuffle: Boolean = false) {
        val controller = mediaController ?: return
        val args = Bundle().apply {
            putString(PlaybackService.ARG_ROOT_FOLDER_URI, rootFolderUri)
            putString(PlaybackService.ARG_FOLDER_PATH, folderPath)
            putBoolean(PlaybackService.ARG_SHUFFLE, shuffle)
        }
        controller.sendCustomCommand(
            SessionCommand(PlaybackService.COMMAND_PLAY_FOLDER, Bundle.EMPTY),
            args
        )
    }

    fun scanLibrary() {
        viewModelScope.launch {
            _isScanning.value = true
            MediaStoreScanner(getApplication()).scan()
            _isScanning.value = false
        }
    }

    /** Call with the Uri from an OpenDocumentTree picker result. Persists
     * access so this folder can be re-scanned later without picking it
     * again, then runs the first scan. */
    fun addFolder(treeUri: Uri) {
        val context = getApplication<Application>()
        context.contentResolver.takePersistableUriPermission(
            treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        rescanFolder(treeUri.toString())
    }

    fun rescanFolder(rootUri: String) {
        viewModelScope.launch {
            _scanningFolderUri.value = rootUri
            FolderScanner(getApplication()).scanFolder(Uri.parse(rootUri))
            _scanningFolderUri.value = null
        }
    }

    /** Tracks under one scanned root, newest-folder-structure first —
     * what the Repo screen shows when a folder is opened. */
    fun tracksUnderRoot(rootUri: String) =
        AppDatabase.getInstance(getApplication()).trackDao().getTracksUnderRootFlow(rootUri)

    override fun onCleared() {
        mediaController?.removeListener(playerListener)
        mediaController?.release()
        super.onCleared()
    }
}

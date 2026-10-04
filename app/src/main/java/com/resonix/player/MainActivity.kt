package com.resonix.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.resonix.player.data.ScannedFolder
import com.resonix.player.data.Track
import com.resonix.player.ui.PlayerViewModel
import com.resonix.player.ui.components.FloatingBottomBar
import com.resonix.player.ui.components.MiniPlayer
import com.resonix.player.ui.components.NavTab
import com.resonix.player.ui.screens.FolderDetailScreen
import com.resonix.player.ui.screens.LibraryScreen
import com.resonix.player.ui.screens.NowPlayingScreen
import com.resonix.player.ui.screens.RepoScreen
import com.resonix.player.ui.theme.ResonixBlack
import com.resonix.player.ui.theme.ResonixTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ResonixTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = ResonixBlack) {
                    ResonixApp(viewModel)
                }
            }
        }
    }
}

/**
 * Home currently shows the same content as Library — there's no
 * separate "home" concept (recents, suggestions) built yet; a natural
 * next addition. Floating overlays (mini-player, capsule nav) sit above
 * whichever tab is active; Now Playing slides in as a full-screen
 * overlay on top of everything.
 */
@Composable
private fun ResonixApp(viewModel: PlayerViewModel) {
    var selectedTab by remember { mutableStateOf(NavTab.LIBRARY) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var openFolder by remember { mutableStateOf<ScannedFolder?>(null) }

    val tracks by viewModel.libraryTracks.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val scannedFolders by viewModel.scannedFolders.collectAsState()
    val scanningFolderUri by viewModel.scanningFolderUri.collectAsState()

    fun openTrack(track: Track) {
        viewModel.playTrackFolder(track)
        showNowPlaying = true
    }

    BackHandler(enabled = showNowPlaying) { showNowPlaying = false }
    BackHandler(enabled = !showNowPlaying && openFolder != null) { openFolder = null }

    Box(modifier = Modifier.fillMaxSize()) {
        when (selectedTab) {
            NavTab.HOME, NavTab.LIBRARY -> LibraryScreen(
                tracks = tracks,
                isScanning = isScanning,
                onTrackClick = ::openTrack,
                onScanClick = { viewModel.scanLibrary() },
                modifier = Modifier.fillMaxSize()
            )
            NavTab.REPO -> {
                val currentFolder = openFolder
                if (currentFolder == null) {
                    RepoScreen(
                        scannedFolders = scannedFolders,
                        scanningFolderUri = scanningFolderUri,
                        onAddFolder = { uri -> viewModel.addFolder(uri) },
                        onRescanFolder = { rootUri -> viewModel.rescanFolder(rootUri) },
                        onOpenFolder = { folder -> openFolder = folder },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    val folderTracks by remember(currentFolder.rootUri) {
                        viewModel.tracksUnderRoot(currentFolder.rootUri)
                    }.collectAsState(initial = emptyList())

                    FolderDetailScreen(
                        folder = currentFolder,
                        tracks = folderTracks,
                        onTrackClick = { track ->
                            viewModel.playTrackFolder(track)
                            showNowPlaying = true
                        },
                        onBackClick = { openFolder = null },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (uiState.hasTrack) {
                MiniPlayer(
                    title = uiState.title,
                    artist = uiState.artist,
                    albumArtUri = uiState.albumArtUri,
                    isPlaying = uiState.isPlaying,
                    onPlayPauseClick = { viewModel.playPause() },
                    onNextClick = { viewModel.skipNext() },
                    onClick = { showNowPlaying = true }
                )
            }

            FloatingBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it },
                onSearchClick = { /* Search screen: a natural next addition */ }
            )
        }

        AnimatedVisibility(
            visible = showNowPlaying,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            NowPlayingScreen(
                uiState = uiState,
                onPlayPauseClick = { viewModel.playPause() },
                onNextClick = { viewModel.skipNext() },
                onPreviousClick = { viewModel.skipPrevious() },
                onSeek = { viewModel.seekTo(it) },
                onBackClick = { showNowPlaying = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

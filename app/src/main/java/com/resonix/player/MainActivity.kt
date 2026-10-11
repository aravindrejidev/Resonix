package com.resonix.player

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.resonix.player.data.Track
import com.resonix.player.ui.PlayerViewModel
import com.resonix.player.ui.SettingsState
import com.resonix.player.ui.components.FloatingNavBar
import com.resonix.player.ui.components.MiniPlayer
import com.resonix.player.ui.components.NavTab
import com.resonix.player.ui.neo.neoBackground
import com.resonix.player.ui.screens.HomeScreen
import com.resonix.player.ui.screens.NowPlayingScreen
import com.resonix.player.ui.screens.SearchScreen
import com.resonix.player.ui.screens.SettingsScreen
import com.resonix.player.ui.screens.ToolsScreen
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.ResonixTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by viewModel.settings.state.collectAsState()
            ResonixTheme(
                themeMode = settings.themeMode,
                accent = settings.accent,
                haptics = settings.haptics,
            ) {
                ResonixApp(viewModel = viewModel, settings = settings)
            }
        }
    }
}

/**
 * Three tabs (Home, Tools, Settings) behind a floating neumorphic bar, a mini player above it,
 * and two full-screen overlays: Search and Now Playing.
 */
@Composable
private fun ResonixApp(viewModel: PlayerViewModel, settings: SettingsState) {
    val context = LocalContext.current
    val neo = LocalNeoColors.current

    // Where the user is. rememberSaveable so rotation / process death keep it.
    var tab by rememberSaveable { mutableStateOf(NavTab.HOME) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var homeSource by rememberSaveable { mutableStateOf<String?>(null) }
    var homePath by rememberSaveable { mutableStateOf("") }

    // Scroll positions live here so they survive switching tabs.
    val homeListState = rememberLazyListState()
    val toolsScroll = rememberScrollState()
    val settingsScroll = rememberScrollState()

    val tracks by viewModel.libraryTracks.collectAsState()
    val folders by viewModel.scannedFolders.collectAsState()
    val isScanningDevice by viewModel.isScanning.collectAsState()
    val scanningFolderUri by viewModel.scanningFolderUri.collectAsState()
    val nowPlaying by viewModel.uiState.collectAsState()
    val sleepTimer by viewModel.sleepTimer.collectAsState()

    // ── folder picker + permission plumbing ──
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            viewModel.addFolder(uri)
            homeSource = uri.toString()
            homePath = ""
            tab = NavTab.HOME
        }
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.scanLibrary()
    }
    val scanDevice: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.scanLibrary() else permissionLauncher.launch(audioPermission)
    }

    val playTrack: (Track) -> Unit = { track -> viewModel.playTrackFolder(track) }

    // A restored "player open" flag must not pop the player up later when playback restarts.
    LaunchedEffect(nowPlaying.hasTrack) {
        if (!nowPlaying.hasTrack) showNowPlaying = false
    }

    // One back handler for everything, so the order of precedence is explicit.
    BackHandler(enabled = showNowPlaying || showSearch || tab != NavTab.HOME || homePath.isNotEmpty()) {
        when {
            showNowPlaying -> showNowPlaying = false
            showSearch -> showSearch = false
            tab != NavTab.HOME -> tab = NavTab.HOME
            homePath.isNotEmpty() -> homePath = homePath.substringBeforeLast('/', "")
        }
    }

    // Room the scrolling screens must leave at the bottom for the floating bar (+ mini player).
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset: Dp by animateDpAsState(
        targetValue = navInset + 14.dp + 74.dp + 24.dp + (if (nowPlaying.hasTrack) 78.dp + 12.dp else 0.dp),
        animationSpec = tween(300),
        label = "bottomInset",
    )

    Box(modifier = Modifier.fillMaxSize().neoBackground()) {

        // ── the three tabs ──
        Crossfade(targetState = tab, animationSpec = tween(220), label = "tabs") { current ->
            when (current) {
                NavTab.HOME -> HomeScreen(
                    folders = folders,
                    tracks = tracks,
                    currentUri = nowPlaying.contentUri,
                    isPlaying = nowPlaying.isPlaying,
                    isScanningDevice = isScanningDevice,
                    scanningFolderUri = scanningFolderUri,
                    settings = settings,
                    selectedSource = homeSource,
                    path = homePath,
                    listState = homeListState,
                    bottomInset = bottomInset,
                    onSelectSource = { key ->
                        homeSource = key
                        viewModel.settings.update { it.copy(lastSourceKey = key) }
                    },
                    onPathChange = { homePath = it },
                    onTrackClick = playTrack,
                    onPlayFolder = { root, path, shuffle -> viewModel.playFolder(root, path, shuffle) },
                    onAddFolder = { folderPicker.launch(null) },
                    onScanDevice = scanDevice,
                    onRescanFolder = { viewModel.rescanFolder(it) },
                    onOpenSearch = { showSearch = true },
                    onOpenSettings = { tab = NavTab.SETTINGS },
                    onSortChange = { key, ascending ->
                        viewModel.settings.update { it.copy(sortKey = key, sortAscending = ascending) }
                    },
                    onToggleBadges = { viewModel.settings.update { it.copy(showQualityBadges = !it.showQualityBadges) } },
                    onToggleArt = { viewModel.settings.update { it.copy(showAlbumArt = !it.showAlbumArt) } },
                )

                NavTab.TOOLS -> ToolsScreen(
                    settings = settings,
                    nowPlaying = nowPlaying,
                    sleepTimer = sleepTimer,
                    scrollState = toolsScroll,
                    bottomInset = bottomInset,
                    onSettingsChange = { transform -> viewModel.settings.update(transform) },
                    onSleepTimer = { minutes -> viewModel.setSleepTimer(minutes) },
                )

                NavTab.SETTINGS -> SettingsScreen(
                    settings = settings,
                    folders = folders,
                    scanningFolderUri = scanningFolderUri,
                    isScanningDevice = isScanningDevice,
                    scrollState = settingsScroll,
                    bottomInset = bottomInset,
                    onChange = { transform -> viewModel.settings.update(transform) },
                    onAddFolder = { folderPicker.launch(null) },
                    onScanDevice = scanDevice,
                    onRescanFolder = { viewModel.rescanFolder(it) },
                )
            }
        }

        // ── soft fade behind the floating controls, so content slides *under* them ──
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(bottomInset + 6.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, neo.surface))),
        )

        // ── mini player + floating bar ──
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 22.dp, end = 22.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(
                visible = nowPlaying.hasTrack,
                enter = slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260)),
                exit = slideOutVertically(tween(200)) { it / 2 } + fadeOut(tween(200)),
            ) {
                MiniPlayer(
                    state = nowPlaying,
                    showArt = settings.showAlbumArt,
                    onPlayPause = { viewModel.playPause() },
                    onNext = { viewModel.skipNext() },
                    onOpen = { showNowPlaying = true },
                )
            }
            FloatingNavBar(selected = tab, onSelect = { tab = it })
        }

        // ── full-screen Search ──
        AnimatedVisibility(
            visible = showSearch,
            enter = fadeIn(tween(200)) + slideInVertically(tween(260)) { it / 12 },
            exit = fadeOut(tween(160)) + slideOutVertically(tween(200)) { it / 12 },
        ) {
            SearchScreen(
                tracks = tracks,
                currentUri = nowPlaying.contentUri,
                isPlaying = nowPlaying.isPlaying,
                showBadges = settings.showQualityBadges,
                showArt = settings.showAlbumArt,
                onTrackClick = { track ->
                    playTrack(track)
                    showSearch = false
                },
                onClose = { showSearch = false },
            )
        }

        // ── full-screen Now Playing ──
        AnimatedVisibility(
            visible = showNowPlaying && nowPlaying.hasTrack,
            enter = slideInVertically(tween(320)) { it } + fadeIn(tween(220)),
            exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(220)),
        ) {
            NowPlayingScreen(
                state = nowPlaying,
                keepScreenOn = settings.keepScreenOn,
                onPlayPause = { viewModel.playPause() },
                onNext = { viewModel.skipNext() },
                onPrevious = { viewModel.skipPrevious() },
                onSeek = { viewModel.seekTo(it) },
                onClose = { showNowPlaying = false },
                onOpenTools = {
                    showNowPlaying = false
                    tab = NavTab.TOOLS
                },
            )
        }
    }
}

package com.resonix.player

import android.Manifest
import android.content.Context
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
import com.resonix.player.data.PlaylistTrack
import com.resonix.player.data.Track
import com.resonix.player.ui.PlayerViewModel
import com.resonix.player.ui.SettingsState
import com.resonix.player.ui.components.FloatingNavBar
import com.resonix.player.ui.components.MiniPlayer
import com.resonix.player.ui.components.NavTab
import com.resonix.player.ui.components.NeoToast
import com.resonix.player.ui.components.TrackActionsSheet
import com.resonix.player.util.AppMessages
import kotlinx.coroutines.delay
import com.resonix.player.ui.neo.neoBackground
import com.resonix.player.ui.screens.HomeScreen
import com.resonix.player.ui.screens.NowPlayingScreen
import com.resonix.player.ui.screens.PlaylistsScreen
import com.resonix.player.ui.screens.SearchScreen
import com.resonix.player.ui.screens.SettingsPage
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
 * the Playlists section that opens over them (under the mini player and the bar), two full-screen
 * overlays — Search and Now Playing — and the actions sheet of a song.
 */
@Composable
private fun ResonixApp(viewModel: PlayerViewModel, settings: SettingsState) {
    val context = LocalContext.current
    val neo = LocalNeoColors.current

    // Where the user is. rememberSaveable so rotation / process death keep it.
    var tab by rememberSaveable { mutableStateOf(NavTab.HOME) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showSearch by rememberSaveable { mutableStateOf(false) }
    var showPlaylists by rememberSaveable { mutableStateOf(false) }
    var openPlaylistId by rememberSaveable { mutableStateOf<Long?>(null) }
    // The song whose actions sheet (play next, add to queue / a playlist) is open.
    var menuTrack by remember { mutableStateOf<Track?>(null) }
    var homeSource by rememberSaveable { mutableStateOf<String?>(null) }
    var homePath by rememberSaveable { mutableStateOf("") }
    // Which section of Settings is open (null = the list of sections).
    var settingsPage by rememberSaveable { mutableStateOf<SettingsPage?>(null) }

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
    val outputRoute by viewModel.outputRoute.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val openPlaylistTracks by remember(openPlaylistId) {
        viewModel.playlistTracks(openPlaylistId ?: -1L)
    }.collectAsState(initial = emptyList<PlaylistTrack>())

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

    // Android 13+: ask once for permission to show the playback notification (lock-screen /
    // headset controls). Playback works whether or not it is granted; if it was refused,
    // Settings has a row that opens the system page for it.
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            val prefs = context.getSharedPreferences("resonix_library", Context.MODE_PRIVATE)
            if (!granted && !prefs.getBoolean("asked_notifications", false)) {
                prefs.edit().putBoolean("asked_notifications", true).apply()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val playTrack: (Track) -> Unit = { track -> viewModel.playTrackFolder(track) }

    // Short messages from the playback service and the scanners ("Can't play …", "7 songs found …").
    var toast by remember { mutableStateOf<ToastMessage?>(null) }
    var lastToastText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        AppMessages.messages.collect { text ->
            lastToastText = text
            toast = ToastMessage(text)
        }
    }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(4500)
            toast = null
        }
    }

    // A restored "player open" flag must not pop the player up later when playback restarts.
    LaunchedEffect(nowPlaying.hasTrack) {
        if (!nowPlaying.hasTrack) showNowPlaying = false
    }

    // One back handler for everything, so the order of precedence is explicit.
    BackHandler(
        enabled = menuTrack != null || showNowPlaying || showSearch || showPlaylists ||
            tab != NavTab.HOME || homePath.isNotEmpty()
    ) {
        when {
            menuTrack != null -> menuTrack = null
            showNowPlaying -> showNowPlaying = false
            showSearch -> showSearch = false
            showPlaylists -> if (openPlaylistId != null) openPlaylistId = null else showPlaylists = false
            tab == NavTab.SETTINGS && settingsPage != null -> settingsPage = null
            tab != NavTab.HOME -> tab = NavTab.HOME
            homePath.isNotEmpty() -> homePath = homePath.substringBeforeLast('/', "")
        }
    }

    // Room the scrolling screens must leave at the bottom for the floating bar (+ mini player).
    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset: Dp by animateDpAsState(
        targetValue = navInset + 14.dp + 68.dp + 24.dp + (if (nowPlaying.hasTrack) 72.dp + 10.dp else 0.dp),
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
                    onOpenPlaylists = { showPlaylists = true },
                    onTrackMenu = { menuTrack = it },
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
                    outputRoute = outputRoute,
                    page = settingsPage,
                    onPageChange = { settingsPage = it },
                    onChange = { transform -> viewModel.settings.update(transform) },
                    onAddFolder = { folderPicker.launch(null) },
                    onScanDevice = scanDevice,
                    onRescanFolder = { viewModel.rescanFolder(it) },
                )
            }
        }

        // ── Playlists: a section over the tabs, under the mini player and the bar ──
        AnimatedVisibility(
            visible = showPlaylists,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(160)),
        ) {
            PlaylistsScreen(
                playlists = playlists,
                openPlaylistId = openPlaylistId,
                openTracks = openPlaylistTracks,
                currentUri = nowPlaying.contentUri,
                isPlaying = nowPlaying.isPlaying,
                showBadges = settings.showQualityBadges,
                showArt = settings.showAlbumArt,
                bottomInset = bottomInset,
                onOpenPlaylist = { openPlaylistId = it },
                onCreate = { name -> viewModel.createPlaylist(name) { id -> openPlaylistId = id } },
                onRename = { id, name -> viewModel.renamePlaylist(id, name) },
                onDelete = { id -> viewModel.deletePlaylist(id) },
                onPlay = { id, shuffle, start -> viewModel.playPlaylist(id, shuffle, start) },
                onRemoveEntry = { entryId -> viewModel.removeFromPlaylist(entryId) },
                onTrackMenu = { menuTrack = it },
                onClose = {
                    openPlaylistId = null
                    showPlaylists = false
                },
            )
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
                .padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AnimatedVisibility(
                visible = toast != null,
                enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)),
                exit = slideOutVertically(tween(180)) { it / 2 } + fadeOut(tween(180)),
            ) {
                NeoToast(text = lastToastText, onDismiss = { toast = null })
            }
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
            FloatingNavBar(
                selected = tab,
                onSelect = { selected ->
                    // Tapping Settings again steps back out to the list of sections.
                    if (selected == tab && selected == NavTab.SETTINGS) settingsPage = null
                    showPlaylists = false
                    tab = selected
                },
            )
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
                onTrackMenu = { menuTrack = it },
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
                fullFrameArt = settings.fullFrameArt,
                keepScreenOn = settings.keepScreenOn,
                showVolume = settings.directVolume,
                volume = settings.directVolumeLevel,
                onVolumeChange = { level -> viewModel.settings.update { it.copy(directVolumeLevel = level) } },
                onToggleFullFrameArt = { viewModel.settings.update { it.copy(fullFrameArt = !it.fullFrameArt) } },
                onToggleKeepScreenOn = { viewModel.settings.update { it.copy(keepScreenOn = !it.keepScreenOn) } },
                onPlayPause = { viewModel.playPause() },
                onNext = { viewModel.skipNext() },
                onPrevious = { viewModel.skipPrevious() },
                onSeek = { viewModel.seekTo(it) },
                onClose = { showNowPlaying = false },
                onOpenTools = {
                    showNowPlaying = false
                    showPlaylists = false
                    tab = NavTab.TOOLS
                },
                queue = queue,
                onQueueJump = { index -> viewModel.playQueueIndex(index) },
                onQueueRemove = { index -> viewModel.removeFromQueue(index) },
                onQueueClear = { viewModel.clearUpcoming() },
                onTrackMenu = { menuTrack = it },
            )
        }

        // ── what can be done with one song (press and hold) — on top of everything ──
        TrackActionsSheet(
            track = menuTrack,
            playlists = playlists,
            onDismiss = { menuTrack = null },
            onPlayNext = { track -> viewModel.playNext(track) },
            onAddToQueue = { track -> viewModel.addToQueue(track) },
            onAddToPlaylist = { id, track -> viewModel.addToPlaylist(id, listOf(track)) },
            onCreatePlaylist = { name, track -> viewModel.createPlaylist(name, listOf(track)) },
        )
    }
}

/** One toast; a fresh instance per message so repeating the same text still restarts the timer. */
private class ToastMessage(val text: String)

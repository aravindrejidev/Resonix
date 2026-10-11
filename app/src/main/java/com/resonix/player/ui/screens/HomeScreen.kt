package com.resonix.player.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resonix.player.data.ScannedFolder
import com.resonix.player.data.Track
import com.resonix.player.ui.SettingsState
import com.resonix.player.ui.SortKey
import com.resonix.player.ui.components.FolderRow
import com.resonix.player.ui.components.HomeMenu
import com.resonix.player.ui.components.TrackRow
import com.resonix.player.ui.neo.NeoCard
import com.resonix.player.ui.neo.NeoChip
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoLoader
import com.resonix.player.ui.neo.NeoPillButton
import com.resonix.player.ui.neo.NeoSectionLabel
import com.resonix.player.ui.neo.bleedHorizontally
import com.resonix.player.ui.neo.formatTotalTime
import com.resonix.player.ui.neo.neoInset
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

private data class HomeSource(val key: String, val label: String)

private data class FolderEntry(val name: String, val path: String, val songCount: Int)

private class BrowseResult(
    val folders: List<FolderEntry>,
    val songs: List<Track>,
    val totalSongs: Int,
    val totalDurationMs: Long,
)

/** Splits one source's tracks into "sub-folders of [path]" and "songs directly in [path]". */
private fun browse(tracks: List<Track>, path: String): BrowseResult {
    val prefix = if (path.isEmpty()) "" else "$path/"
    val counts = LinkedHashMap<String, Int>()
    val here = ArrayList<Track>()
    var total = 0
    var duration = 0L
    for (t in tracks) {
        if (t.folderPath == path) {
            here.add(t)
            total++
            duration += t.durationMs
        } else if (prefix.isEmpty() || t.folderPath.startsWith(prefix)) {
            val name = t.folderPath.substring(prefix.length).substringBefore('/')
            if (name.isNotEmpty()) {
                counts[name] = (counts[name] ?: 0) + 1
                total++
                duration += t.durationMs
            }
        }
    }
    val folders = counts.entries
        .map { FolderEntry(it.key, if (path.isEmpty()) it.key else "$path/${it.key}", it.value) }
        .sortedBy { it.name.lowercase() }
    return BrowseResult(folders, here, total, duration)
}

private fun sortTracks(list: List<Track>, key: SortKey, ascending: Boolean): List<Track> {
    val comparator: Comparator<Track> = when (key) {
        SortKey.TITLE -> compareBy { it.title.lowercase() }
        SortKey.ARTIST -> compareBy<Track> { it.artist.lowercase() }.thenBy { it.title.lowercase() }
        SortKey.DATE_ADDED -> compareBy { it.dateAddedSec }
        SortKey.DURATION -> compareBy { it.durationMs }
    }
    return if (ascending) list.sortedWith(comparator) else list.sortedWith(comparator.reversed())
}

/**
 * Home: the songs of your main folder, browsable folder by folder.
 *
 * [selectedSource] / [path] are hoisted into the caller so the position survives switching tabs.
 */
@Composable
fun HomeScreen(
    folders: List<ScannedFolder>,
    tracks: List<Track>,
    currentUri: String,
    isPlaying: Boolean,
    isScanningDevice: Boolean,
    scanningFolderUri: String?,
    settings: SettingsState,
    selectedSource: String?,
    path: String,
    listState: LazyListState,
    bottomInset: Dp,
    onSelectSource: (String) -> Unit,
    onPathChange: (String) -> Unit,
    onTrackClick: (Track) -> Unit,
    onPlayFolder: (rootUri: String, path: String, shuffle: Boolean) -> Unit,
    onAddFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onRescanFolder: (rootUri: String) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onSortChange: (SortKey, Boolean) -> Unit,
    onToggleBadges: () -> Unit,
    onToggleArt: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    var menuOpen by remember { mutableStateOf(false) }

    val sources = remember(folders, tracks) {
        buildList {
            folders.forEach { add(HomeSource(it.rootUri, it.displayName.ifBlank { "Music" })) }
            if (tracks.any { it.rootFolderUri.isEmpty() }) add(HomeSource("", "Device library"))
        }
    }
    val wantedKey = selectedSource ?: settings.lastSourceKey
    val active = sources.firstOrNull { it.key == wantedKey } ?: sources.firstOrNull()
    val activeKey = active?.key

    val sourceTracks = remember(tracks, activeKey) {
        if (activeKey == null) emptyList() else tracks.filter { it.rootFolderUri == activeKey }
    }
    val result = remember(sourceTracks, path) { browse(sourceTracks, path) }
    val songs = remember(result, settings.sortKey, settings.sortAscending) {
        sortTracks(result.songs, settings.sortKey, settings.sortAscending)
    }

    // Jump back to the top when the folder changes — but not when merely returning to this tab.
    var lastLocation by remember { mutableStateOf(path to activeKey) }
    LaunchedEffect(path, activeKey) {
        if (lastLocation != (path to activeKey)) {
            listState.scrollToItem(0)
            lastLocation = path to activeKey
        }
    }

    val rootLabel = active?.label ?: "Resonix"
    val title = if (path.isEmpty()) rootLabel else path.substringAfterLast('/')
    val busy = isScanningDevice || scanningFolderUri != null
    val subtitle = when {
        active == null -> "Your music, folder by folder"
        result.totalSongs == 0 -> "No songs yet"
        else -> {
            val count = if (result.totalSongs == 1) "1 song" else "${result.totalSongs} songs"
            "$count · ${formatTotalTime(result.totalDurationMs)}"
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomeHeader(
                overline = if (path.isEmpty()) "RESONIX" else rootLabel.uppercase(),
                title = title,
                subtitle = subtitle,
                canGoUp = path.isNotEmpty(),
                onUp = { onPathChange(path.substringBeforeLast('/', "")) },
                onSearch = onOpenSearch,
                onMore = { menuOpen = !menuOpen },
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (sources.isEmpty()) {
                    EmptyLibrary(
                        busy = busy,
                        bottomInset = bottomInset,
                        onAddFolder = onAddFolder,
                        onScanDevice = onScanDevice,
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomInset),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (busy) {
                            item(key = "busy") { ScanBanner() }
                        }
                        if (sources.size > 1) {
                            item(key = "sources") {
                                LazyRow(
                                    modifier = Modifier.bleedHorizontally(20.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
                                ) {
                                    items(sources, key = { it.key }) { source ->
                                        NeoChip(
                                            text = source.label,
                                            selected = source.key == activeKey,
                                            icon = if (source.key.isEmpty()) NeoIcons.Phone else NeoIcons.Folder,
                                            onClick = {
                                                if (source.key != activeKey) {
                                                    onSelectSource(source.key)
                                                    onPathChange("")
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        item(key = "summary") {
                            val breadcrumb = if (path.isEmpty()) rootLabel else "$rootLabel › ${path.replace("/", " › ")}"
                            FolderSummaryCard(
                                breadcrumb = breadcrumb,
                                songCount = result.totalSongs,
                                folderCount = result.folders.size,
                                canPlay = songs.isNotEmpty(),
                                onPlay = { onPlayFolder(activeKey ?: "", path, false) },
                                onShuffle = { onPlayFolder(activeKey ?: "", path, true) },
                            )
                        }
                        if (result.folders.isNotEmpty()) {
                            item(key = "folders-label") { NeoSectionLabel("Folders") }
                            items(result.folders, key = { "folder:" + it.path }) { entry ->
                                FolderRow(
                                    name = entry.name,
                                    songCount = entry.songCount,
                                    onClick = { onPathChange(entry.path) },
                                )
                            }
                        }
                        if (songs.isNotEmpty()) {
                            item(key = "songs-label") { NeoSectionLabel("Songs") }
                            items(songs, key = { it.contentUri }) { track ->
                                TrackRow(
                                    track = track,
                                    isCurrent = track.contentUri == currentUri,
                                    isPlaying = isPlaying,
                                    showBadge = settings.showQualityBadges,
                                    showArt = settings.showAlbumArt,
                                    onClick = { onTrackClick(track) },
                                )
                            }
                        }
                        if (songs.isEmpty() && result.folders.isEmpty() && !busy) {
                            item(key = "empty") {
                                Text(
                                    text = "Nothing playable in this folder.",
                                    color = neo.textTertiary,
                                    style = NeoType.bodyRegular,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                )
                            }
                        }
                    }
                    // Soft fade so rows slide under the header instead of being cut off.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(14.dp)
                            .background(Brush.verticalGradient(listOf(neo.surface, Color.Transparent))),
                    )
                }
            }
        }

        HomeMenu(
            visible = menuOpen,
            onDismiss = { menuOpen = false },
            canRescan = active != null && !active.key.isEmpty(),
            sortKey = settings.sortKey,
            sortAscending = settings.sortAscending,
            showBadges = settings.showQualityBadges,
            showArt = settings.showAlbumArt,
            onAddFolder = onAddFolder,
            onScanDevice = onScanDevice,
            onRescan = { if (activeKey != null) onRescanFolder(activeKey) },
            onSortKey = { onSortChange(it, settings.sortAscending) },
            onToggleDirection = { onSortChange(settings.sortKey, !settings.sortAscending) },
            onToggleBadges = onToggleBadges,
            onToggleArt = onToggleArt,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun HomeHeader(
    overline: String,
    title: String,
    subtitle: String,
    canGoUp: Boolean,
    onUp: () -> Unit,
    onSearch: () -> Unit,
    onMore: () -> Unit,
) {
    val neo = LocalNeoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = canGoUp) {
            NeoIconButton(
                icon = NeoIcons.Back,
                onClick = onUp,
                contentDescription = "Up one folder",
                modifier = Modifier.padding(end = 14.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = overline,
                color = neo.accent,
                style = NeoType.overline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title,
                color = neo.textPrimary,
                style = NeoType.display,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = neo.textSecondary,
                style = NeoType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        NeoIconButton(icon = NeoIcons.Search, onClick = onSearch, contentDescription = "Search")
        Spacer(Modifier.width(12.dp))
        NeoIconButton(icon = NeoIcons.More, onClick = onMore, contentDescription = "More options")
    }
}

@Composable
private fun FolderSummaryCard(
    breadcrumb: String,
    songCount: Int,
    folderCount: Int,
    canPlay: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
) {
    val neo = LocalNeoColors.current
    val stats = buildString {
        append(if (songCount == 1) "1 song" else "$songCount songs")
        if (folderCount > 0) append(" · ").append(if (folderCount == 1) "1 folder" else "$folderCount folders")
    }
    NeoCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 30.dp,
        elevation = 9.dp,
        contentPadding = PaddingValues(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconBadge(icon = NeoIcons.FolderOpen, diameter = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = breadcrumb,
                    color = neo.textPrimary,
                    style = NeoType.heading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = stats, color = neo.textSecondary, style = NeoType.caption, maxLines = 1)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NeoPillButton(
                text = "Play",
                onClick = onPlay,
                modifier = Modifier.weight(1f),
                icon = NeoIcons.Play,
                accent = true,
                enabled = canPlay,
            )
            NeoPillButton(
                text = "Shuffle",
                onClick = onShuffle,
                modifier = Modifier.weight(1f),
                icon = NeoIcons.Shuffle,
                enabled = canPlay,
            )
        }
    }
}

@Composable
private fun ScanBanner() {
    val neo = LocalNeoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .neoInset(cornerRadius = 22.dp, elevation = 4.dp)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoLoader(diameter = 24.dp)
        Spacer(Modifier.width(14.dp))
        Text(text = "Scanning your music…", color = neo.textSecondary, style = NeoType.body)
    }
}

@Composable
private fun EmptyLibrary(
    busy: Boolean,
    bottomInset: Dp,
    onAddFolder: () -> Unit,
    onScanDevice: () -> Unit,
) {
    val neo = LocalNeoColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 36.dp, end = 36.dp, bottom = bottomInset),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .padding(8.dp)
                .width(132.dp)
                .height(132.dp)
                .neoInset(cornerRadius = 66.dp, elevation = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                NeoLoader(diameter = 48.dp)
            } else {
                NeoIconBadge(icon = NeoIcons.FolderOpen, diameter = 84.dp)
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = if (busy) "Scanning your music…" else "Pick your music folder",
            color = neo.textPrimary,
            style = NeoType.title,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (busy) {
                "Songs will appear here as soon as they are found."
            } else {
                "Resonix plays your files straight from the folders you choose — no library to rebuild."
            },
            color = neo.textSecondary,
            style = NeoType.bodyRegular,
            textAlign = TextAlign.Center,
        )
        if (!busy) {
            Spacer(Modifier.height(28.dp))
            NeoPillButton(
                text = "Add music folder",
                onClick = onAddFolder,
                icon = NeoIcons.FolderAdd,
                accent = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            NeoPillButton(
                text = "Scan device library",
                onClick = onScanDevice,
                icon = NeoIcons.Phone,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

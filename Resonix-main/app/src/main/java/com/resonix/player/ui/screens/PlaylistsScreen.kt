package com.resonix.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resonix.player.data.PlaylistSummary
import com.resonix.player.data.PlaylistTrack
import com.resonix.player.data.Track
import com.resonix.player.ui.components.ConfirmDialog
import com.resonix.player.ui.components.NameDialog
import com.resonix.player.ui.components.TrackRow
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoPillButton
import com.resonix.player.ui.neo.formatTotalTime
import com.resonix.player.ui.neo.neoBackground
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/**
 * Playlists: the list of them, and inside one the songs it holds. Opens over the Home / Tools /
 * Settings content but under the mini player and the navigation bar, like a section of the app.
 *
 * [openPlaylistId] / [onOpenPlaylist] are hoisted into the caller, so the screen stays where it was
 * through a rotation and Back can step out of one playlist to the list.
 */
@Composable
fun PlaylistsScreen(
    playlists: List<PlaylistSummary>,
    openPlaylistId: Long?,
    openTracks: List<PlaylistTrack>,
    currentUri: String,
    isPlaying: Boolean,
    showBadges: Boolean,
    showArt: Boolean,
    bottomInset: Dp,
    onOpenPlaylist: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (playlistId: Long, name: String) -> Unit,
    onDelete: (playlistId: Long) -> Unit,
    onPlay: (playlistId: Long, shuffle: Boolean, startIndex: Int) -> Unit,
    onRemoveEntry: (entryId: Long) -> Unit,
    onTrackMenu: (Track) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = playlists.firstOrNull { it.id == openPlaylistId }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    // The playlist that was open is gone (deleted): back to the list.
    LaunchedEffect(openPlaylistId, open == null) {
        if (openPlaylistId != null && open == null) onOpenPlaylist(null)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .neoBackground()
            // Swallow touches so nothing underneath this screen reacts to them.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (open == null) {
                ScreenHeader(
                    overline = "RESONIX",
                    title = "Playlists",
                    subtitle = when (playlists.size) {
                        0 -> "Songs you gather together"
                        1 -> "1 playlist"
                        else -> "${playlists.size} playlists"
                    },
                    backDescription = "Close playlists",
                    onBack = onClose,
                ) {
                    NeoIconButton(
                        icon = NeoIcons.Add,
                        onClick = { creating = true },
                        contentDescription = "New playlist",
                        diameter = 44.dp,
                    )
                }
                PlaylistList(
                    playlists = playlists,
                    bottomInset = bottomInset,
                    onOpen = { onOpenPlaylist(it) },
                    onCreate = { creating = true },
                )
            } else {
                val count = if (open.trackCount == 1) "1 song" else "${open.trackCount} songs"
                ScreenHeader(
                    overline = "PLAYLIST",
                    title = open.name,
                    subtitle = if (open.trackCount == 0) "No songs yet" else "$count · ${formatTotalTime(open.totalDurationMs)}",
                    backDescription = "Back to playlists",
                    onBack = { onOpenPlaylist(null) },
                ) {
                    NeoIconButton(
                        icon = NeoIcons.Edit,
                        onClick = { renaming = true },
                        contentDescription = "Rename playlist",
                        diameter = 44.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    NeoIconButton(
                        icon = NeoIcons.Delete,
                        onClick = { confirmingDelete = true },
                        contentDescription = "Delete playlist",
                        diameter = 44.dp,
                    )
                }
                PlaylistDetail(
                    tracks = openTracks,
                    currentUri = currentUri,
                    isPlaying = isPlaying,
                    showBadges = showBadges,
                    showArt = showArt,
                    bottomInset = bottomInset,
                    onPlay = { shuffle -> onPlay(open.id, shuffle, -1) },
                    onPlayFrom = { index -> onPlay(open.id, false, index) },
                    onRemoveEntry = onRemoveEntry,
                    onTrackMenu = onTrackMenu,
                )
            }
        }

        NameDialog(
            visible = creating,
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onConfirm = { name ->
                creating = false
                onCreate(name)
            },
            onDismiss = { creating = false },
        )
        NameDialog(
            visible = renaming && open != null,
            title = "Rename playlist",
            initial = open?.name ?: "",
            confirmLabel = "Save",
            onConfirm = { name ->
                renaming = false
                if (open != null) onRename(open.id, name)
            },
            onDismiss = { renaming = false },
        )
        ConfirmDialog(
            visible = confirmingDelete && open != null,
            title = "Delete “${open?.name ?: ""}”?",
            message = "Only the playlist is removed. The songs stay in your library.",
            confirmLabel = "Delete",
            onConfirm = {
                confirmingDelete = false
                if (open != null) {
                    onOpenPlaylist(null)
                    onDelete(open.id)
                }
            },
            onDismiss = { confirmingDelete = false },
        )
    }
}

/** Back button, a three-line title like Home's, and [actions] on the right. */
@Composable
private fun ScreenHeader(
    overline: String,
    title: String,
    subtitle: String,
    backDescription: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    val neo = LocalNeoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconButton(
            icon = NeoIcons.Back,
            onClick = onBack,
            contentDescription = backDescription,
            modifier = Modifier.padding(end = 14.dp),
        )
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
                style = NeoType.title,
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
        actions()
    }
}

@Composable
private fun PlaylistList(
    playlists: List<PlaylistSummary>,
    bottomInset: Dp,
    onOpen: (Long) -> Unit,
    onCreate: () -> Unit,
) {
    val neo = LocalNeoColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (playlists.isEmpty()) {
            item(key = "empty") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp, vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    NeoIconBadge(icon = NeoIcons.Playlists, diameter = 84.dp)
                    Spacer(Modifier.height(24.dp))
                    Text(text = "No playlists yet", color = neo.textPrimary, style = NeoType.title, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Make one here, or press and hold any song and choose Add to playlist.",
                        color = neo.textSecondary,
                        style = NeoType.bodyRegular,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(24.dp))
                    NeoPillButton(
                        text = "New playlist",
                        onClick = onCreate,
                        icon = NeoIcons.Add,
                        accent = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            items(playlists, key = { it.id }) { playlist ->
                PlaylistRow(playlist = playlist, onClick = { onOpen(playlist.id) })
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: PlaylistSummary, onClick: () -> Unit) {
    val neo = LocalNeoColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val rowColor by animateColorAsState(
        targetValue = if (pressed) neo.textPrimary.copy(alpha = 0.06f) else neo.textPrimary.copy(alpha = 0f),
        label = "playlistRowColor",
    )
    val subtitle = if (playlist.trackCount == 0) {
        "No songs yet"
    } else {
        val count = if (playlist.trackCount == 1) "1 song" else "${playlist.trackCount} songs"
        "$count · ${formatTotalTime(playlist.totalDurationMs)}"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowColor, RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(icon = NeoIcons.Playlists, diameter = 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                color = neo.textPrimary,
                style = NeoType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = subtitle, color = neo.textSecondary, style = NeoType.caption, maxLines = 1)
        }
        Icon(
            imageVector = NeoIcons.Chevron,
            contentDescription = null,
            tint = neo.textTertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun PlaylistDetail(
    tracks: List<PlaylistTrack>,
    currentUri: String,
    isPlaying: Boolean,
    showBadges: Boolean,
    showArt: Boolean,
    bottomInset: Dp,
    onPlay: (shuffle: Boolean) -> Unit,
    onPlayFrom: (index: Int) -> Unit,
    onRemoveEntry: (entryId: Long) -> Unit,
    onTrackMenu: (Track) -> Unit,
) {
    val neo = LocalNeoColors.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item(key = "play") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                NeoPillButton(
                    text = "Play",
                    onClick = { onPlay(false) },
                    modifier = Modifier.weight(1f),
                    icon = NeoIcons.Play,
                    accent = true,
                    enabled = tracks.isNotEmpty(),
                )
                NeoPillButton(
                    text = "Shuffle",
                    onClick = { onPlay(true) },
                    modifier = Modifier.weight(1f),
                    icon = NeoIcons.Shuffle,
                    enabled = tracks.isNotEmpty(),
                )
            }
        }
        if (tracks.isEmpty()) {
            item(key = "empty") {
                Text(
                    text = "No songs yet. Press and hold a song anywhere and choose Add to playlist.",
                    color = neo.textTertiary,
                    style = NeoType.bodyRegular,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 26.dp, vertical = 32.dp),
                )
            }
        } else {
            itemsIndexed(tracks, key = { _, entry -> entry.entryId }) { index, entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrackRow(
                        track = entry.track,
                        isCurrent = entry.track.contentUri == currentUri,
                        isPlaying = isPlaying,
                        showBadge = showBadges,
                        showArt = showArt,
                        onClick = { onPlayFrom(index) },
                        modifier = Modifier.weight(1f),
                        onLongClick = { onTrackMenu(entry.track) },
                    )
                    NeoIconButton(
                        icon = NeoIcons.Close,
                        onClick = { onRemoveEntry(entry.entryId) },
                        diameter = 38.dp,
                        iconSize = 18.dp,
                        contentDescription = "Remove from playlist",
                    )
                }
            }
        }
    }
}

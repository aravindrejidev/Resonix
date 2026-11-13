package com.resonix.player.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.data.PlaylistSummary
import com.resonix.player.data.Track
import com.resonix.player.ui.neo.NeoAlbumArt
import com.resonix.player.ui.neo.NeoDivider
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoPillButton
import com.resonix.player.ui.neo.neoFloating
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

private enum class SheetMode { ACTIONS, PICK_PLAYLIST, NEW_PLAYLIST }

/**
 * What can be done with one song, opened by pressing and holding it in a list: play it next, add it
 * to the queue, or add it to a playlist (an existing one, or a new one made on the spot). A panel
 * rising from the bottom over a dimmed backdrop; [track] = null hides it.
 */
@Composable
fun TrackActionsSheet(
    track: Track?,
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onPlayNext: (Track) -> Unit,
    onAddToQueue: (Track) -> Unit,
    onAddToPlaylist: (playlistId: Long, track: Track) -> Unit,
    onCreatePlaylist: (name: String, track: Track) -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    // Keep showing the song while the sheet fades out.
    var last by remember { mutableStateOf<Track?>(null) }
    LaunchedEffect(track) {
        if (track != null) last = track
    }
    val shown = track ?: last
    var mode by remember(track?.contentUri) { mutableStateOf(SheetMode.ACTIONS) }
    // Composed after everything it floats over, so Back closes the sheet before the screen beneath it.
    BackHandler(enabled = track != null) { onDismiss() }
    var newName by remember(track?.contentUri) { mutableStateOf("") }

    AnimatedVisibility(
        visible = track != null,
        modifier = modifier,
        enter = fadeIn(tween(160)),
        exit = fadeOut(tween(120)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.36f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            if (shown != null) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .neoFloating(cornerRadius = 26.dp, elevation = 16.dp)
                        .padding(vertical = 12.dp)
                        // Swallow taps on the panel itself so they don't reach the backdrop.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    when (mode) {
                        SheetMode.ACTIONS -> {
                            SongHeader(shown)
                            NeoDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            SheetRow(NeoIcons.Next, "Play next") {
                                onPlayNext(shown)
                                onDismiss()
                            }
                            SheetRow(NeoIcons.Queue, "Add to queue") {
                                onAddToQueue(shown)
                                onDismiss()
                            }
                            SheetRow(NeoIcons.AddToPlaylist, "Add to playlist", showChevron = true) {
                                mode = SheetMode.PICK_PLAYLIST
                            }
                        }

                        SheetMode.PICK_PLAYLIST -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NeoIconButton(
                                    icon = NeoIcons.Back,
                                    onClick = { mode = SheetMode.ACTIONS },
                                    diameter = 40.dp,
                                    contentDescription = "Back",
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(text = "Add to playlist", color = neo.textPrimary, style = NeoType.heading)
                            }
                            NeoDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                                item(key = "new") {
                                    SheetRow(NeoIcons.Add, "New playlist") { mode = SheetMode.NEW_PLAYLIST }
                                }
                                items(playlists, key = { it.id }) { playlist ->
                                    SheetRow(
                                        icon = NeoIcons.Playlists,
                                        title = playlist.name,
                                        subtitle = if (playlist.trackCount == 1) "1 song" else "${playlist.trackCount} songs",
                                    ) {
                                        onAddToPlaylist(playlist.id, shown)
                                        onDismiss()
                                    }
                                }
                            }
                        }

                        SheetMode.NEW_PLAYLIST -> {
                            Column(modifier = Modifier.padding(horizontal = 20.dp)) {
                                Text(text = "New playlist", color = neo.textPrimary, style = NeoType.heading)
                                Spacer(Modifier.height(14.dp))
                                NeoTextField(
                                    value = newName,
                                    onValueChange = { newName = it },
                                    placeholder = "Playlist name",
                                    modifier = Modifier.fillMaxWidth(),
                                    autoFocus = true,
                                    onDone = {
                                        if (newName.isNotBlank()) {
                                            onCreatePlaylist(newName, shown)
                                            onDismiss()
                                        }
                                    },
                                )
                                Spacer(Modifier.height(16.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    NeoPillButton(
                                        text = "Back",
                                        onClick = { mode = SheetMode.PICK_PLAYLIST },
                                        modifier = Modifier.weight(1f),
                                    )
                                    NeoPillButton(
                                        text = "Create",
                                        onClick = {
                                            onCreatePlaylist(newName, shown)
                                            onDismiss()
                                        },
                                        modifier = Modifier.weight(1f),
                                        accent = true,
                                        enabled = newName.isNotBlank(),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongHeader(track: Track) {
    val neo = LocalNeoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoAlbumArt(
            uri = track.albumArtUri.ifBlank { track.contentUri },
            modifier = Modifier.size(52.dp),
            cornerRadius = 12.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { "Unknown title" },
                color = neo.textPrimary,
                style = NeoType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = track.artist.ifBlank { "Unknown artist" },
                color = neo.textSecondary,
                style = NeoType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SheetRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    showChevron: Boolean = false,
    onClick: () -> Unit,
) {
    val neo = LocalNeoColors.current
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .background(
                color = if (pressed) neo.accent.copy(alpha = 0.12f) else Color.Transparent,
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(icon = icon, diameter = 38.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = neo.textPrimary, style = NeoType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(text = subtitle, color = neo.textSecondary, style = NeoType.caption, maxLines = 1)
            }
        }
        if (showChevron) {
            Icon(
                imageVector = NeoIcons.Chevron,
                contentDescription = null,
                tint = neo.textTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

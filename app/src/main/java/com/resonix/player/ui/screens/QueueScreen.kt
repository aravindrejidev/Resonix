package com.resonix.player.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.data.Track
import com.resonix.player.playback.QueueSnapshot
import com.resonix.player.ui.components.TrackRow
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.neoBackground
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/**
 * The queue: every song in play order, the one playing highlighted and the ones already played
 * dimmed. Tap a song to jump to it, the cross takes it out of the queue, press and hold for its
 * actions, and the bin clears everything after the song that is playing.
 */
@Composable
fun QueueScreen(
    queue: QueueSnapshot,
    isPlaying: Boolean,
    onJump: (index: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onClear: () -> Unit,
    onTrackMenu: (Track) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val upNext = if (queue.currentIndex in queue.tracks.indices) queue.tracks.size - queue.currentIndex - 1 else 0
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (queue.currentIndex - 1).coerceAtLeast(0),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .neoBackground()
            // Swallow touches so the player underneath does not react to them.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {})
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(
                icon = NeoIcons.Back,
                onClick = onClose,
                contentDescription = "Close queue",
                modifier = Modifier.padding(end = 14.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "QUEUE", color = neo.accent, style = NeoType.overline, maxLines = 1)
                Text(
                    text = if (queue.tracks.isEmpty()) "Nothing queued" else "${queue.tracks.size} songs",
                    color = neo.textPrimary,
                    style = NeoType.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (upNext == 1) "1 song up next" else "$upNext songs up next",
                    color = neo.textSecondary,
                    style = NeoType.caption,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(12.dp))
            if (upNext > 0) {
                NeoIconButton(
                    icon = NeoIcons.Delete,
                    onClick = onClear,
                    contentDescription = "Clear up next",
                    diameter = 44.dp,
                )
            }
        }

        if (queue.tracks.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp)
                    .padding(bottom = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                NeoIconBadge(icon = NeoIcons.Queue, diameter = 84.dp)
                Spacer(Modifier.height(24.dp))
                Text(text = "The queue is empty", color = neo.textPrimary, style = NeoType.title, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Press and hold a song and choose Play next or Add to queue.",
                    color = neo.textSecondary,
                    style = NeoType.bodyRegular,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                // A song can be in the queue twice, so the position is part of the key.
                itemsIndexed(queue.tracks, key = { index, track -> "$index:${track.contentUri}" }) { index, track ->
                    val current = index == queue.currentIndex
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TrackRow(
                            track = track,
                            isCurrent = current,
                            isPlaying = isPlaying,
                            showBadge = false,
                            showArt = true,
                            onClick = { onJump(index) },
                            modifier = Modifier
                                .weight(1f)
                                .alpha(if (index < queue.currentIndex) 0.5f else 1f),
                            onLongClick = { onTrackMenu(track) },
                        )
                        if (!current) {
                            NeoIconButton(
                                icon = NeoIcons.Close,
                                onClick = { onRemove(index) },
                                diameter = 38.dp,
                                iconSize = 18.dp,
                                contentDescription = "Remove from queue",
                            )
                        }
                    }
                }
            }
        }
    }
}

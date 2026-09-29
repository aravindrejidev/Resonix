package com.resonix.player.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.NowPlayingUiState
import com.resonix.player.ui.components.GlassBackground
import com.resonix.player.ui.components.QualityBadge
import com.resonix.player.ui.components.formatQualityLabel
import com.resonix.player.ui.rememberAlbumArt
import com.resonix.player.ui.theme.ResonixCrimson
import com.resonix.player.ui.theme.ResonixSurfaceVariant
import com.resonix.player.ui.theme.ResonixTextPrimary
import com.resonix.player.ui.theme.ResonixTextSecondary

@Composable
fun NowPlayingScreen(
    uiState: NowPlayingUiState,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onSeek: (Long) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val art by rememberAlbumArt(uiState.albumArtUri)

    // Local only — there's no favorites concept in the backend/DB yet,
    // so this doesn't persist across tracks or app restarts.
    var isFavorite by remember(uiState.title) { mutableStateOf(false) }

    // Dragged locally so the slider doesn't fight the periodic position
    // updates while the user is actively seeking.
    var draggingPositionMs by remember { mutableStateOf<Long?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        GlassBackground(albumArt = art, modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = ResonixTextPrimary
                    )
                }
                IconButton(onClick = { isFavorite = !isFavorite }) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorite",
                        tint = if (isFavorite) ResonixCrimson else ResonixTextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(ResonixSurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val currentArt = art
                if (currentArt != null) {
                    Image(
                        bitmap = currentArt,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = ResonixTextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = uiState.title.ifEmpty { "Nothing playing" },
                style = MaterialTheme.typography.headlineSmall,
                color = ResonixTextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = ResonixTextSecondary
            )
            Spacer(modifier = Modifier.height(10.dp))
            QualityBadge(text = formatQualityLabel(uiState.format, uiState.bitDepth, uiState.sampleRateHz))

            Spacer(modifier = Modifier.height(28.dp))

            val sliderMax = uiState.durationMs.coerceAtLeast(1L).toFloat()
            val sliderValue = (draggingPositionMs ?: uiState.positionMs).toFloat().coerceIn(0f, sliderMax)
            Slider(
                value = sliderValue,
                onValueChange = { draggingPositionMs = it.toLong() },
                onValueChangeFinished = {
                    draggingPositionMs?.let(onSeek)
                    draggingPositionMs = null
                },
                valueRange = 0f..sliderMax,
                colors = SliderDefaults.colors(
                    thumbColor = ResonixCrimson,
                    activeTrackColor = ResonixCrimson,
                    inactiveTrackColor = ResonixSurfaceVariant
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = formatMs(draggingPositionMs ?: uiState.positionMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = ResonixTextSecondary
                )
                Text(
                    text = formatMs(uiState.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = ResonixTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPreviousClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        tint = ResonixTextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }
                FilledIconButton(
                    onClick = onPlayPauseClick,
                    modifier = Modifier.size(72.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ResonixCrimson)
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(34.dp)
                    )
                }
                IconButton(onClick = onNextClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        tint = ResonixTextPrimary,
                        modifier = Modifier.size(34.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

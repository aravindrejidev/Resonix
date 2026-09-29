package com.resonix.player.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.rememberAlbumArt
import com.resonix.player.ui.theme.ResonixSurface
import com.resonix.player.ui.theme.ResonixSurfaceVariant
import com.resonix.player.ui.theme.ResonixTextPrimary
import com.resonix.player.ui.theme.ResonixTextSecondary

@Composable
fun MiniPlayer(
    title: String,
    artist: String,
    albumArtUri: String,
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val art by rememberAlbumArt(albumArtUri)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(ResonixSurfaceVariant.copy(alpha = 0.92f))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(ResonixSurface),
            contentAlignment = Alignment.Center
        ) {
            val currentArt = art
            if (currentArt != null) {
                Image(
                    bitmap = currentArt,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(44.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = ResonixTextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.bodySmall,
                color = ResonixTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        IconButton(onClick = onPlayPauseClick) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = ResonixTextPrimary
            )
        }
        IconButton(onClick = onNextClick) {
            Icon(
                imageVector = Icons.Filled.SkipNext,
                contentDescription = "Next",
                tint = ResonixTextPrimary
            )
        }
    }
}

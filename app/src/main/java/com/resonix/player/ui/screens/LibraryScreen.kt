package com.resonix.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.resonix.player.data.Track
import com.resonix.player.ui.components.TrackListItem
import com.resonix.player.ui.theme.ResonixTextSecondary

/** Bottom padding under the list so the last rows aren't hidden behind
 * the floating mini-player + capsule nav overlaying this screen. */
private val BottomOverlayPadding = 180.dp

@Composable
fun LibraryScreen(
    tracks: List<Track>,
    isScanning: Boolean,
    onTrackClick: (Track) -> Unit,
    onScanClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Library", style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${tracks.size} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = ResonixTextSecondary
                )
            }

            FilledTonalIconButton(onClick = onScanClick) {
                if (isScanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Scan library")
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = BottomOverlayPadding)
        ) {
            items(items = tracks, key = { it.contentUri }) { track ->
                TrackListItem(track = track, onClick = { onTrackClick(track) })
            }
        }
    }
}

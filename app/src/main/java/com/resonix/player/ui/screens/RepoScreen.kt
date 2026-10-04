package com.resonix.player.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.data.ScannedFolder
import com.resonix.player.data.Track
import com.resonix.player.ui.components.TrackListItem
import com.resonix.player.ui.theme.ResonixCrimson
import com.resonix.player.ui.theme.ResonixSurfaceVariant
import com.resonix.player.ui.theme.ResonixTextPrimary
import com.resonix.player.ui.theme.ResonixTextSecondary

private val BottomOverlayPadding = 180.dp

@Composable
fun RepoScreen(
    scannedFolders: List<ScannedFolder>,
    scanningFolderUri: String?,
    onAddFolder: (Uri) -> Unit,
    onRescanFolder: (String) -> Unit,
    onOpenFolder: (ScannedFolder) -> Unit,
    modifier: Modifier = Modifier
) {
    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) onAddFolder(uri)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "Repo", style = MaterialTheme.typography.headlineMedium)
            FilledTonalIconButton(onClick = { pickFolderLauncher.launch(null) }) {
                Icon(imageVector = Icons.Filled.CreateNewFolder, contentDescription = "Add folder")
            }
        }

        if (scannedFolders.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = BottomOverlayPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No folders added yet — tap + to add one",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ResonixTextSecondary
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = BottomOverlayPadding)) {
                items(items = scannedFolders, key = { it.rootUri }) { folder ->
                    FolderListItem(
                        folder = folder,
                        isScanning = folder.rootUri == scanningFolderUri,
                        onClick = { onOpenFolder(folder) },
                        onRescanClick = { onRescanFolder(folder.rootUri) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderListItem(
    folder: ScannedFolder,
    isScanning: Boolean,
    onClick: () -> Unit,
    onRescanClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(ResonixSurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Filled.Folder, contentDescription = null, tint = ResonixCrimson)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = folder.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Last scanned ${formatRelativeTime(folder.lastScannedAtSec)}",
                style = MaterialTheme.typography.bodySmall,
                color = ResonixTextSecondary
            )
        }
        IconButton(onClick = onRescanClick) {
            if (isScanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Rescan",
                    tint = ResonixTextSecondary
                )
            }
        }
    }
}

@Composable
fun FolderDetailScreen(
    folder: ScannedFolder,
    tracks: List<Track>,
    onTrackClick: (Track) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = ResonixTextPrimary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column {
                Text(text = folder.displayName, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "${tracks.size} tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = ResonixTextSecondary
                )
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = BottomOverlayPadding)) {
            items(items = tracks, key = { it.contentUri }) { track ->
                TrackListItem(track = track, onClick = { onTrackClick(track) })
            }
        }
    }
}

private fun formatRelativeTime(epochSec: Long): String {
    val nowSec = System.currentTimeMillis() / 1000
    val diffSec = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        diffSec < 60 -> "just now"
        diffSec < 3600 -> "${diffSec / 60}m ago"
        diffSec < 86400 -> "${diffSec / 3600}h ago"
        else -> "${diffSec / 86400}d ago"
    }
}

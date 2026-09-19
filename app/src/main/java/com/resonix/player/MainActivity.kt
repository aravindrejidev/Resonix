package com.resonix.player

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.ui.theme.ResonixTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ResonixTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PlayerScreen()
                }
            }
        }
    }
}

/**
 * Phase 2A smoke-test UI: pick any audio file via the system document
 * picker (no storage permission required — SAF grants access to just
 * that file) and play it through the real FFmpeg decode pipeline.
 * Phase 2B replaces the "pick a file" button with a scanned library
 * list, feeding playTrack() the same way.
 */
@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val engine = remember { NativeAudioEngine() }
    var isPlaying by remember { mutableStateOf(false) }
    var trackInfo by remember { mutableStateOf<AudioTrackInfo?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var openFd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var pickedFileName by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            engine.release()
            openFd?.close()
        }
    }

    // Position polling stand-in for Phase 2A verification. Phase 2B's
    // UI should drive this from a MediaSession/PlaybackState callback
    // instead of polling on a timer.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = engine.getCurrentPositionMs()
            delay(250)
        }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        openFd?.close()
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        openFd = pfd

        if (pfd != null) {
            // FFmpeg's file protocol wants a path; /proc/self/fd bridges
            // the already-open, SAF-granted descriptor into one without
            // copying the file or needing a storage permission.
            val fdPath = "/proc/self/fd/${pfd.fd}"
            val started = engine.playTrack(fdPath)
            isPlaying = started
            trackInfo = if (started) engine.getAudioTrackInfo() else null
            pickedFileName = queryDisplayName(context.contentResolver, uri)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Resonix", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = pickedFileName ?: "No file loaded",
            style = MaterialTheme.typography.bodyMedium
        )

        trackInfo?.let { info ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${info.format.uppercase()} • ${info.sampleRateHz} Hz • " +
                        "${info.bitDepth}-bit • ${info.channelCount}ch",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${formatMs(positionMs)} / ${formatMs(info.durationMs)}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FilledTonalIconButton(
                onClick = { pickFileLauncher.launch(arrayOf("audio/*")) },
                modifier = Modifier.size(64.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.FolderOpen,
                    contentDescription = "Open file",
                    modifier = Modifier.size(28.dp)
                )
            }

            FilledIconButton(
                onClick = {
                    if (trackInfo == null) return@FilledIconButton
                    if (isPlaying) {
                        engine.pauseTrack()
                    } else {
                        engine.resumeTrack()
                    }
                    isPlaying = !isPlaying
                },
                enabled = trackInfo != null,
                modifier = Modifier.size(80.dp)
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    val projection = arrayOf(OpenableColumns.DISPLAY_NAME)
    resolver.query(uri, projection, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) {
            return cursor.getString(nameIndex)
        }
    }
    return null
}

private fun formatMs(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

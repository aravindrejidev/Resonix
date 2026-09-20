package com.resonix.player

import android.Manifest
import android.content.ContentResolver
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.resonix.player.audio.AudioTrackInfo
import com.resonix.player.audio.NativeAudioEngine
import com.resonix.player.data.AppDatabase
import com.resonix.player.data.MediaStoreScanner
import com.resonix.player.ui.theme.ResonixTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "PlayerScreen"

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
 * Phase 2B: adds a permission-gated MediaStore scan and a Room-backed
 * track list on top of Phase 2A's SAF file picker. Both ways of picking
 * a file funnel into the same [playUri] — MediaStore rows store a
 * content:// URI, not a filesystem path, so playback opens a fresh file
 * descriptor for it exactly the way the SAF path already did.
 *
 * State is kept directly in this Composable rather than a ViewModel —
 * fine while the UI is this simple, but worth revisiting once a real
 * player screen (queue, more controls) makes this file grow further.
 */
@Composable
fun PlayerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { NativeAudioEngine() }
    val scanner = remember { MediaStoreScanner(context) }
    val tracks by remember {
        AppDatabase.getInstance(context).trackDao().getAllTracks()
    }.collectAsState(initial = emptyList())

    var isPlaying by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var trackInfo by remember { mutableStateOf<AudioTrackInfo?>(null) }
    var positionMs by remember { mutableStateOf(0L) }
    var nowPlayingFd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    var nowPlayingLabel by remember { mutableStateOf<String?>(null) }

    val readAudioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, readAudioPermission) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    fun runScan() {
        scope.launch {
            isScanning = true
            scanner.scan()
            isScanning = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        if (granted) runScan()
    }

    fun playUri(uri: Uri, label: String?) {
        try {
            nowPlayingFd?.close()
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            nowPlayingFd = pfd
            if (pfd != null) {
                val started = engine.playTrack("/proc/self/fd/${pfd.fd}")
                isPlaying = started
                trackInfo = if (started) engine.getAudioTrackInfo() else null
                nowPlayingLabel = label
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open $uri", e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            engine.release()
            nowPlayingFd?.close()
        }
    }

    // Position polling stand-in — a MediaSession/PlaybackState callback
    // would replace this once the app has one.
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = engine.getCurrentPositionMs()
            delay(250)
        }
    }

    val pickFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            playUri(uri, queryDisplayName(context.contentResolver, uri))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Resonix", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = nowPlayingLabel ?: "Nothing playing",
                style = MaterialTheme.typography.bodyMedium
            )

            trackInfo?.let { info ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${info.format.uppercase()} • ${info.sampleRateHz} Hz • " +
                            "${info.bitDepth}-bit • ${info.channelCount}ch",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "${formatMs(positionMs)} / ${formatMs(info.durationMs)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                FilledTonalIconButton(
                    onClick = { pickFileLauncher.launch(arrayOf("audio/*")) },
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = "Open file")
                }

                FilledTonalIconButton(
                    onClick = {
                        if (hasPermission) runScan() else permissionLauncher.launch(readAudioPermission)
                    },
                    modifier = Modifier.size(56.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Scan library")
                    }
                }

                FilledIconButton(
                    onClick = {
                        if (trackInfo == null) return@FilledIconButton
                        if (isPlaying) engine.pauseTrack() else engine.resumeTrack()
                        isPlaying = !isPlaying
                    },
                    enabled = trackInfo != null,
                    modifier = Modifier.size(56.dp)
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play"
                    )
                }
            }
        }

        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(items = tracks, key = { it.mediaStoreId }) { track ->
                ListItem(
                    headlineContent = { Text(track.title) },
                    supportingContent = {
                        Text(
                            "${track.artist} • ${track.format.uppercase()} " +
                                    "${track.sampleRateHz}Hz/${track.bitDepth}-bit"
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            playUri(track.contentUri.toUri(), "${track.title} — ${track.artist}")
                        }
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

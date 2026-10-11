package com.resonix.player.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.NowPlayingUiState
import com.resonix.player.ui.neo.NeoAccentIconButton
import com.resonix.player.ui.neo.NeoAlbumArt
import com.resonix.player.ui.neo.NeoHiResTag
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoQualityPill
import com.resonix.player.ui.neo.NeoSlider
import com.resonix.player.ui.neo.formatDuration
import com.resonix.player.ui.neo.isHiRes
import com.resonix.player.ui.neo.neoBackground
import com.resonix.player.ui.neo.neoInset
import com.resonix.player.ui.neo.neoRaised
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import kotlinx.coroutines.delay

/** Full-screen player: big album art on a raised plate, seek bar, transport controls. */
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    keepScreenOn: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onClose: () -> Unit,
    onOpenTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current

    val view = LocalView.current
    DisposableEffect(keepScreenOn) {
        val previous = view.keepScreenOn
        view.keepScreenOn = keepScreenOn
        onDispose { view.keepScreenOn = previous }
    }

    // While the thumb is being dragged (and for a moment after release, until the player
    // reports the new position) show the dragged value instead of the polled one.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var dragging by remember { mutableStateOf(false) }
    var releaseToken by remember { mutableIntStateOf(0) }
    LaunchedEffect(releaseToken) {
        if (releaseToken > 0) {
            delay(450)
            if (!dragging) dragFraction = null
        }
    }
    val playedFraction = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = dragFraction ?: playedFraction
    val shownPositionMs = (shownFraction * state.durationMs).toLong()

    val artScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 220f),
        label = "nowPlayingArtScale",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .neoBackground()
            // Swallow touches so nothing underneath this overlay reacts to them.
            .pointerInput(Unit) { detectTapGestures { } }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(icon = NeoIcons.Down, onClick = onClose, contentDescription = "Close player")
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "NOW PLAYING", color = neo.accent, style = NeoType.overline)
                if (state.format.isNotBlank()) {
                    Text(
                        text = state.format.uppercase(),
                        color = neo.textTertiary,
                        style = NeoType.tiny,
                        maxLines = 1,
                    )
                }
            }
            NeoIconButton(icon = NeoIcons.Tools, onClick = onOpenTools, contentDescription = "Sound tools")
        }

        // Album art on its plate — as large as the screen allows.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val side = (minOf(maxWidth, maxHeight) - 36.dp).coerceAtLeast(120.dp)
            Column(
                modifier = Modifier
                    .size(side)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .neoRaised(cornerRadius = 40.dp, elevation = 14.dp)
                    .padding(14.dp)
                    .neoInset(cornerRadius = 28.dp, elevation = 6.dp)
                    .padding(6.dp),
            ) {
                NeoAlbumArt(
                    uri = state.albumArtUri.ifBlank { state.contentUri },
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 22.dp,
                    sizePx = 1024,
                    iconSize = 72.dp,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = state.title.ifBlank { "Unknown title" },
            color = neo.textPrimary,
            style = NeoType.title,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.artist.ifBlank { "Unknown artist" },
            color = neo.textSecondary,
            style = NeoType.body,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.format.isNotBlank()) NeoQualityPill(state.format.uppercase())
            if (state.bitDepth > 0) NeoQualityPill("${state.bitDepth}-bit")
            if (state.sampleRateHz > 0) {
                val khz = state.sampleRateHz / 1000f
                val khzText = if (khz % 1f == 0f) khz.toInt().toString() else "%.1f".format(java.util.Locale.US, khz)
                NeoQualityPill("$khzText kHz")
            }
            if (isHiRes(state.bitDepth, state.sampleRateHz)) NeoHiResTag()
        }

        Spacer(Modifier.height(20.dp))
        NeoSlider(
            value = shownFraction,
            onValueChange = {
                dragging = true
                dragFraction = it
            },
            onValueChangeFinished = {
                dragging = false
                dragFraction?.let { fraction -> onSeek((fraction * state.durationMs).toLong()) }
                releaseToken++
            },
            enabled = state.durationMs > 0L,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatDuration(shownPositionMs), color = neo.textSecondary, style = NeoType.caption)
            Text(text = formatDuration(state.durationMs), color = neo.textSecondary, style = NeoType.caption)
        }

        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(
                icon = NeoIcons.Previous,
                onClick = onPrevious,
                diameter = 62.dp,
                iconSize = 28.dp,
                contentDescription = "Previous",
            )
            NeoAccentIconButton(
                icon = if (state.isPlaying) NeoIcons.Pause else NeoIcons.Play,
                onClick = onPlayPause,
                diameter = 86.dp,
                iconSize = 40.dp,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
            )
            NeoIconButton(
                icon = NeoIcons.Next,
                onClick = onNext,
                diameter = 62.dp,
                iconSize = 28.dp,
                contentDescription = "Next",
            )
        }
    }
}

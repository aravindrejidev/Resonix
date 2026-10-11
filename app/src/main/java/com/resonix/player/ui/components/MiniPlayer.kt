package com.resonix.player.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.NowPlayingUiState
import com.resonix.player.ui.neo.NeoAlbumArt
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.neoInset
import com.resonix.player.ui.neo.neoSurface
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/**
 * Compact player that floats above the navigation bar: art, title, play / pause with a progress
 * ring around it, and next. Tap anywhere else to open Now Playing.
 */
@Composable
fun MiniPlayer(
    state: NowPlayingUiState,
    showArt: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val depth = animateFloatAsState(
        targetValue = if (pressed) 0.6f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "miniPlayerDepth",
    )
    val progress = if (state.durationMs > 0L) {
        (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .neoSurface(cornerRadius = 30.dp, elevation = 8.dp, depth = { depth.value })
            .clickable(interactionSource = source, indication = null) {
                haptic()
                onOpen()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(54.dp)
                .neoInset(cornerRadius = 18.dp, elevation = 3.dp)
                .padding(4.dp),
        ) {
            NeoAlbumArt(
                uri = if (showArt) state.albumArtUri.ifBlank { state.contentUri } else "",
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 14.dp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.title.ifBlank { "Unknown title" },
                color = neo.textPrimary,
                style = NeoType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.artist.ifBlank { "Unknown artist" },
                color = neo.textSecondary,
                style = NeoType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .drawBehind {
                    val stroke = 3.dp.toPx()
                    val ringTopLeft = Offset(stroke / 2f, stroke / 2f)
                    val ringSize = Size(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = neo.textTertiary.copy(alpha = 0.25f),
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = ringTopLeft,
                        size = ringSize,
                        style = Stroke(width = stroke),
                    )
                    if (progress > 0f) {
                        drawArc(
                            color = neo.accent,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            topLeft = ringTopLeft,
                            size = ringSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            NeoIconButton(
                icon = if (state.isPlaying) NeoIcons.Pause else NeoIcons.Play,
                onClick = onPlayPause,
                diameter = 44.dp,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                tint = neo.accent,
            )
        }
        Spacer(Modifier.width(4.dp))
        NeoIconButton(
            icon = NeoIcons.Next,
            onClick = onNext,
            diameter = 40.dp,
            iconSize = 20.dp,
            contentDescription = "Next",
        )
    }
}

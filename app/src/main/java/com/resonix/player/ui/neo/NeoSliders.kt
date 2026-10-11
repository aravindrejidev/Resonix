package com.resonix.player.ui.neo

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import kotlin.math.roundToInt

private fun fractionAlong(pos: Float, thumbPx: Float, lengthPx: Int): Float =
    ((pos - thumbPx / 2f) / (lengthPx - thumbPx).coerceAtLeast(1f)).coerceIn(0f, 1f)

/**
 * Horizontal slider: an inset groove, an accent fill and a raised round thumb.
 * [value] is 0..1. With [centered] the fill grows from the middle (balance, gain).
 * [onValueChangeFinished] fires when the finger lifts — use it to seek.
 */
@Composable
fun NeoSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    trackHeight: Dp = 12.dp,
    thumbDiameter: Dp = 28.dp,
    enabled: Boolean = true,
    centered: Boolean = false,
) {
    val neo = LocalNeoColors.current
    val density = LocalDensity.current
    val v = value.coerceIn(0f, 1f)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinish by rememberUpdatedState(onValueChangeFinished)
    var widthPx by remember { mutableIntStateOf(0) }
    val thumbPx = with(density) { thumbDiameter.toPx() }
    val travel = (widthPx - thumbPx).coerceAtLeast(1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thumbDiameter + 14.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, thumbPx) {
                if (enabled) {
                    detectTapGestures { offset ->
                        currentOnChange(fractionAlong(offset.x, thumbPx, size.width))
                        currentOnFinish()
                    }
                }
            }
            .pointerInput(enabled, thumbPx) {
                if (enabled) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> currentOnChange(fractionAlong(offset.x, thumbPx, size.width)) },
                        onDragEnd = { currentOnFinish() },
                        onDragCancel = { currentOnFinish() },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            currentOnChange(fractionAlong(change.position.x, thumbPx, size.width))
                        },
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // Groove + accent fill.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .neoInset(cornerRadius = trackHeight / 2, elevation = 3.dp)
                .drawBehind {
                    val pad = 2.dp.toPx()
                    val thumbCenter = thumbPx / 2f + v * travel
                    val origin = if (centered) thumbPx / 2f + 0.5f * travel else 0f
                    val left = minOf(origin, thumbCenter)
                    val right = maxOf(origin, thumbCenter)
                    if (right - left > 1f) {
                        drawRoundRect(
                            brush = Brush.horizontalGradient(
                                listOf(neo.accentLow, neo.accentHigh),
                                startX = left,
                                endX = right,
                            ),
                            topLeft = Offset(left, pad),
                            size = Size(right - left, size.height - pad * 2f),
                            cornerRadius = CornerRadius((size.height - pad * 2f) / 2f),
                            alpha = if (enabled) 1f else 0.4f,
                        )
                    }
                },
        )
        // Thumb.
        Box(
            modifier = Modifier
                .offset { IntOffset((v * travel).roundToInt(), 0) }
                .size(thumbDiameter)
                .neoRaised(cornerRadius = thumbDiameter / 2, elevation = 4.dp)
                .drawBehind {
                    drawCircle(
                        color = if (enabled) neo.accent else neo.textTertiary,
                        radius = size.minDimension * 0.16f,
                    )
                },
        )
    }
}

/**
 * Vertical "fader" for one equaliser band: [value] 0 (bottom) … 1 (top), fill grows from
 * the centre line. [onDragStateChange] reports when a finger goes down / up.
 */
@Composable
fun NeoVerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDragStateChange: (Boolean) -> Unit = {},
    enabled: Boolean = true,
    capWidth: Dp = 24.dp,
    capHeight: Dp = 20.dp,
) {
    val neo = LocalNeoColors.current
    val density = LocalDensity.current
    val v = value.coerceIn(0f, 1f)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnDrag by rememberUpdatedState(onDragStateChange)
    var heightPx by remember { mutableIntStateOf(0) }
    val capPx = with(density) { capHeight.toPx() }
    val travel = (heightPx - capPx).coerceAtLeast(1f)

    Box(
        modifier = modifier
            .width(capWidth)
            .fillMaxHeight()
            .onSizeChanged { heightPx = it.height }
            .pointerInput(enabled, capPx) {
                if (enabled) {
                    detectTapGestures { offset ->
                        currentOnChange(1f - fractionAlong(offset.y, capPx, size.height))
                    }
                }
            }
            .pointerInput(enabled, capPx) {
                if (enabled) {
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            currentOnDrag(true)
                            currentOnChange(1f - fractionAlong(offset.y, capPx, size.height))
                        },
                        onDragEnd = { currentOnDrag(false) },
                        onDragCancel = { currentOnDrag(false) },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            currentOnChange(1f - fractionAlong(change.position.y, capPx, size.height))
                        },
                    )
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Groove with the centre (0 dB) tick and the accent fill.
        Box(
            modifier = Modifier
                .width(10.dp)
                .fillMaxHeight()
                .neoInset(cornerRadius = 5.dp, elevation = 3.dp)
                .drawBehind {
                    val pad = 2.dp.toPx()
                    val centerY = capPx / 2f + 0.5f * travel
                    val valueY = capPx / 2f + (1f - v) * travel
                    val top = minOf(centerY, valueY)
                    val bottom = maxOf(centerY, valueY)
                    if (bottom - top > 1f) {
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                listOf(neo.accentHigh, neo.accentLow),
                                startY = top,
                                endY = bottom,
                            ),
                            topLeft = Offset(pad, top),
                            size = Size(size.width - pad * 2f, bottom - top),
                            cornerRadius = CornerRadius((size.width - pad * 2f) / 2f),
                            alpha = if (enabled) 1f else 0.4f,
                        )
                    }
                    drawLine(
                        color = neo.textTertiary.copy(alpha = 0.55f),
                        start = Offset(0f, centerY),
                        end = Offset(size.width, centerY),
                        strokeWidth = 1.dp.toPx(),
                    )
                },
        )
        // Fader cap.
        Box(
            modifier = Modifier
                .offset { IntOffset(0, ((1f - v) * travel).roundToInt()) }
                .size(width = capWidth, height = capHeight)
                .neoRaised(cornerRadius = 8.dp, elevation = 3.dp)
                .drawBehind {
                    drawLine(
                        color = if (enabled) neo.accent else neo.textTertiary,
                        start = Offset(size.width * 0.28f, size.height / 2f),
                        end = Offset(size.width * 0.72f, size.height / 2f),
                        strokeWidth = 2.dp.toPx(),
                        cap = StrokeCap.Round,
                    )
                },
        )
    }
}

/**
 * Rotary knob: an inset ring with an accent arc, and a raised cap with a pointer dot.
 * Drag up / down to turn. [value] is 0..1.
 */
@Composable
fun NeoKnob(
    value: Float,
    onValueChange: (Float) -> Unit,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
    diameter: Dp = 80.dp,
) {
    val neo = LocalNeoColors.current
    val density = LocalDensity.current
    val v = value.coerceIn(0f, 1f)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentValue by rememberUpdatedState(v)
    val dragRangePx = with(density) { 220.dp.toPx() }

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(diameter)
                .neoInset(cornerRadius = diameter / 2, elevation = 5.dp)
                .drawBehind {
                    val stroke = 4.dp.toPx()
                    val inset = 11.dp.toPx()
                    val arcTopLeft = Offset(inset, inset)
                    val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
                    drawArc(
                        color = neo.textTertiary.copy(alpha = 0.30f),
                        startAngle = 135f,
                        sweepAngle = 270f,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    if (v > 0.005f) {
                        drawArc(
                            color = neo.accent,
                            startAngle = 135f,
                            sweepAngle = 270f * v,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                }
                .clip(CircleShape)
                .pointerInput(dragRangePx) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        currentOnChange((currentValue - dragAmount / dragRangePx).coerceIn(0f, 1f))
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(diameter * 0.68f)
                    .neoRaised(cornerRadius = diameter * 0.34f, elevation = 5.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = -135f + 270f * v },
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 7.dp)
                            .size(7.dp)
                            .background(neo.accent, CircleShape),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = valueText,
            color = neo.accent,
            style = NeoType.caption.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(text = label, color = neo.textSecondary, style = NeoType.tiny, maxLines = 1)
    }
}

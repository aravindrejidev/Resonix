package com.resonix.player.ui.neo

import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.theme.LocalNeoColors

/** Depth of a resting, raised (convex) surface. */
val RaisedDepth: () -> Float = { 0f }

/** Depth of a pressed-in (concave) surface. */
val InsetDepth: () -> Float = { 1f }

internal fun Color.scaleAlpha(factor: Float): Color = copy(alpha = alpha * factor)

private val canDrawShadowLayer: Boolean get() = Build.VERSION.SDK_INT >= 28

/**
 * Two dials for the old two-shadow surface ([neoSurface], [neoRaised], [neoInset]). The app now
 * uses it only sparingly; these keep any remaining use gentle. 1 = the original strength.
 */
private const val ShadowOffsetScale = 0.55f
private const val ShadowAlphaScale = 0.55f

/**
 * The heart of the neumorphic look.
 *
 * Draws a rounded surface made of the theme's single surface colour, lit from
 * the top-left:
 *  - depth = 0 → raised: a light shadow towards the light, a dark shadow away from it
 *  - depth = 1 → inset: the same two shadows drawn *inside* the shape, reversed
 *
 * [depth] is a lambda so callers can feed it an animated value that is only
 * read while drawing — a button press then re-draws, but never recomposes.
 *
 * The shadows are drawn outside the layout bounds, so give the parent enough
 * padding (roughly 3 × [elevation]) or they will be clipped.
 */
@Composable
fun Modifier.neoSurface(
    cornerRadius: Dp = 20.dp,
    elevation: Dp = 6.dp,
    depth: () -> Float = RaisedDepth,
    fill: Color = Color.Unspecified,
    gradient: Boolean = true,
    edge: Boolean = true,
): Modifier {
    val neo = LocalNeoColors.current
    val base = if (fill == Color.Unspecified) neo.surface else fill
    return this.drawWithCache {
        val w = size.width
        val h = size.height
        val radius = minOf(cornerRadius.toPx(), minOf(w, h) / 2f)
        val off = elevation.toPx() * ShadowOffsetScale
        val blur = off * 2f
        val rect = RectF(0f, 0f, w, h)
        val roundRect = RoundRect(0f, 0f, w, h, CornerRadius(radius, radius))
        val shapePath = Path().apply { addRoundRect(roundRect) }
        val margin = blur * 1.5f + off
        val frame = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(-margin, -margin, w + margin, h + margin))
            addRoundRect(roundRect)
        }
        val androidFrame = frame.asAndroidPath()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val edgePx = 1.dp.toPx()
        val corner = CornerRadius(radius, radius)
        val edgeCorner = CornerRadius((radius - edgePx / 2f).coerceAtLeast(0f))
        val diagonalEnd = Offset(w, h)

        onDrawBehind {
            val d = depth().coerceIn(0f, 1f)
            val raised = 1f - d

            // 1. Outer shadows (raised part).
            if (raised > 0.01f) {
                if (canDrawShadowLayer) {
                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas
                        paint.color = base.toArgb()
                        paint.setShadowLayer(blur, -off, -off, neo.shadowLight.scaleAlpha(raised * ShadowAlphaScale).toArgb())
                        nc.drawRoundRect(rect, radius, radius, paint)
                        paint.setShadowLayer(blur, off, off, neo.shadowDark.scaleAlpha(raised * ShadowAlphaScale).toArgb())
                        nc.drawRoundRect(rect, radius, radius, paint)
                        paint.clearShadowLayer()
                    }
                } else {
                    drawFallbackShadow(neo.shadowLight, -off * 0.6f, -off * 0.6f, off * 1.6f, radius, w, h, raised)
                    drawFallbackShadow(neo.shadowDark, off * 0.6f, off * 0.6f, off * 1.6f, radius, w, h, raised)
                }
            }

            // 2. Solid fill, then the convex / concave sheen on top of it.
            drawRoundRect(color = base, cornerRadius = corner)
            if (gradient) {
                val topLeft = lerp(neo.lit(base), neo.shaded(base), d)
                val bottomRight = lerp(neo.shaded(base), neo.lit(base), d)
                drawRoundRect(
                    brush = Brush.linearGradient(listOf(topLeft, bottomRight), start = Offset.Zero, end = diagonalEnd),
                    cornerRadius = corner,
                )
            }

            // 3. Inner shadows (inset part): the shadow of everything *outside*
            //    the shape, clipped to the shape.
            if (d > 0.01f && canDrawShadowLayer) {
                clipPath(shapePath) {
                    drawIntoCanvas { canvas ->
                        val nc = canvas.nativeCanvas
                        paint.color = base.toArgb()
                        paint.setShadowLayer(blur, off, off, neo.shadowDark.scaleAlpha(d * ShadowAlphaScale).toArgb())
                        nc.drawPath(androidFrame, paint)
                        paint.setShadowLayer(blur, -off, -off, neo.shadowLight.scaleAlpha(d * ShadowAlphaScale).toArgb())
                        nc.drawPath(androidFrame, paint)
                        paint.clearShadowLayer()
                    }
                }
            }

            // 4. Hairline edge light.
            if (edge) {
                val half = edgePx / 2f
                val edgeSize = Size(w - edgePx, h - edgePx)
                if (raised > 0.01f) {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(neo.edgeLight.scaleAlpha(raised), Color.Transparent),
                            start = Offset.Zero,
                            end = diagonalEnd,
                        ),
                        topLeft = Offset(half, half),
                        size = edgeSize,
                        cornerRadius = edgeCorner,
                        style = Stroke(width = edgePx),
                    )
                }
                if (d > 0.01f) {
                    drawRoundRect(
                        brush = Brush.linearGradient(
                            listOf(Color.Transparent, neo.edgeLight.scaleAlpha(d * 0.8f)),
                            start = Offset.Zero,
                            end = diagonalEnd,
                        ),
                        topLeft = Offset(half, half),
                        size = edgeSize,
                        cornerRadius = edgeCorner,
                        style = Stroke(width = edgePx),
                    )
                }
            }
        }
    }
}

/** A resting, raised (convex) neumorphic surface. */
@Composable
fun Modifier.neoRaised(cornerRadius: Dp = 20.dp, elevation: Dp = 6.dp): Modifier =
    neoSurface(cornerRadius = cornerRadius, elevation = elevation, depth = RaisedDepth)

/** A pressed-in (concave) neumorphic surface — wells, tracks, displays. */
@Composable
fun Modifier.neoInset(cornerRadius: Dp = 20.dp, elevation: Dp = 5.dp): Modifier =
    neoSurface(cornerRadius = cornerRadius, elevation = elevation, depth = InsetDepth)

// ───────────────────────────── flat layer ─────────────────────────────
//
// Most of the interface is flat: surfaces are told apart by tone and a hairline, not by shadow.
// Shadow is kept for the things that really sit above something else (the floating bars and
// menus) and for the little parts you grab (thumbs, knobs, the play button).

/** A flat card: a slightly lighter fill with a hairline edge. No shadow. */
@Composable
fun Modifier.neoFlat(
    cornerRadius: Dp = 20.dp,
    fill: Color = Color.Unspecified,
    border: Boolean = true,
): Modifier {
    val neo = LocalNeoColors.current
    val shape = RoundedCornerShape(cornerRadius)
    val base = if (fill == Color.Unspecified) neo.card else fill
    return this
        .background(base, shape)
        .then(if (border) Modifier.border(1.dp, neo.outline, shape) else Modifier)
}

/** A flat recessed area (track, display, badge): a slightly darker fill. No shadow. */
@Composable
fun Modifier.neoWell(
    cornerRadius: Dp = 20.dp,
    border: Boolean = false,
): Modifier {
    val neo = LocalNeoColors.current
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .background(neo.well, shape)
        .then(if (border) Modifier.border(1.dp, neo.outline, shape) else Modifier)
}

/**
 * A panel that floats above the content (navigation bar, mini player, menus, toast): one soft
 * drop shadow underneath, a flat fill and a hairline edge. This is where shadow earns its place.
 */
@Composable
fun Modifier.neoFloating(
    cornerRadius: Dp = 24.dp,
    elevation: Dp = 10.dp,
): Modifier {
    val neo = LocalNeoColors.current
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (neo.isDark) 0.40f else 0.10f),
            spotColor = Color.Black.copy(alpha = if (neo.isDark) 0.55f else 0.22f),
        )
        .background(neo.card, shape)
        .border(1.dp, neo.outline, shape)
}

/** The part of a control you grab (slider thumb, knob cap, switch thumb): a small, tight shadow. */
@Composable
fun Modifier.neoThumb(
    cornerRadius: Dp = 14.dp,
    elevation: Dp = 3.dp,
    fill: Color = Color.Unspecified,
): Modifier {
    val neo = LocalNeoColors.current
    val shape = RoundedCornerShape(cornerRadius)
    return this
        .shadow(
            elevation = elevation,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (neo.isDark) 0.35f else 0.08f),
            spotColor = Color.Black.copy(alpha = if (neo.isDark) 0.50f else 0.20f),
        )
        .background(if (fill == Color.Unspecified) neo.thumb else fill, shape)
        .border(1.dp, neo.outline, shape)
}

/**
 * The glowing accent-coloured body used by primary buttons. [press] (0..1) dims the glow
 * while the button is held down.
 */
@Composable
fun Modifier.neoAccentSurface(
    cornerRadius: Dp = 24.dp,
    glow: Dp = 14.dp,
    press: () -> Float = RaisedDepth,
): Modifier {
    val neo = LocalNeoColors.current
    return this.drawWithCache {
        val w = size.width
        val h = size.height
        val radius = minOf(cornerRadius.toPx(), minOf(w, h) / 2f)
        val glowPx = glow.toPx()
        val rect = RectF(0f, 0f, w, h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val corner = CornerRadius(radius, radius)
        val edgePx = 1.dp.toPx()
        val body = Brush.linearGradient(listOf(neo.accentHigh, neo.accentLow), start = Offset.Zero, end = Offset(w, h))
        val sheen = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.40f), Color.Transparent),
            start = Offset.Zero,
            end = Offset(w, h),
        )

        onDrawBehind {
            val p = press().coerceIn(0f, 1f)
            drawIntoCanvas { canvas ->
                val nc = canvas.nativeCanvas
                paint.color = neo.accent.toArgb()
                if (canDrawShadowLayer) {
                    paint.setShadowLayer(glowPx, 0f, glowPx * 0.3f, neo.accent.copy(alpha = 0.34f * (1f - 0.6f * p)).toArgb())
                }
                nc.drawRoundRect(rect, radius, radius, paint)
                paint.clearShadowLayer()
            }
            drawRoundRect(brush = body, cornerRadius = corner)
            drawRoundRect(
                brush = sheen,
                topLeft = Offset(edgePx / 2f, edgePx / 2f),
                size = Size(w - edgePx, h - edgePx),
                cornerRadius = CornerRadius((radius - edgePx / 2f).coerceAtLeast(0f)),
                style = Stroke(width = edgePx),
            )
        }
    }
}

/** Soft translucent stand-in for a blurred shadow on Android 8/8.1, where setShadowLayer is text-only. */
private fun DrawScope.drawFallbackShadow(
    color: Color,
    dx: Float,
    dy: Float,
    spread: Float,
    radius: Float,
    w: Float,
    h: Float,
    strength: Float,
) {
    val steps = 6
    for (i in 0 until steps) {
        val t = (i + 1) / steps.toFloat()
        val grow = spread * t
        drawRoundRect(
            color = color.scaleAlpha(strength * (1f - t) * 0.40f),
            topLeft = Offset(dx - grow, dy - grow),
            size = Size(w + grow * 2f, h + grow * 2f),
            cornerRadius = CornerRadius(radius + grow),
        )
    }
}

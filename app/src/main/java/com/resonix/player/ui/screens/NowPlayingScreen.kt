package com.resonix.player.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.resonix.player.audio.isLosslessFormat
import com.resonix.player.ui.ArtPalette
import com.resonix.player.ui.NowPlayingUiState
import com.resonix.player.ui.components.PlayerMenu
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
import com.resonix.player.ui.neo.neoFloating
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.rememberAlbumArt
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import com.resonix.player.ui.tintFromBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The full-screen player. One structure, two looks:
 *
 *   top bar (close · where from · more)
 *   artwork
 *   title / artist
 *   seek bar + times
 *   transport (previous · play/pause · next)
 *   [volume, with Direct volume control]
 *   output line
 *
 * With [fullFrameArt] on, the cover fills the top of the screen edge to edge and fades into a
 * background tinted from the cover's own colours. Off, the cover sits on the app's raised plate.
 * Both are switched from Settings → Player and from the three-dot menu here.
 */
@Composable
fun NowPlayingScreen(
    state: NowPlayingUiState,
    fullFrameArt: Boolean,
    keepScreenOn: Boolean,
    /** Direct volume control is on: show the in-engine volume slider. */
    showVolume: Boolean,
    /** 0 … 1, the in-engine volume (only meaningful while [showVolume]). */
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    onToggleFullFrameArt: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
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

    // A full-bleed cover needs a tall, narrow window: the controls under it take a fixed amount of
    // room, and in landscape or on a very short screen nothing would be left for the picture. There
    // the framed look is used instead, whatever the setting says (the setting itself is untouched).
    val config = LocalConfiguration.current
    val roomForBleed = config.screenWidthDp.toFloat() <= config.screenHeightDp * BLEED_MAX_ASPECT &&
        config.screenHeightDp >= BLEED_MIN_HEIGHT_DP
    val bleeding = fullFrameArt && roomForBleed

    // The full-frame background is always dark, so the status / navigation bar icons have to be
    // light while it is showing — whatever the app theme says. Handed back when it goes away.
    if (bleeding && !view.isInEditMode) {
        SideEffect { setLightSystemBars(view, light = false) }
    }
    DisposableEffect(bleeding, neo.isDark) {
        onDispose { if (!view.isInEditMode) setLightSystemBars(view, light = !neo.isDark) }
    }

    var menuOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = menuOpen) { menuOpen = false }

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

    val controls = PlayerControls(
        state = state,
        seekFraction = shownFraction,
        seekPositionMs = shownPositionMs,
        onSeekChange = {
            dragging = true
            dragFraction = it
        },
        onSeekFinish = {
            dragging = false
            dragFraction?.let { fraction -> onSeek((fraction * state.durationMs).toLong()) }
            releaseToken++
        },
        showVolume = showVolume,
        volume = volume,
        onVolumeChange = onVolumeChange,
        onPlayPause = onPlayPause,
        onNext = onNext,
        onPrevious = onPrevious,
        onClose = onClose,
        onMore = { menuOpen = true },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            // Swallow touches so nothing underneath this overlay reacts to them.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Crossfade(
            targetState = bleeding,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(260),
            label = "nowPlayingLook",
        ) { full ->
            if (full) FullFrameLayout(controls) else FramedLayout(controls)
        }

        PlayerMenu(
            visible = menuOpen,
            onDismiss = { menuOpen = false },
            fullFrameArt = fullFrameArt,
            keepScreenOn = keepScreenOn,
            onToggleFullFrameArt = onToggleFullFrameArt,
            onToggleKeepScreenOn = onToggleKeepScreenOn,
            onOpenTools = onOpenTools,
        )
    }
}

/** Everything the two layouts need, so each gets the same inputs. */
private class PlayerControls(
    val state: NowPlayingUiState,
    val seekFraction: Float,
    val seekPositionMs: Long,
    val onSeekChange: (Float) -> Unit,
    val onSeekFinish: () -> Unit,
    val showVolume: Boolean,
    val volume: Float,
    val onVolumeChange: (Float) -> Unit,
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onClose: () -> Unit,
    val onMore: () -> Unit,
)

// ──────────────────────────── full-frame look ────────────────────────────

/**
 * The cover runs edge to edge up under the status bar, at most as tall as it is wide — it is never
 * zoomed past that to fill a taller screen, so none of it is cropped away. Its lower part then
 * dissolves into a background tinted from its own colours, and that background carries the rest
 * of the screen.
 *
 * Everything under the cover has a fixed place in one column, top to bottom:
 *
 *     cover ── title · artist · format ── seek bar + times ── prev · play · next ── volume · output
 *
 * The title starts inside the end of the cover's fade, the output line is pinned to the bottom, and
 * the height left over is shared out as even gaps between the groups (each with a smallest and a
 * largest size). Only if a very tall screen still has height to spare does the rest go above the
 * title, as plain background under the cover. The groups are placed by hand ([Layout] below).
 *
 * Three things keep the join between picture and background invisible:
 *  - the page behind is a single gradient, and the cover is faded out *into* it (its own alpha is
 *    masked), rather than a coloured overlay being laid over the cover;
 *  - the fade follows a smootherstep curve cut into many stops, so neither end of it shows as a line;
 *  - the background tint is read from the lower part of the cover, the part the fade passes through.
 */
@Composable
private fun FullFrameLayout(c: PlayerControls) {
    val state = c.state
    val neo = LocalNeoColors.current

    val art by rememberAlbumArt(state.albumArtUri.ifBlank { state.contentUri }, 1024)
    val fallbackTint = remember(neo.accent) { Color(ArtPalette.tintFor(neo.accent.toArgb())) }
    val tint by rememberArtTint(art, fallbackTint)
    val deep = lerp(tint, Color.Black, 0.55f)
    val page = remember(tint, deep) { pageBrush(tint, deep) }

    // How tall the cover is allowed to be, as a share of the screen width: a square cover gets 1.0
    // (shown whole, never zoomed in); a wider one gets less, so it is not cropped at the sides either.
    val coverRatio by animateFloatAsState(
        targetValue = art?.let { (it.height.toFloat() / it.width).coerceIn(COVER_MIN_RATIO, COVER_MAX_RATIO) }
            ?: COVER_MAX_RATIO,
        animationSpec = tween(300),
        label = "coverRatio",
    )

    val primary = Color.White
    val secondary = Color.White.copy(alpha = 0.68f)
    val tertiary = Color.White.copy(alpha = 0.46f)

    val currentOnClose by rememberUpdatedState(c.onClose)

    // One background for the whole screen. The cover fades into exactly this, so there is nothing
    // to match up between the picture and what is under it.
    Layout(
        modifier = Modifier
            .fillMaxSize()
            .background(page),
        content = {
            // ── 1 · the cover, edge to edge, up under the status bar ──
            Box(
                modifier = Modifier
                    // Drag the cover down to close, like a sheet.
                    .pointerInput(Unit) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = {
                                if (total > 140f) currentOnClose()
                                total = 0f
                            },
                            onDragCancel = { total = 0f },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                total += dragAmount
                            },
                        )
                    },
            ) {
                // The picture and the fade. Kept in one layer so the mask only touches the picture.
                Box(modifier = Modifier.fillMaxSize().dissolveBottom(DISSOLVE_FRACTION)) {
                    Crossfade(
                        targetState = art,
                        modifier = Modifier.fillMaxSize(),
                        animationSpec = tween(350),
                        label = "fullFrameArt",
                    ) { shown ->
                        if (shown != null) {
                            Image(
                                bitmap = shown,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                // Only matters for a cover that is not square: keep the top of it,
                                // where titles usually are.
                                alignment = BiasAlignment(0f, -0.4f),
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Brush.verticalGradient(listOf(lerp(tint, Color.White, 0.12f), tint))),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = NeoIcons.Music,
                                    contentDescription = null,
                                    tint = Color.White.copy(alpha = 0.25f),
                                    modifier = Modifier.size(110.dp),
                                )
                            }
                        }
                    }
                }

                // Keeps the status bar and the buttons readable on any cover; eased so it has no edge.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(Brush.verticalGradient(*TopScrimStops)),
                )

                TopBar(
                    c = c,
                    onArt = true,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding(),
                )
            }

            // ── 2 · who is playing: title, artist, and what the file is ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SIDE_MARGIN),
            ) {
                TitleBlock(state, primary, secondary)
                val caption = qualityCaption(state)
                if (caption.isNotBlank()) {
                    Text(
                        text = caption,
                        color = tertiary,
                        style = NeoType.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }

            // ── 3 · where in the song: seek bar, then the times with the quality label between ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SIDE_MARGIN),
            ) {
                FlatSeekBar(
                    value = c.seekFraction,
                    onValueChange = c.onSeekChange,
                    onValueChangeFinished = c.onSeekFinish,
                    enabled = state.durationMs > 0L,
                    activeColor = primary,
                    trackColor = Color.White.copy(alpha = 0.24f),
                )
                Box(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = formatDuration(c.seekPositionMs),
                        color = secondary,
                        style = NeoType.caption,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    QualityBadge(state, secondary, Modifier.align(Alignment.Center))
                    Text(
                        text = "-" + formatDuration((state.durationMs - c.seekPositionMs).coerceAtLeast(0L)),
                        color = secondary,
                        style = NeoType.caption,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            // ── 4 · what to play: previous, play / pause, next ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SIDE_MARGIN),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FlatTransportButton(NeoIcons.Previous, "Previous", c.onPrevious, boxSize = 72.dp, iconSize = 40.dp, tint = primary)
                FlatTransportButton(
                    icon = if (state.isPlaying) NeoIcons.Pause else NeoIcons.Play,
                    description = if (state.isPlaying) "Pause" else "Play",
                    onClick = c.onPlayPause,
                    boxSize = 96.dp,
                    iconSize = 62.dp,
                    tint = primary,
                )
                FlatTransportButton(NeoIcons.Next, "Next", c.onNext, boxSize = 72.dp, iconSize = 40.dp, tint = primary)
            }

            // ── 5 · the foot of the screen: volume (if shown) and where the sound goes ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = SIDE_MARGIN)
                    .padding(bottom = 14.dp),
            ) {
                if (c.showVolume) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(NeoIcons.Volume, contentDescription = "Volume", tint = tertiary, modifier = Modifier.size(18.dp))
                        FlatSeekBar(
                            value = c.volume,
                            onValueChange = c.onVolumeChange,
                            onValueChangeFinished = {},
                            enabled = true,
                            activeColor = primary,
                            trackColor = Color.White.copy(alpha = 0.24f),
                            modifier = Modifier.weight(1f),
                        )
                        Icon(NeoIcons.Volume, contentDescription = null, tint = secondary, modifier = Modifier.size(24.dp))
                    }
                    Spacer(Modifier.height(6.dp))
                }
                OutputLine(state.outputInfo, tertiary)
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight

        // The groups under the cover take the room they need.
        val loose = Constraints(minWidth = width, maxWidth = width, minHeight = 0, maxHeight = height)
        val info = measurables[1].measure(loose)
        val seek = measurables[2].measure(loose)
        val transport = measurables[3].measure(loose)
        val foot = measurables[4].measure(loose)

        // Smallest gap and the most it may grow by, for: info→seek, seek→transport, transport→foot.
        val minGap = intArrayOf(GAP_INFO_SEEK.min.roundToPx(), GAP_SEEK_TRANSPORT.min.roundToPx(), GAP_TRANSPORT_FOOT.min.roundToPx())
        val growBy = intArrayOf(GAP_INFO_SEEK.grow.roundToPx(), GAP_SEEK_TRANSPORT.grow.roundToPx(), GAP_TRANSPORT_FOOT.grow.roundToPx())
        val minTotal = minGap.sum()

        // The cover gets what is left over once those are in, up to its limit.
        val ride = TITLE_RIDE_UP.roundToPx()
        val groups = info.height + seek.height + transport.height + foot.height
        val wanted = (width * coverRatio).roundToInt()
        val room = (height - groups - minTotal + ride).coerceAtLeast(0)
        val coverHeight = minOf(wanted, room)
        val cover = measurables[0].measure(Constraints.fixed(width, coverHeight))

        // Whatever height is still free is shared out between the three gaps, a little more of it
        // above the seek bar. Any beyond what the gaps may take ends up under the cover.
        val free = (height - ((coverHeight - ride) + groups)).coerceAtLeast(0)
        val extra = (free - minTotal).coerceAtLeast(0)
        val share = floatArrayOf(GAP_SHARE[0], GAP_SHARE[1], GAP_SHARE[2])
        val shareSum = share.sum()
        val gap = IntArray(3) { i -> minGap[i] + minOf(growBy[i], (extra * share[i] / shareSum).toInt()) }

        // Stacked up from the bottom edge, so the foot never moves and rounding lands under the cover.
        val footY = height - foot.height
        val transportY = footY - gap[2] - transport.height
        val seekY = transportY - gap[1] - seek.height
        val infoY = (seekY - gap[0] - info.height).coerceAtLeast((coverHeight - ride).coerceAtLeast(0))

        layout(width, height) {
            cover.place(0, 0)
            info.place(0, infoY)
            seek.place(0, seekY)
            transport.place(0, transportY)
            foot.place(0, footY)
        }
    }
}

// ───────── the full-bleed cover's helpers ─────────

/** The window is at most this wide for its height (width ÷ height) for the cover to bleed. */
private const val BLEED_MAX_ASPECT = 0.75f

/** ...and at least this tall (dp), so there is room for the picture above the controls. */
private const val BLEED_MIN_HEIGHT_DP = 560

/** The cover is at most this tall for its width: square, so it is never zoomed in or cropped. */
private const val COVER_MAX_RATIO = 1.0f

/** ...and a wide cover is allowed to be this short at the least, relative to the width. */
private const val COVER_MIN_RATIO = 0.8f

/** The lower share of the cover that dissolves into the background. */
private const val DISSOLVE_FRACTION = 0.40f

/** How far the title starts up inside that fade. */
private val TITLE_RIDE_UP = 36.dp

/** Space at the left and right edge of everything under the cover. */
private val SIDE_MARGIN = 24.dp

/** A gap between two groups: never smaller than [min], and given at most [grow] more than that. */
private class GapRange(val min: Dp, val grow: Dp)

private val GAP_INFO_SEEK = GapRange(min = 16.dp, grow = 56.dp)
private val GAP_SEEK_TRANSPORT = GapRange(min = 8.dp, grow = 40.dp)
private val GAP_TRANSPORT_FOOT = GapRange(min = 8.dp, grow = 40.dp)

/** How the free height is divided between those three gaps (a little more above the seek bar). */
private val GAP_SHARE = floatArrayOf(1.2f, 1f, 1f)

/** Smootherstep: 0 → 1 with zero slope at both ends, so an eased ramp has no visible start or stop. */
private fun smootherStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

/** The cover's alpha along the fade: opaque at the top of it, gone at the bottom. */
private val DissolveStops: Array<Pair<Float, Color>> = Array(13) { i ->
    val t = i / 12f
    t to Color.Black.copy(alpha = 1f - smootherStep(t))
}

/** The darkening behind the status bar and top buttons: strongest at the edge, eased to nothing. */
private val TopScrimStops: Array<Pair<Float, Color>> = Array(9) { i ->
    val t = i / 8f
    t to Color.Black.copy(alpha = 0.55f * (1f - smootherStep(t)))
}

/** The screen's background: the cover's tint down to where the fade ends, then easing to its deep shade. */
private fun pageBrush(tint: Color, deep: Color): Brush {
    val stops = ArrayList<Pair<Float, Color>>(10)
    stops.add(0f to tint)
    for (i in 0..8) {
        val t = i / 8f
        stops.add((0.45f + 0.55f * t) to lerp(tint, deep, smootherStep(t)))
    }
    return Brush.verticalGradient(*stops.toTypedArray())
}

/**
 * Fades the bottom [fraction] of whatever this wraps to transparent, along a smootherstep curve.
 * The fade is a mask on the content's own alpha, which is why it needs a layer of its own.
 */
private fun Modifier.dissolveBottom(fraction: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithCache {
        val mask = Brush.verticalGradient(
            *DissolveStops,
            startY = size.height * (1f - fraction),
            endY = size.height,
        )
        onDrawWithContent {
            drawContent()
            drawRect(brush = mask, blendMode = BlendMode.DstIn)
        }
    }

/**
 * The dominant colour of the lower part of the cover — the part that dissolves into the page — as a
 * dark background tint; eases from one song's to the next.
 */
@Composable
private fun rememberArtTint(art: ImageBitmap?, fallback: Color): State<Color> {
    val target = remember { mutableStateOf(fallback) }
    LaunchedEffect(art, fallback) {
        val argb = if (art == null) {
            null
        } else {
            withContext(Dispatchers.Default) { tintFromBitmap(art.asAndroidBitmap(), fromFraction = 0.45f) }
        }
        target.value = if (argb != null) Color(argb) else fallback
    }
    return animateColorAsState(targetValue = target.value, animationSpec = tween(500), label = "artTint")
}

// ──────────────────────────── framed look ────────────────────────────

@Composable
private fun FramedLayout(c: PlayerControls) {
    val state = c.state
    val neo = LocalNeoColors.current
    val currentOnClose by rememberUpdatedState(c.onClose)

    val artScale by animateFloatAsState(
        targetValue = if (state.isPlaying) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 220f),
        label = "nowPlayingArtScale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .neoBackground()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp),
    ) {
        TopBar(c = c, onArt = false, modifier = Modifier.fillMaxWidth())

        // Album art on its plate — as large as the screen allows.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var total = 0f
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = {
                            if (total > 140f) currentOnClose()
                            total = 0f
                        },
                        onDragCancel = { total = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            total += dragAmount
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val side = (minOf(maxWidth, maxHeight) - 20.dp).coerceAtLeast(120.dp)
            Column(
                modifier = Modifier
                    .size(side)
                    .graphicsLayer {
                        scaleX = artScale
                        scaleY = artScale
                    }
                    .neoFloating(cornerRadius = 28.dp, elevation = 18.dp),
            ) {
                NeoAlbumArt(
                    uri = state.albumArtUri.ifBlank { state.contentUri },
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 28.dp,
                    sizePx = 1024,
                    iconSize = 72.dp,
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        TitleBlock(state, neo.textPrimary, neo.textSecondary)

        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.format.isNotBlank()) NeoQualityPill(state.format.uppercase())
            if (state.bitDepth > 0) NeoQualityPill("${state.bitDepth}-bit")
            if (state.sampleRateHz > 0) {
                val khz = state.sampleRateHz / 1000f
                val khzText = if (khz % 1f == 0f) khz.toInt().toString() else "%.1f".format(Locale.US, khz)
                NeoQualityPill("$khzText kHz")
            }
            if (isHiRes(state.bitDepth, state.sampleRateHz)) NeoHiResTag()
        }

        Spacer(Modifier.height(18.dp))
        NeoSlider(
            value = c.seekFraction,
            onValueChange = c.onSeekChange,
            onValueChangeFinished = c.onSeekFinish,
            enabled = state.durationMs > 0L,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = formatDuration(c.seekPositionMs), color = neo.textSecondary, style = NeoType.caption)
            Text(text = formatDuration(state.durationMs), color = neo.textSecondary, style = NeoType.caption)
        }

        if (c.showVolume) {
            // Direct volume control (Settings → Audio engine): the level inside the engine.
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = NeoIcons.Volume,
                    contentDescription = "Volume",
                    tint = neo.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
                NeoSlider(
                    value = c.volume,
                    onValueChange = c.onVolumeChange,
                    modifier = Modifier.weight(1f),
                    trackHeight = 8.dp,
                    thumbDiameter = 22.dp,
                )
                Text(
                    text = "${(c.volume * 100f).toInt()}%",
                    color = neo.textSecondary,
                    style = NeoType.caption,
                    modifier = Modifier.widthIn(min = 36.dp),
                    textAlign = TextAlign.End,
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(
                icon = NeoIcons.Previous,
                onClick = c.onPrevious,
                diameter = 62.dp,
                iconSize = 28.dp,
                contentDescription = "Previous",
            )
            NeoAccentIconButton(
                icon = if (state.isPlaying) NeoIcons.Pause else NeoIcons.Play,
                onClick = c.onPlayPause,
                diameter = 86.dp,
                iconSize = 40.dp,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
            )
            NeoIconButton(
                icon = NeoIcons.Next,
                onClick = c.onNext,
                diameter = 62.dp,
                iconSize = 28.dp,
                contentDescription = "Next",
            )
        }

        Spacer(Modifier.height(10.dp))
        OutputLine(state.outputInfo, neo.textTertiary)
        Spacer(Modifier.height(14.dp))
    }
}

// ──────────────────────────── shared pieces ────────────────────────────

/** Close · "Playing from …" · more. On the full-frame cover the buttons are dark glass circles. */
@Composable
private fun TopBar(c: PlayerControls, onArt: Boolean, modifier: Modifier = Modifier) {
    val neo = LocalNeoColors.current
    val state = c.state
    val labelColor = if (onArt) Color.White.copy(alpha = 0.70f) else neo.textTertiary
    val nameColor = if (onArt) Color.White else neo.textPrimary
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = if (onArt) 16.dp else 0.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onArt) {
            GlassButton(NeoIcons.Down, "Close player", c.onClose)
        } else {
            NeoIconButton(icon = NeoIcons.Down, onClick = c.onClose, contentDescription = "Close player")
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.source.isNotBlank()) {
                Text(text = "PLAYING FROM", color = labelColor, style = NeoType.tiny, maxLines = 1)
                Text(
                    text = state.source,
                    color = nameColor,
                    style = NeoType.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(text = "NOW PLAYING", color = if (onArt) Color.White else neo.accent, style = NeoType.overline)
            }
        }
        if (onArt) {
            GlassButton(NeoIcons.More, "More options", c.onMore)
        } else {
            NeoIconButton(icon = NeoIcons.More, onClick = c.onMore, contentDescription = "More options")
        }
    }
}

@Composable
private fun GlassButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .size(46.dp)
            .background(Color.Black.copy(alpha = if (pressed) 0.58f else 0.34f), CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TitleBlock(state: NowPlayingUiState, titleColor: Color, artistColor: Color) {
    Text(
        text = state.title.trim().ifBlank { "Unknown title" },
        color = titleColor,
        style = NeoType.title,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(2.dp))
    Text(
        text = state.artist.trim().ifBlank { "Unknown artist" },
        color = artistColor,
        style = NeoType.body,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Hi-Res Lossless" / "Lossless" / the format — the small label centred under the seek bar. */
@Composable
private fun QualityBadge(state: NowPlayingUiState, color: Color, modifier: Modifier = Modifier) {
    val lossless = isLosslessFormat(state.format) || state.format.uppercase(Locale.ROOT) in LOSSLESS_DISPLAY_NAMES
    val label = when {
        lossless && isHiRes(state.bitDepth, state.sampleRateHz) -> "Hi-Res Lossless"
        lossless -> "Lossless"
        else -> state.format.uppercase(Locale.ROOT)
    }
    if (label.isBlank()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(imageVector = NeoIcons.Equalizer, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(text = label, color = color, style = NeoType.caption, maxLines = 1)
    }
}

private val LOSSLESS_DISPLAY_NAMES = setOf("PCM", "DSD", "WAV", "AIFF", "ALAC", "FLAC", "APE", "WAVPACK", "TTA")

/** What the output stream really is (API · sharing · rate), centred at the foot of the screen. */
@Composable
private fun OutputLine(text: String, color: Color) {
    if (text.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(imageVector = NeoIcons.Headphones, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = text, color = color, style = NeoType.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A bare play / skip glyph that shrinks a little while pressed (full-frame look). */
@Composable
private fun FlatTransportButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    boxSize: Dp,
    iconSize: Dp,
    tint: Color,
) {
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "flatTransportScale",
    )
    Box(
        modifier = Modifier
            .size(boxSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = tween(150), label = "flatTransportIcon") { shown ->
            Icon(imageVector = shown, contentDescription = description, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/**
 * A slim, flat seek / volume bar. Tap to jump, drag to scrub; it thickens while held. The gesture
 * handling is the same as NeoSlider's (tap detector outside, drag detector inside).
 */
@Composable
private fun FlatSeekBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    enabled: Boolean,
    activeColor: Color,
    trackColor: Color,
    modifier: Modifier = Modifier,
) {
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinish by rememberUpdatedState(onValueChangeFinished)
    var held by remember { mutableStateOf(false) }
    val thickness by animateDpAsState(
        targetValue = if (held) 10.dp else 6.dp,
        animationSpec = tween(120),
        label = "flatSeekThickness",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures { offset ->
                        currentOnChange((offset.x / size.width).coerceIn(0f, 1f))
                        currentOnFinish()
                    }
                }
            }
            .pointerInput(enabled) {
                if (enabled) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            held = true
                            currentOnChange((offset.x / size.width).coerceIn(0f, 1f))
                        },
                        onDragEnd = {
                            held = false
                            currentOnFinish()
                        },
                        onDragCancel = {
                            held = false
                            currentOnFinish()
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            currentOnChange((change.position.x / size.width).coerceIn(0f, 1f))
                        },
                    )
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thickness)
                .clip(CircleShape)
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = value.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(if (enabled) activeColor else activeColor.copy(alpha = 0.4f)),
            )
        }
    }
}

// ──────────────────────────── helpers ────────────────────────────

/** "FLAC · 24-bit · 48.0 kHz · 1780 kbps · Stereo" */
private fun qualityCaption(state: NowPlayingUiState): String {
    val parts = ArrayList<String>(5)
    if (state.format.isNotBlank()) parts.add(state.format.uppercase(Locale.ROOT))
    if (state.bitDepth > 0) parts.add("${state.bitDepth}-bit")
    if (state.sampleRateHz > 0) parts.add(String.format(Locale.US, "%.1f kHz", state.sampleRateHz / 1000f))
    if (state.bitrateKbps > 0) parts.add("${state.bitrateKbps} kbps")
    when (state.channelCount) {
        0 -> Unit
        1 -> parts.add("Mono")
        2 -> parts.add("Stereo")
        else -> parts.add("${state.channelCount} ch")
    }
    return parts.joinToString(" · ")
}

/** Light or dark status / navigation bar icons, on whichever window hosts [view]. */
private fun setLightSystemBars(view: View, light: Boolean) {
    var context: Context = view.context
    while (context is ContextWrapper) {
        if (context is Activity) {
            val controller = WindowCompat.getInsetsController(context.window, view)
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = light
            return
        }
        context = context.baseContext
    }
}

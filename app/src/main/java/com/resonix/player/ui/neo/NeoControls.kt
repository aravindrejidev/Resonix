package com.resonix.player.ui.neo

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resonix.player.ui.rememberAlbumArt
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.LocalNeoHaptics
import com.resonix.player.ui.theme.NeoType
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Lets a horizontally-scrolling row use [amount] of the padding on each side of its parent, so the
 * soft shadows of the first / last item aren't cut off by the row's own clip bounds. Pair it with
 * the same amount of extra `contentPadding`.
 */
fun Modifier.bleedHorizontally(amount: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val width = constraints.maxWidth + extra * 2
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(constraints.maxWidth, placeable.height) {
        placeable.place(-extra, 0)
    }
}

// ───────────────────────────── feedback ─────────────────────────────

/** A tiny haptic tick, honouring Settings → Appearance → Haptic feedback. */
@Composable
fun rememberHaptic(): () -> Unit {
    val enabled = LocalNeoHaptics.current
    val feedback = LocalHapticFeedback.current
    return remember(enabled, feedback) {
        val tick: () -> Unit = {
            if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        tick
    }
}

// ───────────────────────────── buttons ─────────────────────────────

/** Round flat button. Shrinks a little while held; [toggled] tints it with the accent colour. */
@Composable
fun NeoIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 46.dp,
    iconSize: Dp = 22.dp,
    contentDescription: String? = null,
    tint: Color = Color.Unspecified,
    toggled: Boolean = false,
    enabled: Boolean = true,
) {
    val neo = LocalNeoColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press = animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "neoIconPress",
    )
    val fill by animateColorAsState(
        targetValue = when {
            toggled -> neo.accent.copy(alpha = 0.16f)
            pressed && enabled -> neo.well
            else -> neo.card
        },
        label = "neoIconFill",
    )
    val haptic = rememberHaptic()
    val iconColor = when {
        !enabled -> neo.textTertiary
        tint != Color.Unspecified -> tint
        toggled -> neo.accent
        else -> neo.textSecondary
    }
    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                val scale = 1f - 0.06f * press.value
                scaleX = scale
                scaleY = scale
            }
            .background(fill, CircleShape)
            .border(1.dp, if (toggled) neo.accent.copy(alpha = 0.45f) else neo.outline, CircleShape)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) {
                haptic()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = tween(150), label = "neoIconSwap") { shown ->
            Icon(
                imageVector = shown,
                contentDescription = contentDescription,
                tint = iconColor,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Big round accent button with a glow — the play / pause button. */
@Composable
fun NeoAccentIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 76.dp,
    iconSize: Dp = 34.dp,
    contentDescription: String? = null,
) {
    val neo = LocalNeoColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 600f),
        label = "neoAccentPress",
    )
    val haptic = rememberHaptic()
    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                val s = 1f - 0.05f * press.value
                scaleX = s
                scaleY = s
            }
            .neoAccentSurface(cornerRadius = diameter / 2, glow = diameter * 0.14f, press = { press.value })
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = tween(150), label = "neoAccentIconSwap") { shown ->
            Icon(
                imageVector = shown,
                contentDescription = contentDescription,
                tint = neo.onAccent,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Pill button with optional icon. [accent] makes it the glowing primary action. */
@Composable
fun NeoPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Boolean = false,
    enabled: Boolean = true,
    buttonHeight: Dp = 48.dp,
) {
    val neo = LocalNeoColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press = animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 600f),
        label = "neoPillPress",
    )
    val haptic = rememberHaptic()
    val contentColor = when {
        !enabled -> neo.textTertiary
        accent -> neo.onAccent
        else -> neo.textPrimary
    }
    val surface = if (accent && enabled) {
        Modifier.neoAccentSurface(cornerRadius = buttonHeight / 2, glow = 7.dp, press = { press.value })
    } else {
        Modifier.neoFlat(
            cornerRadius = buttonHeight / 2,
            fill = if (pressed && enabled) neo.well else Color.Unspecified,
        )
    }
    Row(
        modifier = modifier
            .height(buttonHeight)
            .graphicsLayer {
                val s = 1f - 0.03f * press.value
                scaleX = s
                scaleY = s
            }
            .then(surface)
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text = text, color = contentColor, style = NeoType.body, maxLines = 1)
    }
}

// ───────────────────────────── switch / segmented / chip ─────────────────────────────

/** Flat track that fills with the accent colour when on, with a small thumb that slides across. */
@Composable
fun NeoSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val neo = LocalNeoColors.current
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val progress = animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 500f),
        label = "neoSwitch",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) neo.accent else neo.well,
        label = "neoSwitchTrack",
    )
    val thumbColor by animateColorAsState(
        targetValue = if (checked) Color.White else if (neo.isDark) Color(0xFF8A93A6) else Color.White,
        label = "neoSwitchThumb",
    )
    val travel = with(LocalDensity.current) { 22.dp.toPx() }
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 30.dp)
            .background(trackColor, RoundedCornerShape(15.dp))
            .border(1.dp, if (checked) Color.Transparent else neo.outline, RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Switch) {
                haptic()
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset((progress.value * travel).roundToInt(), 0) }
                .padding(4.dp)
                .size(22.dp)
                .neoThumb(cornerRadius = 11.dp, elevation = 2.dp, fill = thumbColor),
        )
    }
}

/** Flat track with a small raised thumb that slides under the selected option. */
@Composable
fun NeoSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segmentHeight: Dp = 42.dp,
) {
    val neo = LocalNeoColors.current
    val haptic = rememberHaptic()
    val density = LocalDensity.current
    var widthPx by remember { mutableIntStateOf(0) }
    val pad = 4.dp
    val index = animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 450f),
        label = "neoSegmented",
    )
    val cellWidthPx = if (options.isEmpty()) 0f else (widthPx - with(density) { pad.toPx() * 2f }) / options.size

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(segmentHeight)
            .neoWell(cornerRadius = segmentHeight / 2)
            .onSizeChanged { widthPx = it.width }
            .clip(RoundedCornerShape(segmentHeight / 2))
            .padding(pad),
    ) {
        if (cellWidthPx > 0f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset((index.value * cellWidthPx).roundToInt(), 0) }
                    .width(with(density) { cellWidthPx.toDp() })
                    .fillMaxHeight()
                    .neoThumb(cornerRadius = (segmentHeight - pad * 2) / 2, elevation = 2.dp),
            )
        }
        Row(modifier = Modifier.fillMaxSize()) {
            options.forEachIndexed { i, label ->
                val selected = i == selectedIndex
                val textColor by animateColorAsState(
                    targetValue = if (selected) neo.accent else neo.textSecondary,
                    label = "neoSegmentedText",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            role = Role.Tab,
                        ) {
                            if (!selected) {
                                haptic()
                                onSelect(i)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = textColor,
                        style = NeoType.caption.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Small pill — flat when idle, tinted with the accent colour when [selected]. */
@Composable
fun NeoChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val neo = LocalNeoColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val fill by animateColorAsState(
        targetValue = when {
            selected -> neo.accent.copy(alpha = 0.16f)
            pressed -> neo.well
            else -> neo.card
        },
        label = "neoChipFill",
    )
    val edge by animateColorAsState(
        targetValue = if (selected) neo.accent.copy(alpha = 0.55f) else neo.outline,
        label = "neoChipEdge",
    )
    val contentColor = if (selected) neo.accent else neo.textSecondary
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = modifier
            .height(36.dp)
            .background(fill, shape)
            .border(1.dp, edge, shape)
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(16.dp))
        }
        Text(
            text = text,
            color = contentColor,
            style = NeoType.caption.copy(fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
    }
}

// ───────────────────────────── containers ─────────────────────────────

/**
 * Flat card — the building block of the Tools and Settings screens. A lighter fill and a hairline
 * edge, no shadow. [elevation] is kept so existing callers still compile; it has no effect.
 */
@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    @Suppress("UNUSED_PARAMETER") elevation: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .neoFlat(cornerRadius = cornerRadius)
            .padding(contentPadding),
        content = content,
    )
}

/** Flat recessed display area (EQ screen, wells). [elevation] has no effect; kept for old callers. */
@Composable
fun NeoInsetPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    @Suppress("UNUSED_PARAMETER") elevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier.neoWell(cornerRadius = cornerRadius, border = true)) {
        content()
    }
}

/** Small caps section label. */
@Composable
fun NeoSectionLabel(text: String, modifier: Modifier = Modifier) {
    val neo = LocalNeoColors.current
    Text(
        text = text.uppercase(),
        color = neo.textTertiary,
        style = NeoType.overline,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

/** A thin rule between rows. */
@Composable
fun NeoDivider(modifier: Modifier = Modifier) {
    val neo = LocalNeoColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(neo.divider),
    )
}

/**
 * Screen backdrop: the one flat surface colour. (Neumorphism only works when every
 * surface *and* the background are the same colour — a gradient here shows up as a
 * visible seam under the fade overlays at the top and bottom of the lists.)
 */
@Composable
fun Modifier.neoBackground(): Modifier {
    val neo = LocalNeoColors.current
    return this.background(neo.surface)
}

// ───────────────────────────── small pieces ─────────────────────────────

/** Soft tinted tile holding an accent-coloured icon — leading icon of every settings row. */
@Composable
fun NeoIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    diameter: Dp = 42.dp,
    tint: Color = Color.Unspecified,
) {
    val neo = LocalNeoColors.current
    val iconTint = if (tint == Color.Unspecified) neo.accent else tint
    Box(
        modifier = modifier
            .size(diameter)
            .background(iconTint.copy(alpha = 0.13f), RoundedCornerShape(diameter * 0.32f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

/** "FLAC · 24-bit / 96 kHz" in a tiny inset capsule. */
@Composable
fun NeoQualityPill(text: String, modifier: Modifier = Modifier) {
    val neo = LocalNeoColors.current
    Box(
        modifier = modifier
            .height(20.dp)
            .background(neo.well, RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = neo.textSecondary, style = NeoType.tiny, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Accent tag for anything above CD quality. */
@Composable
fun NeoHiResTag(modifier: Modifier = Modifier) {
    val neo = LocalNeoColors.current
    Box(
        modifier = modifier
            .height(20.dp)
            .background(neo.accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "HI-RES",
            color = neo.accent,
            style = NeoType.tiny.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp),
            maxLines = 1,
        )
    }
}

/** Three bouncing bars — marks the track that is playing. */
@Composable
fun NeoEqBars(playing: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "neoEqBars")
    val a by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(520, easing = LinearEasing), RepeatMode.Reverse),
        label = "barA",
    )
    val b by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(410, easing = LinearEasing), RepeatMode.Reverse),
        label = "barB",
    )
    val c by transition.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(tween(640, easing = LinearEasing), RepeatMode.Reverse),
        label = "barC",
    )
    Box(
        modifier = modifier
            .size(width = 18.dp, height = 16.dp)
            .drawBehind {
                val barWidth = size.width / 5f
                val heights = if (playing) floatArrayOf(a, b, c) else floatArrayOf(0.35f, 0.60f, 0.45f)
                for (i in 0 until 3) {
                    val barHeight = size.height * heights[i]
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(barWidth * (i * 2f), size.height - barHeight),
                        size = Size(barWidth, barHeight),
                        cornerRadius = CornerRadius(barWidth / 2f),
                    )
                }
            },
    )
}

/** Indeterminate spinner: a faint ring with an accent arc chasing round it. */
@Composable
fun NeoLoader(modifier: Modifier = Modifier, diameter: Dp = 26.dp) {
    val neo = LocalNeoColors.current
    val transition = rememberInfiniteTransition(label = "neoLoader")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle",
    )
    Box(
        modifier = modifier
            .size(diameter)
            .drawBehind {
                val stroke = 3.dp.toPx()
                val arcTopLeft = Offset(stroke / 2f, stroke / 2f)
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = neo.textTertiary.copy(alpha = 0.25f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                drawArc(
                    color = neo.accent,
                    startAngle = angle,
                    sweepAngle = 100f,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            },
    )
}

/**
 * Album art clipped to a rounded square, with a neumorphic music-note
 * placeholder while loading / when the file has no embedded art.
 */
@Composable
fun NeoAlbumArt(
    uri: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 14.dp,
    sizePx: Int = 192,
    iconSize: Dp = 24.dp,
) {
    val neo = LocalNeoColors.current
    val art by rememberAlbumArt(uri, sizePx)
    Box(
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = art, animationSpec = tween(220), label = "neoAlbumArt") { bitmap ->
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(neo.lit(neo.surface), neo.shaded(neo.surface)),
                                start = Offset.Zero,
                                end = Offset.Infinite,
                            ),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = NeoIcons.Music,
                        contentDescription = null,
                        tint = neo.textTertiary,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

// ───────────────────────────── search field ─────────────────────────────

/** Flat pill search field with a clear button. */
@Composable
fun NeoSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search",
    autoFocus: Boolean = false,
) {
    val neo = LocalNeoColors.current
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            delay(200)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                // The field wasn't attached yet — the user can still tap it.
            }
        }
    }
    Row(
        modifier = modifier
            .height(48.dp)
            .neoWell(cornerRadius = 24.dp, border = true)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = NeoIcons.Search,
            contentDescription = null,
            tint = neo.textTertiary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = NeoType.body.copy(color = neo.textPrimary),
            cursorBrush = SolidColor(neo.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(text = placeholder, color = neo.textTertiary, style = NeoType.body, maxLines = 1)
                    }
                    inner()
                }
            },
        )
        AnimatedVisibility(visible = query.isNotEmpty()) {
            Icon(
                imageVector = NeoIcons.Close,
                contentDescription = "Clear",
                tint = neo.textSecondary,
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onQueryChange("") }
                    .padding(2.dp),
            )
        }
    }
}

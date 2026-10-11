package com.resonix.player.ui.screens

import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.EQ_BAND_COUNT
import com.resonix.player.ui.NowPlayingUiState
import com.resonix.player.ui.SettingsState
import com.resonix.player.ui.SleepTimerState
import com.resonix.player.ui.neo.NeoCard
import com.resonix.player.ui.neo.NeoChip
import com.resonix.player.ui.neo.NeoHiResTag
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoInsetPanel
import com.resonix.player.ui.neo.NeoKnob
import com.resonix.player.ui.neo.NeoSlider
import com.resonix.player.ui.neo.NeoSwitch
import com.resonix.player.ui.neo.NeoVerticalSlider
import com.resonix.player.ui.neo.bleedHorizontally
import com.resonix.player.ui.neo.formatDuration
import com.resonix.player.ui.neo.isHiRes
import com.resonix.player.ui.neo.neoInset
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private class EqPreset(val name: String, val gains: List<Float>)

private val EqPresets = listOf(
    EqPreset("Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Bass Boost", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqPreset("Treble Boost", listOf(0f, 0f, 0f, 0f, 0f, 1f, 2f, 4f, 5f, 6f)),
    EqPreset("Vocal", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 1f, 0f, -1f)),
    EqPreset("Rock", listOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 5f)),
    EqPreset("Pop", listOf(-1f, 2f, 4f, 5f, 3f, 0f, -1f, -1f, -2f, -2f)),
    EqPreset("Jazz", listOf(3f, 2f, 1f, 2f, -2f, -2f, 0f, 1f, 2f, 3f)),
    EqPreset("Classical", listOf(4f, 3f, 3f, 2f, -1f, -1f, 0f, 2f, 3f, 4f)),
    EqPreset("Electronic", listOf(5f, 4f, 1f, 0f, -2f, 2f, 1f, 2f, 4f, 5f)),
)

private val BandLabels = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

private val SleepChoices = listOf(0, 15, 30, 45, 60, 90)

/** −12 … +12 in half-dB steps. */
private fun snapDb(raw: Float): Float = (raw * 2f).roundToInt() / 2f

private fun formatDb(gain: Float): String {
    val body = if (gain % 1f == 0f) gain.toInt().toString() else String.format(Locale.US, "%.1f", gain)
    return if (gain > 0f) "+$body" else body
}

/** Big screen title shared by Tools and Settings. */
@Composable
internal fun ScreenHeader(title: String, subtitle: String, modifier: Modifier = Modifier) {
    val neo = LocalNeoColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 6.dp),
    ) {
        Text(text = "RESONIX", color = neo.accent, style = NeoType.overline)
        Text(text = title, color = neo.textPrimary, style = NeoType.display)
        Text(text = subtitle, color = neo.textSecondary, style = NeoType.caption)
    }
}

/**
 * Tools: the equaliser and the other sound / playback controls.
 *
 * The equaliser, preamp, bass, width and balance are an interface only for now — they are saved
 * in [SettingsState] and shown, but the audio engine does not read them yet. The sleep timer and
 * the signal card are real.
 */
@Composable
fun ToolsScreen(
    settings: SettingsState,
    nowPlaying: NowPlayingUiState,
    sleepTimer: SleepTimerState?,
    scrollState: ScrollState,
    bottomInset: Dp,
    onSettingsChange: ((SettingsState) -> SettingsState) -> Unit,
    onSleepTimer: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenHeader(title = "Tools", subtitle = "Shape your sound")
        EqualizerCard(settings = settings, onChange = onSettingsChange)
        SoundCard(settings = settings, onChange = onSettingsChange)
        SleepTimerCard(timer = sleepTimer, onSelect = onSleepTimer)
        SignalCard(state = nowPlaying)
    }
}

// ───────────────────────────── equaliser ─────────────────────────────

@Composable
private fun EqualizerCard(settings: SettingsState, onChange: ((SettingsState) -> SettingsState) -> Unit) {
    val neo = LocalNeoColors.current
    var dragging by remember { mutableStateOf(false) }

    // Bands glide to a preset's values, but follow the finger exactly while dragging.
    val shown = settings.eqBands.mapIndexed { index, gain ->
        animateFloatAsState(
            targetValue = gain,
            animationSpec = if (dragging) snap<Float>() else spring<Float>(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "eqBand$index",
        ).value
    }

    fun setBand(index: Int, gain: Float) {
        onChange { s ->
            val bands = s.eqBands.toMutableList()
            bands[index] = gain
            s.copy(eqBands = bands, eqPreset = "Custom")
        }
    }

    NeoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconBadge(icon = NeoIcons.Equalizer, diameter = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Equalizer", color = neo.textPrimary, style = NeoType.heading)
                    Spacer(Modifier.width(8.dp))
                    PreviewTag()
                }
                Text(
                    text = if (settings.eqEnabled) settings.eqPreset else "Off",
                    color = neo.textSecondary,
                    style = NeoType.caption,
                )
            }
            NeoSwitch(
                checked = settings.eqEnabled,
                onCheckedChange = { on -> onChange { it.copy(eqEnabled = on) } },
            )
        }

        Spacer(Modifier.height(16.dp))
        EqDisplay(bands = shown, active = settings.eqEnabled)

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(176.dp),
        ) {
            for (index in 0 until EQ_BAND_COUNT) {
                val gain = shown[index]
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = formatDb(snapDb(gain)),
                        color = if (abs(gain) > 0.25f) neo.accent else neo.textTertiary,
                        style = NeoType.tiny,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    NeoVerticalSlider(
                        value = (gain + 12f) / 24f,
                        onValueChange = { v -> setBand(index, snapDb(v * 24f - 12f)) },
                        onDragStateChange = { dragging = it },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(text = BandLabels[index], color = neo.textTertiary, style = NeoType.tiny, maxLines = 1)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        LazyRow(
            modifier = Modifier.bleedHorizontally(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        ) {
            if (settings.eqPreset == "Custom") {
                item(key = "custom") {
                    NeoChip(text = "Custom", selected = true, onClick = {})
                }
            }
            items(EqPresets, key = { it.name }) { preset ->
                NeoChip(
                    text = preset.name,
                    selected = settings.eqPreset == preset.name,
                    onClick = {
                        onChange { it.copy(eqPreset = preset.name, eqBands = preset.gains, eqEnabled = true) }
                    },
                )
            }
        }
    }
}

@Composable
private fun PreviewTag() {
    val neo = LocalNeoColors.current
    Box(
        modifier = Modifier
            .neoInset(cornerRadius = 9.dp, elevation = 2.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = "PREVIEW", color = neo.accent, style = NeoType.tiny.copy(fontWeight = FontWeight.ExtraBold))
    }
}

/** The recessed "screen" that draws the frequency response through the ten bands. */
@Composable
private fun EqDisplay(bands: List<Float>, active: Boolean) {
    val neo = LocalNeoColors.current
    val lineColor = if (active) neo.accent else neo.textTertiary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(116.dp)
            .neoInset(cornerRadius = 22.dp, elevation = 6.dp)
            .drawBehind {
                val w = size.width
                val h = size.height
                val padV = 18.dp.toPx()
                val midY = h / 2f
                val rangeY = h / 2f - padV
                val n = bands.size
                val xs = FloatArray(n) { w * (it + 0.5f) / n }
                val ys = FloatArray(n) { midY - (bands[it].coerceIn(-12f, 12f) / 12f) * rangeY }

                // Grid: one hairline per band, a brighter one at 0 dB.
                for (i in 0 until n) {
                    drawLine(
                        color = neo.textTertiary.copy(alpha = 0.10f),
                        start = Offset(xs[i], padV * 0.5f),
                        end = Offset(xs[i], h - padV * 0.5f),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
                drawLine(
                    color = neo.textTertiary.copy(alpha = 0.32f),
                    start = Offset(0f, midY),
                    end = Offset(w, midY),
                    strokeWidth = 1.dp.toPx(),
                )

                // Smooth curve through the band points (Catmull-Rom → cubic Bézier).
                val curve = Path()
                curve.moveTo(xs[0], ys[0])
                for (i in 0 until n - 1) {
                    val i0 = maxOf(i - 1, 0)
                    val i3 = minOf(i + 2, n - 1)
                    val c1x = xs[i] + (xs[i + 1] - xs[i0]) / 6f
                    val c1y = ys[i] + (ys[i + 1] - ys[i0]) / 6f
                    val c2x = xs[i + 1] - (xs[i3] - xs[i]) / 6f
                    val c2y = ys[i + 1] - (ys[i3] - ys[i]) / 6f
                    curve.cubicTo(c1x, c1y, c2x, c2y, xs[i + 1], ys[i + 1])
                }

                val area = Path()
                area.addPath(curve)
                area.lineTo(xs[n - 1], h)
                area.lineTo(xs[0], h)
                area.close()
                drawPath(
                    path = area,
                    brush = Brush.verticalGradient(
                        colors = listOf(lineColor.copy(alpha = 0.22f), Color.Transparent),
                        startY = 0f,
                        endY = h,
                    ),
                )

                // Faux glow: the same stroke three times, wide + faint to thin + solid.
                drawPath(curve, lineColor.copy(alpha = 0.10f), style = Stroke(10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(curve, lineColor.copy(alpha = 0.20f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(curve, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                for (i in 0 until n) {
                    drawCircle(color = lineColor, radius = 3.dp.toPx(), center = Offset(xs[i], ys[i]))
                }
            },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 10.dp, top = 6.dp, bottom = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            Text(text = "+12 dB", color = neo.textTertiary.copy(alpha = 0.8f), style = NeoType.tiny)
            Text(text = "0", color = neo.textTertiary.copy(alpha = 0.8f), style = NeoType.tiny)
            Text(text = "−12 dB", color = neo.textTertiary.copy(alpha = 0.8f), style = NeoType.tiny)
        }
    }
}

// ───────────────────────────── sound shaping ─────────────────────────────

@Composable
private fun SoundCard(settings: SettingsState, onChange: ((SettingsState) -> SettingsState) -> Unit) {
    val neo = LocalNeoColors.current
    val balanceText = when {
        settings.balance == 0f -> "Center"
        settings.balance < 0f -> "L ${(-settings.balance * 100f).roundToInt()}%"
        else -> "R ${(settings.balance * 100f).roundToInt()}%"
    }
    NeoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconBadge(icon = NeoIcons.Surround, diameter = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Sound shaping", color = neo.textPrimary, style = NeoType.heading)
                    Spacer(Modifier.width(8.dp))
                    PreviewTag()
                }
                Text(text = "Drag a knob up or down to turn it", color = neo.textSecondary, style = NeoType.caption)
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NeoKnob(
                value = (settings.preamp + 12f) / 24f,
                onValueChange = { v -> onChange { it.copy(preamp = snapDb(v * 24f - 12f)) } },
                label = "Preamp",
                valueText = "${formatDb(settings.preamp)} dB",
            )
            NeoKnob(
                value = settings.bassBoost,
                onValueChange = { v -> onChange { it.copy(bassBoost = (v * 20f).roundToInt() / 20f) } },
                label = "Bass boost",
                valueText = "${(settings.bassBoost * 100f).roundToInt()}%",
            )
            NeoKnob(
                value = settings.stereoWidth,
                onValueChange = { v -> onChange { it.copy(stereoWidth = (v * 20f).roundToInt() / 20f) } },
                label = "Stereo width",
                valueText = "${(settings.stereoWidth * 200f).roundToInt()}%",
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Balance",
                color = neo.textPrimary,
                style = NeoType.body,
                modifier = Modifier.weight(1f),
            )
            Text(text = balanceText, color = neo.accent, style = NeoType.caption.copy(fontWeight = FontWeight.Bold))
        }
        NeoSlider(
            value = (settings.balance + 1f) / 2f,
            onValueChange = { v ->
                val raw = v * 2f - 1f
                // A little detent in the middle so "centred" is easy to hit.
                val snapped = if (abs(raw) < 0.05f) 0f else (raw * 20f).roundToInt() / 20f
                onChange { it.copy(balance = snapped) }
            },
            centered = true,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = "L", color = neo.textTertiary, style = NeoType.tiny)
            Text(text = "R", color = neo.textTertiary, style = NeoType.tiny)
        }
    }
}

// ───────────────────────────── sleep timer ─────────────────────────────

@Composable
private fun SleepTimerCard(timer: SleepTimerState?, onSelect: (Int) -> Unit) {
    val neo = LocalNeoColors.current
    val remainingMs by produceState(initialValue = 0L, key1 = timer) {
        val running = timer
        while (running != null) {
            value = (running.endsAtElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            delay(1000)
        }
        value = 0L
    }
    NeoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconBadge(icon = NeoIcons.Sleep, diameter = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Sleep timer", color = neo.textPrimary, style = NeoType.heading)
                Text(
                    text = if (timer != null) "Pauses in ${formatDuration(remainingMs)}" else "Pause playback automatically",
                    color = if (timer != null) neo.accent else neo.textSecondary,
                    style = NeoType.caption,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(
            modifier = Modifier.bleedHorizontally(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
        ) {
            items(SleepChoices, key = { it }) { minutes ->
                NeoChip(
                    text = if (minutes == 0) "Off" else "$minutes min",
                    selected = (timer?.totalMinutes ?: 0) == minutes,
                    onClick = { onSelect(minutes) },
                )
            }
        }
    }
}

// ───────────────────────────── signal info ─────────────────────────────

@Composable
private fun SignalCard(state: NowPlayingUiState) {
    val neo = LocalNeoColors.current
    NeoCard(modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconBadge(icon = NeoIcons.HiRes, diameter = 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Signal", color = neo.textPrimary, style = NeoType.heading)
                Text(
                    text = if (state.hasTrack) state.title.ifBlank { "Unknown title" } else "Nothing playing",
                    color = neo.textSecondary,
                    style = NeoType.caption,
                    maxLines = 1,
                )
            }
            if (state.hasTrack && isHiRes(state.bitDepth, state.sampleRateHz)) NeoHiResTag()
        }
        Spacer(Modifier.height(16.dp))
        NeoInsetPanel(modifier = Modifier.fillMaxWidth(), cornerRadius = 20.dp, elevation = 5.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                val khz = state.sampleRateHz / 1000f
                val rate = when {
                    !state.hasTrack || state.sampleRateHz <= 0 -> "—"
                    khz % 1f == 0f -> "${khz.toInt()} kHz"
                    else -> String.format(Locale.US, "%.1f kHz", khz)
                }
                SignalCell("FORMAT", if (state.hasTrack && state.format.isNotBlank()) state.format.uppercase() else "—", Modifier.weight(1f))
                SignalCell("BIT DEPTH", if (state.hasTrack && state.bitDepth > 0) "${state.bitDepth}-bit" else "—", Modifier.weight(1f))
                SignalCell("SAMPLE RATE", rate, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SignalCell(label: String, value: String, modifier: Modifier = Modifier) {
    val neo = LocalNeoColors.current
    Column(modifier = modifier) {
        Text(text = label, color = neo.textTertiary, style = NeoType.tiny)
        Spacer(Modifier.height(4.dp))
        Text(text = value, color = neo.textPrimary, style = NeoType.body, maxLines = 1)
    }
}

package com.resonix.player.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.resonix.player.audio.EngineBand
import com.resonix.player.audio.EqCodec
import com.resonix.player.audio.EqLayout
import com.resonix.player.audio.EqLimits
import com.resonix.player.audio.EqMath
import com.resonix.player.audio.PeqBand
import com.resonix.player.audio.PeqType
import com.resonix.player.audio.defaultPeqBands
import com.resonix.player.audio.formatFreq
import com.resonix.player.audio.formatGain
import com.resonix.player.audio.formatQ
import com.resonix.player.audio.fractionToFreq
import com.resonix.player.audio.fractionToQ
import com.resonix.player.audio.freqToFraction
import com.resonix.player.audio.parseFreqInput
import com.resonix.player.audio.parseNumberInput
import com.resonix.player.audio.qToFraction
import com.resonix.player.audio.suggestNewBandFreq
import com.resonix.player.ui.SettingsState
import com.resonix.player.ui.neo.NeoChip
import com.resonix.player.ui.neo.NeoInsetPanel
import com.resonix.player.ui.neo.NeoSlider
import com.resonix.player.ui.neo.NeoSwitch
import com.resonix.player.ui.neo.bleedHorizontally
import com.resonix.player.ui.neo.neoFlat
import com.resonix.player.ui.neo.neoWell
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import com.resonix.player.util.AppMessages
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

private val GraphFrequencies = DoubleArray(181) { 20.0 * 1000.0.pow(it / 180.0) }

/**
 * "Auto headroom" switch with a line saying how much it is turning the equalizer down. Used by
 * both equalizer cards — it is one setting for the whole equalizer stage.
 */
@Composable
fun EqHeadroomRow(
    settings: SettingsState,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val headroom = remember(settings.eqEnabled, settings.eqBands, settings.peqEnabled, settings.peqBands) {
        EqMath.peakGainDb(EqLayout.build(settings.eqEnabled, settings.eqBands, settings.peqEnabled, settings.peqBands))
    }
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Auto headroom", color = neo.textPrimary, style = NeoType.body)
            Text(
                text = when {
                    !settings.eqAutoHeadroom -> "Off — a large boost can clip"
                    headroom > 0.05 -> "Turned down by ${String.format(java.util.Locale.US, "%.1f", headroom)} dB so boosts can't clip"
                    else -> "Nothing to make room for"
                },
                color = neo.textSecondary,
                style = NeoType.caption,
            )
        }
        NeoSwitch(
            checked = settings.eqAutoHeadroom,
            onCheckedChange = { on -> onChange { it.copy(eqAutoHeadroom = on) } },
        )
    }
}

/**
 * The parametric equalizer's controls: up to 16 bands, each a peak, shelf, pass or notch filter
 * with exact frequency, gain and Q. It is the "Parametric" page of the Equalizer card on the Tools
 * screen — the card, its switch and the headroom row live there. Only one of the two equalizers is
 * on at a time (see AppSettings.update).
 */
@Composable
fun ParametricEqBody(
    settings: SettingsState,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val context = LocalContext.current
    val bands = settings.peqBands
    var selectedRaw by rememberSaveable { mutableStateOf(0) }
    val selected = selectedRaw.coerceIn(0, max(0, bands.size - 1))

    fun updateBand(index: Int, band: PeqBand) {
        onChange { s ->
            val list = s.peqBands.toMutableList()
            if (index in list.indices) list[index] = band
            s.copy(peqBands = list)
        }
    }

    // The curve of just these bands; drawn dimmed while the equalizer is off.
    val curveBands = remember(bands) {
        EqLayout.build(false, settings.eqBands, true, bands)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        PeqGraph(
            curveBands = curveBands,
            nodes = bands,
            selected = selected,
            active = settings.peqEnabled,
            onSelect = { selectedRaw = it },
            onMove = { index, freqHz, gainDb ->
                val b = bands.getOrNull(index)
                if (b != null) {
                    updateBand(
                        index,
                        b.copy(
                            freqHz = roundedFreq(freqHz),
                            gainDb = if (b.type.hasGain) EqLimits.gain((gainDb * 10f).roundToInt() / 10f) else b.gainDb,
                        )
                    )
                }
            },
        )
        FrequencyAxis()

        Spacer(Modifier.height(10.dp))
        LazyRow(
            modifier = Modifier.bleedHorizontally(18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 18.dp),
        ) {
            itemsIndexed(bands) { index, band ->
                NeoChip(
                    text = "${index + 1} · ${formatFreq(band.freqHz)}",
                    selected = index == selected,
                    onClick = { selectedRaw = index },
                )
            }
            if (bands.size < EqLimits.MAX_PEQ_BANDS) {
                item(key = "add") {
                    NeoChip(
                        text = "+ Add band",
                        selected = false,
                        onClick = {
                            val fresh = PeqBand(PeqType.PEAK, suggestNewBandFreq(bands), 0f, 1f)
                            onChange { it.copy(peqBands = it.peqBands + fresh) }
                            selectedRaw = bands.size
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        val band = bands.getOrNull(selected)
        if (band == null) {
            Text(
                text = "No bands. Add one, or paste a filter list from AutoEQ.",
                color = neo.textSecondary,
                style = NeoType.caption,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            BandEditor(
                number = selected + 1,
                band = band,
                onChange = { updateBand(selected, it) },
                onDelete = {
                    onChange { s -> s.copy(peqBands = s.peqBands.filterIndexed { i, _ -> i != selected }) }
                    selectedRaw = (selected - 1).coerceAtLeast(0)
                },
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NeoChip(
                text = "Paste",
                selected = false,
                onClick = {
                    val imported = EqCodec.fromApoText(readClipboard(context))
                    if (imported == null || imported.bands.isEmpty()) {
                        AppMessages.post("No EQ filters found on the clipboard")
                    } else {
                        onChange { it.copy(peqBands = imported.bands, peqEnabled = true, eqAutoHeadroom = true) }
                        selectedRaw = 0
                        AppMessages.post(
                            "Imported ${imported.bands.size} filters" +
                                if (imported.skipped > 0) " (${imported.skipped} skipped)" else ""
                        )
                    }
                },
            )
            NeoChip(
                text = "Copy",
                selected = false,
                onClick = {
                    val preamp = -EqMath.peakGainDb(EqLayout.build(false, settings.eqBands, true, bands))
                    val text = EqCodec.toApoText(bands, if (settings.eqAutoHeadroom) preamp else 0.0)
                    val manager = context.getSystemService(ClipboardManager::class.java)
                    manager?.setPrimaryClip(ClipData.newPlainText("Resonix EQ", text))
                    AppMessages.post("Copied ${bands.size} filters")
                },
            )
            NeoChip(
                text = "Reset",
                selected = false,
                onClick = {
                    onChange { it.copy(peqBands = defaultPeqBands()) }
                    selectedRaw = 0
                },
            )
        }
        Text(
            text = "Paste an AutoEQ / Equalizer APO filter list, or copy these bands to share.",
            color = neo.textTertiary,
            style = NeoType.caption,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun readClipboard(context: android.content.Context): String {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return ""
    val clip = manager.primaryClip ?: return ""
    if (clip.itemCount == 0) return ""
    return clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
}

// ───────────────────────────── response graph ─────────────────────────────

/** Index of the node nearest to [at] within [radius] pixels, or −1. */
private fun nearestNode(at: Offset, width: Float, height: Float, nodes: List<PeqBand>, rangeDb: Float, radius: Float): Int {
    var best = -1
    var bestDistance = radius * radius
    for ((i, b) in nodes.withIndex()) {
        val x = freqToFraction(b.freqHz) * width
        val y = nodeY(b, height, rangeDb)
        val dx = x - at.x
        val dy = y - at.y
        val d = dx * dx + dy * dy
        if (d <= bestDistance) {
            bestDistance = d
            best = i
        }
    }
    return best
}

private fun nodeY(band: PeqBand, height: Float, rangeDb: Float): Float =
    if (band.type.hasGain) (height / 2f - band.gainDb / rangeDb * (height / 2f)).coerceIn(0f, height) else height / 2f

/**
 * The combined frequency response, with a draggable dot for every band: drag sideways for
 * frequency, up and down for gain; tap to select.
 */
@Composable
private fun PeqGraph(
    curveBands: List<EngineBand>,
    nodes: List<PeqBand>,
    selected: Int,
    active: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, freqHz: Float, gainDb: Float) -> Unit,
) {
    val neo = LocalNeoColors.current
    val curve = remember(curveBands) { EqMath.responseDb(curveBands, GraphFrequencies) }
    val biggest = max(
        curve.maxOfOrNull { abs(it) } ?: 0.0,
        nodes.maxOfOrNull { abs(it.gainDb).toDouble() } ?: 0.0,
    )
    val rangeDb = when {
        biggest > 18.0 -> 24f
        biggest > 12.0 -> 18f
        else -> 12f
    }

    val currentNodes by rememberUpdatedState(nodes)
    val currentRange by rememberUpdatedState(rangeDb)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnMove by rememberUpdatedState(onMove)

    NeoInsetPanel(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp),
        cornerRadius = 18.dp,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Only a touch that lands on a dot is ours (select it, then drag it); anywhere else the
                // touch passes through, so the page can still be scrolled with a finger over the graph.
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val hit = nearestNode(down.position, w, h, currentNodes, currentRange, 40.dp.toPx())
                        if (hit < 0) return@awaitEachGesture
                        down.consume()
                        currentOnSelect(hit)
                        drag(down.id) { change ->
                            change.consume()
                            val freq = fractionToFreq(change.position.x / w)
                            val gain = (0.5f - change.position.y / h) * 2f * currentRange
                            currentOnMove(hit, freq, gain)
                        }
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            fun yOf(db: Double): Float = (h / 2f - (db / rangeDb * (h / 2f)).toFloat()).coerceIn(0f, h)

            val grid = neo.textTertiary.copy(alpha = 0.18f)
            val hairline = 1.dp.toPx()
            for (db in listOf(-rangeDb / 2.0, rangeDb / 2.0)) {
                drawLine(grid, Offset(0f, yOf(db)), Offset(w, yOf(db)), strokeWidth = hairline)
            }
            for (f in listOf(100f, 1000f, 10000f)) {
                val x = freqToFraction(f) * w
                drawLine(grid, Offset(x, 0f), Offset(x, h), strokeWidth = hairline)
            }
            drawLine(neo.textTertiary.copy(alpha = 0.45f), Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1.5.dp.toPx())

            val color = if (active) neo.accent else neo.textTertiary
            val line = Path()
            val fill = Path()
            for (i in curve.indices) {
                val x = w * i / (curve.size - 1)
                val y = yOf(curve[i])
                if (i == 0) {
                    line.moveTo(x, y)
                    fill.moveTo(x, h / 2f)
                    fill.lineTo(x, y)
                } else {
                    line.lineTo(x, y)
                    fill.lineTo(x, y)
                }
            }
            fill.lineTo(w, h / 2f)
            fill.close()
            drawPath(fill, color.copy(alpha = 0.16f))
            drawPath(line, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            for ((i, b) in nodes.withIndex()) {
                val centre = Offset(freqToFraction(b.freqHz) * w, nodeY(b, h, rangeDb))
                val isSelected = i == selected
                val dot = if (b.enabled) color else neo.textTertiary
                if (isSelected) drawCircle(dot.copy(alpha = 0.25f), radius = 17.dp.toPx(), center = centre)
                drawCircle(dot, radius = (if (isSelected) 8.dp else 5.5.dp).toPx(), center = centre)
                drawCircle(neo.surface, radius = (if (isSelected) 3.dp else 2.dp).toPx(), center = centre)
            }
        }
    }
}

/** 100 Hz · 1 kHz · 10 kHz under the graph, placed on the same log axis. */
@Composable
private fun FrequencyAxis() {
    val neo = LocalNeoColors.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(18.dp)
            .padding(top = 4.dp),
    ) {
        val total = maxWidth
        listOf(100f to "100", 1000f to "1k", 10000f to "10k").forEach { (freq, label) ->
            Text(
                text = label,
                color = neo.textTertiary,
                style = NeoType.tiny,
                modifier = Modifier.offset(x = total * freqToFraction(freq) - 8.dp),
            )
        }
    }
}

// ───────────────────────────── band editor ─────────────────────────────

@Composable
private fun BandEditor(
    number: Int,
    band: PeqBand,
    onChange: (PeqBand) -> Unit,
    onDelete: () -> Unit,
) {
    val neo = LocalNeoColors.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .neoWell(cornerRadius = 18.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "BAND $number", color = neo.textTertiary, style = NeoType.overline, modifier = Modifier.weight(1f))
            Text(text = "Band on", color = neo.textSecondary, style = NeoType.caption)
            Spacer(Modifier.width(10.dp))
            NeoSwitch(checked = band.enabled, onCheckedChange = { onChange(band.copy(enabled = it)) })
        }
        Spacer(Modifier.height(12.dp))
        LazyRow(
            modifier = Modifier.bleedHorizontally(14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 14.dp),
        ) {
            items(PeqType.entries.toList(), key = { it.name }) { type ->
                NeoChip(
                    text = type.label,
                    selected = band.type == type,
                    onClick = { onChange(band.copy(type = type)) },
                )
            }
        }

        ParamRow(
            label = "Frequency",
            shown = formatFreq(band.freqHz),
            editText = formatFreq(band.freqHz).replace(" kHz", "k").replace(" Hz", ""),
            fraction = freqToFraction(band.freqHz),
            onFraction = { onChange(band.copy(freqHz = roundedFreq(fractionToFreq(it)))) },
            onStep = { direction -> onChange(band.copy(freqHz = roundedFreq(band.freqHz * 2f.pow(direction / 12f)))) },
            onTyped = { text -> parseFreqInput(text)?.let { onChange(band.copy(freqHz = it)) } },
        )

        if (band.type.hasGain) {
            ParamRow(
                label = "Gain",
                shown = formatGain(band.gainDb),
                editText = String.format(java.util.Locale.US, "%.1f", band.gainDb),
                fraction = (band.gainDb - EqLimits.MIN_GAIN) / (EqLimits.MAX_GAIN - EqLimits.MIN_GAIN),
                centered = true,
                onFraction = {
                    val db = EqLimits.MIN_GAIN + it * (EqLimits.MAX_GAIN - EqLimits.MIN_GAIN)
                    onChange(band.copy(gainDb = EqLimits.gain((db * 10f).roundToInt() / 10f)))
                },
                onStep = { direction -> onChange(band.copy(gainDb = EqLimits.gain(((band.gainDb + direction * 0.1f) * 10f).roundToInt() / 10f))) },
                onTyped = { text -> parseNumberInput(text)?.let { onChange(band.copy(gainDb = EqLimits.gain(it))) } },
            )
        }

        ParamRow(
            label = "Q (width)",
            shown = formatQ(band.q),
            editText = String.format(java.util.Locale.US, "%.2f", band.q),
            fraction = qToFraction(band.q),
            onFraction = { onChange(band.copy(q = roundedQ(fractionToQ(it)))) },
            onStep = { direction ->
                val step = when {
                    band.q < 1f -> 0.05f
                    band.q < 5f -> 0.1f
                    else -> 0.5f
                }
                onChange(band.copy(q = EqLimits.q(((band.q + direction * step) * 100f).roundToInt() / 100f)))
            },
            onTyped = { text -> parseNumberInput(text)?.let { onChange(band.copy(q = EqLimits.q(it))) } },
        )

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (band.type.hasGain && band.gainDb != 0f) {
                NeoChip(text = "0 dB", selected = false, onClick = { onChange(band.copy(gainDb = 0f)) })
            }
            NeoChip(text = "Delete band", selected = false, onClick = onDelete)
        }
    }
}

private fun roundedFreq(hz: Float): Float =
    EqLimits.freq(if (hz >= 100f) hz.roundToInt().toFloat() else (hz * 10f).roundToInt() / 10f)

private fun roundedQ(q: Float): Float = EqLimits.q((q * 100f).roundToInt() / 100f)

/**
 * One adjustable value: name, a value you can tap to type an exact number, − / + for fine steps,
 * and a slider for broad moves. [onStep] gets −1 or +1.
 */
@Composable
private fun ParamRow(
    label: String,
    shown: String,
    editText: String,
    fraction: Float,
    onFraction: (Float) -> Unit,
    onStep: (Float) -> Unit,
    onTyped: (String) -> Unit,
    centered: Boolean = false,
) {
    val neo = LocalNeoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, color = neo.textSecondary, style = NeoType.body, modifier = Modifier.weight(1f))
        NeoChip(text = "−", selected = false, onClick = { onStep(-1f) })
        Spacer(Modifier.width(6.dp))
        ValueField(shown = shown, editText = editText, onCommit = onTyped)
        Spacer(Modifier.width(6.dp))
        NeoChip(text = "+", selected = false, onClick = { onStep(1f) })
    }
    NeoSlider(
        value = fraction,
        onValueChange = onFraction,
        centered = centered,
    )
}

/** A value shown in a small inset field; tap it and type an exact number (e.g. "2.5k", "-3.5"). */
@Composable
private fun ValueField(shown: String, editText: String, onCommit: (String) -> Unit) {
    val neo = LocalNeoColors.current
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val currentOnCommit by rememberUpdatedState(onCommit)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .widthIn(min = 84.dp)
            .neoFlat(cornerRadius = 12.dp),
    ) {
        if (!editing) {
            Text(
                text = shown,
                color = neo.textPrimary,
                style = NeoType.body,
                maxLines = 1,
                modifier = Modifier
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        draft = editText
                        editing = true
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            )
        } else {
            val focus = remember { FocusRequester() }
            var hadFocus by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { focus.requestFocus() }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = NeoType.body.copy(color = neo.textPrimary),
                cursorBrush = SolidColor(neo.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        currentOnCommit(draft)
                        editing = false
                    }
                ),
                modifier = Modifier
                    .widthIn(min = 92.dp)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .focusRequester(focus)
                    .onFocusChanged { state ->
                        if (state.isFocused) {
                            hadFocus = true
                        } else if (hadFocus && editing) {
                            currentOnCommit(draft)
                            editing = false
                        }
                    },
            )
        }
    }
}

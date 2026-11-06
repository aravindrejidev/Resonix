package com.resonix.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// The equalizer's model: what a band is, the maths of its filter (so the screen can draw exactly the
// curve the engine applies), how the graphic and parametric sections become one list for the engine,
// and how bands are saved / imported / exported. No Android types in here.

/** Kinds of parametric filter. [nativeValue] is cpp/Eq.h `EqType`. */
enum class PeqType(val label: String, val nativeValue: Int, val hasGain: Boolean, val apoCode: String) {
    PEAK("Peak", 0, true, "PK"),
    LOW_SHELF("Low shelf", 1, true, "LSC"),
    HIGH_SHELF("High shelf", 2, true, "HSC"),
    LOW_PASS("Low-pass", 3, false, "LPQ"),
    HIGH_PASS("High-pass", 4, false, "HPQ"),
    NOTCH("Notch", 5, false, "NO"),
}

/** One band of the parametric equalizer, as the user sets it. */
data class PeqBand(
    val type: PeqType = PeqType.PEAK,
    val freqHz: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
)

object EqLimits {
    const val MIN_FREQ = 20f
    const val MAX_FREQ = 20_000f
    const val MIN_GAIN = -24f
    const val MAX_GAIN = 24f
    const val MIN_Q = 0.1f
    const val MAX_Q = 20f
    const val MAX_PEQ_BANDS = 16

    fun freq(v: Float) = v.coerceIn(MIN_FREQ, MAX_FREQ)
    fun gain(v: Float) = v.coerceIn(MIN_GAIN, MAX_GAIN)
    fun q(v: Float) = v.coerceIn(MIN_Q, MAX_Q)
}

/** A band as the native engine takes it. */
data class EngineBand(val type: Int, val freqHz: Double, val gainDb: Double, val q: Double, val enabled: Boolean)

object EqMath {
    class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** RBJ cookbook designs — the same formulas, constants and clamps as cpp/Eq.h. */
    fun design(type: Int, freqHz: Double, gainDb: Double, q: Double, sampleRate: Double): Coeffs {
        val fs = if (sampleRate > 0.0) sampleRate else 48000.0
        val f = min(max(freqHz, 10.0), 0.49 * fs)
        val qq = min(max(q, 0.05), 50.0)
        val w0 = 2.0 * PI * f / fs
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * qq)
        val a = 10.0.pow(min(max(gainDb, -40.0), 40.0) / 40.0)

        val b0: Double
        val b1: Double
        val b2: Double
        val a0: Double
        val a1: Double
        val a2: Double
        when (type) {
            PeqType.LOW_SHELF.nativeValue -> {
                val s = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) - (a - 1) * cw + s)
                b1 = 2 * a * ((a - 1) - (a + 1) * cw)
                b2 = a * ((a + 1) - (a - 1) * cw - s)
                a0 = (a + 1) + (a - 1) * cw + s
                a1 = -2 * ((a - 1) + (a + 1) * cw)
                a2 = (a + 1) + (a - 1) * cw - s
            }
            PeqType.HIGH_SHELF.nativeValue -> {
                val s = 2.0 * sqrt(a) * alpha
                b0 = a * ((a + 1) + (a - 1) * cw + s)
                b1 = -2 * a * ((a - 1) + (a + 1) * cw)
                b2 = a * ((a + 1) + (a - 1) * cw - s)
                a0 = (a + 1) - (a - 1) * cw + s
                a1 = 2 * ((a - 1) - (a + 1) * cw)
                a2 = (a + 1) - (a - 1) * cw - s
            }
            PeqType.LOW_PASS.nativeValue -> {
                b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = (1 - cw) / 2
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            PeqType.HIGH_PASS.nativeValue -> {
                b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = (1 + cw) / 2
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            PeqType.NOTCH.nativeValue -> {
                b0 = 1.0; b1 = -2 * cw; b2 = 1.0
                a0 = 1 + alpha; a1 = -2 * cw; a2 = 1 - alpha
            }
            else -> {
                b0 = 1 + alpha * a; b1 = -2 * cw; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cw; a2 = 1 - alpha / a
            }
        }
        return Coeffs(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** |H| in dB at [freqHz]. */
    fun magnitudeDb(c: Coeffs, freqHz: Double, sampleRate: Double): Double {
        val w = 2.0 * PI * freqHz / sampleRate
        val cw = cos(w)
        val c2w = cos(2.0 * w)
        val num = c.b0 * c.b0 + c.b1 * c.b1 + c.b2 * c.b2 + 2.0 * (c.b0 * c.b1 + c.b1 * c.b2) * cw + 2.0 * c.b0 * c.b2 * c2w
        val den = 1.0 + c.a1 * c.a1 + c.a2 * c.a2 + 2.0 * (c.a1 + c.a1 * c.a2) * cw + 2.0 * c.a2 * c2w
        return if (den <= 0.0 || num <= 0.0) -300.0 else 10.0 * log10(num / den)
    }

    private fun isIdentity(b: EngineBand): Boolean =
        (b.type <= PeqType.HIGH_SHELF.nativeValue) && abs(b.gainDb) < 0.001

    /** Combined response of every enabled band at each of [freqs] (dB). */
    fun responseDb(bands: List<EngineBand>, freqs: DoubleArray, sampleRate: Double = 48_000.0): DoubleArray {
        val coeffs = bands.filter { it.enabled && !isIdentity(it) }
            .map { design(it.type, it.freqHz, it.gainDb, it.q, sampleRate) }
        return DoubleArray(freqs.size) { i ->
            var sum = 0.0
            for (c in coeffs) sum += magnitudeDb(c, freqs[i], sampleRate)
            sum
        }
    }

    /** Highest point of the combined response (dB, never below 0 here) — what auto headroom turns down by. */
    fun peakGainDb(bands: List<EngineBand>, sampleRate: Double = 48_000.0): Double {
        val active = bands.filter { it.enabled && !isIdentity(it) }
        if (active.isEmpty()) return 0.0
        val top = 0.49 * sampleRate
        val grid = 600
        val freqs = DoubleArray(grid + 1 + active.size)
        for (g in 0..grid) freqs[g] = 10.0 * (top / 10.0).pow(g.toDouble() / grid)
        for ((i, b) in active.withIndex()) freqs[grid + 1 + i] = min(max(b.freqHz, 10.0), top)
        return max(0.0, responseDb(active, freqs, sampleRate).maxOrNull() ?: 0.0)
    }
}

/** How the two equalizers become the one list of bands the engine runs. */
object EqLayout {
    /** ISO-style octave centres of the graphic equalizer. */
    val GRAPHIC_FREQUENCIES = doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)

    /** One octave per band: neighbouring bands overlap just enough to make a smooth curve. */
    const val GRAPHIC_Q = 1.4142

    /**
     * Fixed slots — graphic bands first, parametric after them — so that switching one section off
     * never moves the other's filters to different slots (which would click). A section that is off
     * keeps its slots but disabled; the engine glides those back to flat.
     */
    fun build(graphicOn: Boolean, graphicGains: List<Float>, peqOn: Boolean, peq: List<PeqBand>): List<EngineBand> {
        val out = ArrayList<EngineBand>(GRAPHIC_FREQUENCIES.size + peq.size)
        for (i in GRAPHIC_FREQUENCIES.indices) {
            out.add(EngineBand(PeqType.PEAK.nativeValue, GRAPHIC_FREQUENCIES[i], (graphicGains.getOrNull(i) ?: 0f).toDouble(), GRAPHIC_Q, graphicOn))
        }
        for (band in peq.take(EqLimits.MAX_PEQ_BANDS)) {
            out.add(EngineBand(band.type.nativeValue, band.freqHz.toDouble(), band.gainDb.toDouble(), band.q.toDouble(), peqOn && band.enabled))
        }
        return out
    }
}

/** Saving, and the Equalizer APO / AutoEQ text format everybody shares EQ settings in. */
object EqCodec {
    /** "PEAK,1000.0,3.5,1.0,1;LOW_SHELF,…" — what is stored in preferences. */
    fun encode(bands: List<PeqBand>): String =
        bands.joinToString(";") { "${it.type.name},${it.freqHz},${it.gainDb},${it.q},${if (it.enabled) 1 else 0}" }

    /** Inverse of [encode]; `null` if the text is not a band list. An empty string is a valid empty list. */
    fun decode(text: String?): List<PeqBand>? {
        if (text == null) return null
        if (text.isBlank()) return emptyList()
        val out = ArrayList<PeqBand>()
        for (part in text.split(';')) {
            val f = part.split(',')
            if (f.size != 5) return null
            val type = PeqType.entries.firstOrNull { it.name == f[0] } ?: return null
            out.add(
                PeqBand(
                    type = type,
                    freqHz = EqLimits.freq(f[1].toFloatOrNull() ?: return null),
                    gainDb = EqLimits.gain(f[2].toFloatOrNull() ?: return null),
                    q = EqLimits.q(f[3].toFloatOrNull() ?: return null),
                    enabled = f[4] == "1",
                )
            )
        }
        return out.take(EqLimits.MAX_PEQ_BANDS)
    }

    /** "Preamp: -6.0 dB" and one "Filter n: ON PK Fc … Hz Gain … dB Q …" line per band (Equalizer APO). */
    fun toApoText(bands: List<PeqBand>, preampDb: Double): String = buildString {
        append("Preamp: ").append(fmt(preampDb, 1)).append(" dB\n")
        bands.forEachIndexed { index, b ->
            append("Filter ").append(index + 1).append(": ").append(if (b.enabled) "ON " else "OFF ").append(b.type.apoCode)
            append(" Fc ").append(fmt(b.freqHz.toDouble(), 1)).append(" Hz")
            if (b.type.hasGain) append(" Gain ").append(fmt(b.gainDb.toDouble(), 2)).append(" dB")
            append(" Q ").append(fmt(b.q.toDouble(), 3)).append('\n')
        }
    }

    class Import(val bands: List<PeqBand>, val skipped: Int, val preampDb: Double?)

    private val FILTER_LINE = Regex("""^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+(\S+)(.*)$""", RegexOption.IGNORE_CASE)
    private val PREAMP_LINE = Regex("""^\s*Preamp\s*:\s*([-+]?[\d.,]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val FC = Regex("""\bFc\s+([\d.,]+)\s*(k?Hz)?""", RegexOption.IGNORE_CASE)
    private val GAIN = Regex("""\bGain\s+([-+]?[\d.,]+)\s*dB""", RegexOption.IGNORE_CASE)
    private val Q = Regex("""\bQ\s+([\d.,]+)""", RegexOption.IGNORE_CASE)
    private val BW = Regex("""\bBW\s+Oct\s+([\d.,]+)""", RegexOption.IGNORE_CASE)

    /**
     * Reads Equalizer APO's text format (what AutoEQ, Peace, and most headphone-EQ sites produce).
     * Understands PK / PEQ, LS / LSC, HS / HSC, LP / LPQ, HP / HPQ and NO; other filter kinds are
     * counted in [Import.skipped]. `null` if no filter line was found at all.
     */
    fun fromApoText(text: String): Import? {
        val bands = ArrayList<PeqBand>()
        var skipped = 0
        var preamp: Double? = null
        for (line in text.lineSequence()) {
            PREAMP_LINE.find(line)?.let { preamp = number(it.groupValues[1]); return@let }
            val m = FILTER_LINE.find(line) ?: continue
            val enabled = m.groupValues[1].equals("ON", ignoreCase = true)
            val rest = m.groupValues[3]
            val (type, defaultQ) = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ", "MODAL" -> PeqType.PEAK to 1.0
                "LS", "LSC" -> PeqType.LOW_SHELF to 0.7071
                "HS", "HSC" -> PeqType.HIGH_SHELF to 0.7071
                "LP", "LPQ" -> PeqType.LOW_PASS to 0.7071
                "HP", "HPQ" -> PeqType.HIGH_PASS to 0.7071
                "NO", "NOTCH" -> PeqType.NOTCH to 4.3
                else -> { skipped++; continue }
            }
            val fc = FC.find(rest)
            val freq = fc?.let { number(it.groupValues[1])?.times(if (it.groupValues[2].startsWith("k", true)) 1000.0 else 1.0) }
            if (freq == null) { skipped++; continue }
            val gain = if (type.hasGain) GAIN.find(rest)?.let { number(it.groupValues[1]) } ?: 0.0 else 0.0
            val q = Q.find(rest)?.let { number(it.groupValues[1]) }
                ?: BW.find(rest)?.let { number(it.groupValues[1]) }?.let { qFromOctaves(it) }
                ?: defaultQ
            bands.add(
                PeqBand(
                    type = type,
                    freqHz = EqLimits.freq(freq.toFloat()),
                    gainDb = EqLimits.gain(gain.toFloat()),
                    q = EqLimits.q(q.toFloat()),
                    enabled = enabled,
                )
            )
        }
        if (bands.isEmpty() && skipped == 0) return null
        val over = max(0, bands.size - EqLimits.MAX_PEQ_BANDS)
        return Import(bands.take(EqLimits.MAX_PEQ_BANDS), skipped + over, preamp)
    }

    /** Q of a band that is [octaves] wide (the "BW Oct" notation). */
    fun qFromOctaves(octaves: Double): Double {
        val n = 2.0.pow(octaves)
        return sqrt(n) / (n - 1.0).coerceAtLeast(1e-6)
    }

    private fun number(text: String): Double? = text.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun fmt(v: Double, decimals: Int): String = String.format(java.util.Locale.US, "%.${decimals}f", v)
}

/** The bands a fresh parametric equalizer starts with: a shelf at each end and three bells, all flat. */
fun defaultPeqBands(): List<PeqBand> = listOf(
    PeqBand(PeqType.LOW_SHELF, 80f, 0f, 0.71f),
    PeqBand(PeqType.PEAK, 250f, 0f, 1f),
    PeqBand(PeqType.PEAK, 1000f, 0f, 1f),
    PeqBand(PeqType.PEAK, 4000f, 0f, 1f),
    PeqBand(PeqType.HIGH_SHELF, 10000f, 0f, 0.71f),
)

/** Position of [freqHz] on the log axis 20 Hz … 20 kHz, 0 … 1. */
fun freqToFraction(freqHz: Float): Float = (ln(EqLimits.freq(freqHz) / EqLimits.MIN_FREQ) / ln(EqLimits.MAX_FREQ / EqLimits.MIN_FREQ))

fun fractionToFreq(fraction: Float): Float =
    EqLimits.freq(EqLimits.MIN_FREQ * (EqLimits.MAX_FREQ / EqLimits.MIN_FREQ).pow(fraction.coerceIn(0f, 1f)))

fun qToFraction(q: Float): Float = (ln(EqLimits.q(q) / EqLimits.MIN_Q) / ln(EqLimits.MAX_Q / EqLimits.MIN_Q))

fun fractionToQ(fraction: Float): Float =
    EqLimits.q(EqLimits.MIN_Q * (EqLimits.MAX_Q / EqLimits.MIN_Q).pow(fraction.coerceIn(0f, 1f)))

// ───────────────────────── text in / out for the editor ─────────────────────────

/** "250 Hz", "2.5 kHz", "10 kHz". */
fun formatFreq(hz: Float): String {
    if (hz < 1000f) return trimmed(hz, 1) + " Hz"
    return trimmed(hz / 1000f, 2) + " kHz"
}

fun formatGain(db: Float): String = (if (db > 0f) "+" else "") + String.format(java.util.Locale.US, "%.1f", db) + " dB"

fun formatQ(q: Float): String = String.format(java.util.Locale.US, if (q < 1f) "%.2f" else "%.2f", q)

private fun trimmed(v: Float, decimals: Int): String {
    val s = String.format(java.util.Locale.US, "%.${decimals}f", v)
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

/** A typed frequency: "2500", "2500 Hz", "2.5k", "2,5 kHz". `null` if it is not a number. */
fun parseFreqInput(text: String): Float? {
    var t = text.trim().lowercase().replace(',', '.').replace(" ", "")
    var scale = 1f
    if (t.endsWith("khz")) { t = t.dropLast(3); scale = 1000f }
    else if (t.endsWith("hz")) t = t.dropLast(2)
    else if (t.endsWith("k")) { t = t.dropLast(1); scale = 1000f }
    val v = t.toFloatOrNull() ?: return null
    return if (v.isFinite() && v > 0f) EqLimits.freq(v * scale) else null
}

/** A typed plain number ("-3.5", "+4", "1,2"); `null` if it is not one. */
fun parseNumberInput(text: String): Float? {
    val t = text.trim().lowercase().replace(',', '.').removeSuffix("db").trim()
    return t.toFloatOrNull()?.takeIf { it.isFinite() }
}

/** Where to put a new band: the middle (on the log axis) of the widest gap between the existing ones. */
fun suggestNewBandFreq(existing: List<PeqBand>): Float {
    val points = (listOf(EqLimits.MIN_FREQ, EqLimits.MAX_FREQ) + existing.map { it.freqHz }).sorted()
    var best = 1000f
    var widest = -1f
    for (i in 0 until points.size - 1) {
        val gap = ln(points[i + 1] / points[i])
        if (gap > widest) {
            widest = gap
            best = kotlin.math.sqrt(points[i] * points[i + 1])
        }
    }
    return EqLimits.freq(best)
}

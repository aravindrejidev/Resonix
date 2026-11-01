package com.resonix.player.playback

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The decisions that make an output route sound as good as it can, as plain functions of numbers —
 * no Android types, so they are easy to reason about (and to test).
 */
object OutputPlanner {

    /** The PCM encodings a bit-perfect mixer can be asked for. */
    enum class Encoding(val bits: Int, val nativeFormatPref: Int) {
        PCM16(16, 2),
        PCM24(24, 3),
        PCM32(32, 4),
        FLOAT(32, 1),
    }

    /** One format a DAC offers in bit-perfect mode. */
    data class MixerOption(val sampleRateHz: Int, val channelCount: Int, val encoding: Encoding)

    /**
     * The sample rate the engine should deliver for a file at [fileRateHz] — `0` meaning "leave it
     * at the file's own rate".
     *
     * [supportedRates] are what the output device accepts (empty = unknown, assume anything).
     * With [bitPerfect] the file's rate is kept whenever the device can take it; otherwise the
     * nearest rate in the same family (44.1 → 88.2 / 176.4, 48 → 96 / 192: whole-number ratios
     * resample most cleanly) and, failing that, the closest one above. Without [bitPerfect] every
     * song goes out at the device's own mixer rate ([nativeRateHz]), so the stream never reopens.
     */
    fun chooseRate(fileRateHz: Int, supportedRates: List<Int>, nativeRateHz: Int, bitPerfect: Boolean): Int {
        if (fileRateHz <= 0) return 0
        if (!bitPerfect) {
            val native = if (nativeRateHz > 0) nativeRateHz else 48_000
            return if (supportedRates.isEmpty() || native in supportedRates) native else closestAbove(native, supportedRates)
        }
        if (supportedRates.isEmpty() || fileRateHz in supportedRates) return 0
        for (multiple in 2..8) {
            val candidate = fileRateHz * multiple
            if (candidate in supportedRates) return candidate
        }
        return closestAbove(fileRateHz, supportedRates)
    }

    private fun closestAbove(rate: Int, supported: List<Int>): Int =
        supported.filter { it > rate }.minOrNull() ?: supported.maxOrNull() ?: 0

    /**
     * Picks the entry of [options] to ask the bit-perfect mixer for: the file's own rate if offered
     * (else [chooseRate]'s pick), the right channel count, and the smallest integer encoding that
     * holds [fileBits] without rounding. Returns its index, or `null` if nothing fits.
     */
    fun chooseMixerOption(options: List<MixerOption>, fileRateHz: Int, fileBits: Int, channelCount: Int): Int? {
        val usable = options.withIndex().filter { it.value.channelCount == channelCount }
        if (usable.isEmpty() || fileRateHz <= 0) return null
        val rates = usable.map { it.value.sampleRateHz }.distinct()
        val rate = if (fileRateHz in rates) fileRateHz else chooseRate(fileRateHz, rates, 0, true).takeIf { it > 0 } ?: return null
        // Lossy sources have no native bit depth; 24 bits is plenty for what a decoder produces.
        val wanted = if (fileBits in 1..32) fileBits else 24
        return usable
            .filter { it.value.sampleRateHz == rate }
            .minByOrNull { encodingCost(it.value.encoding, wanted) }
            ?.index
    }

    private fun encodingCost(encoding: Encoding, wantedBits: Int): Int = when {
        encoding == Encoding.FLOAT -> if (wantedBits <= 24) 50 else 60  // exact for ≤24-bit, but ints are what DACs speak
        encoding.bits >= wantedBits -> encoding.bits - wantedBits       // smallest container that holds it
        else -> 100 + (wantedBits - encoding.bits)                      // would have to round: last resort
    }
}

/** Direct volume control arithmetic. The engine's gain is `level²`, so 50 % is −12 dB. */
object VolumeMath {
    const val MIN_LEVEL = 0.02f

    fun gainForLevel(level: Float): Double {
        val l = level.coerceIn(0f, 1f).toDouble()
        return l * l
    }

    fun levelForGain(gain: Double): Float = sqrt(gain.coerceIn(0.0, 1.0)).toFloat()

    /** Loudness of a level, in dB below full scale. */
    fun dbForLevel(level: Float): Double {
        val gain = gainForLevel(level)
        return if (gain <= 0.0) Double.NEGATIVE_INFINITY else 20.0 * log10(gain)
    }

    /**
     * The level that sounds exactly as loud as [level] did with Android's volume at [systemDb]
     * (≤ 0, relative to its maximum) — for when the system volume is about to be parked at maximum
     * or bypassed, so the switch itself never makes anything louder or quieter.
     */
    fun preservedLevel(level: Float, systemDb: Float): Float {
        if (systemDb.isNaN() || systemDb >= 0f) return level
        if (systemDb == Float.NEGATIVE_INFINITY) return MIN_LEVEL
        val systemGain = 10.0.pow(systemDb / 20.0)
        return levelForGain(gainForLevel(level) * systemGain).coerceIn(MIN_LEVEL, 1f)
    }

    /**
     * Which Android volume step reproduces the loudness of [level] (engine only, system at maximum)
     * once DVC is switched off again. [dbByIndex] is the attenuation of every step relative to
     * the maximum (index 0 … max). Picks the loudest step that is not louder than the target.
     */
    fun systemIndexFor(level: Float, dbByIndex: List<Float>): Int {
        if (dbByIndex.isEmpty()) return 0
        val target = dbForLevel(level).toFloat()
        var best = 0
        for (i in dbByIndex.indices) {
            if (dbByIndex[i] <= target + 0.25f) best = i  // 0.25 dB of slack for rounding
        }
        return best
    }
}

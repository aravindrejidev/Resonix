package com.resonix.player.ui

import android.graphics.Bitmap

/**
 * Picks the colour a cover is "about" and turns it into the deep background tint the full-frame
 * Now Playing screen fades into. Pure arithmetic on ARGB ints (no Android classes), apart from
 * [tintFromBitmap], which only samples the bitmap.
 */
object ArtPalette {

    private const val HUE_BINS = 12
    private const val MAX_TINT_LUMINANCE = 0.16

    /**
     * The dominant *colourful* colour of [pixels] (ARGB). Saturated, reasonably bright pixels vote
     * for their hue; the winning hue's average is returned. A cover that is practically grey
     * returns the plain average instead. `null` if there is nothing opaque to look at.
     */
    fun dominantColor(pixels: IntArray): Int? {
        val weight = DoubleArray(HUE_BINS)
        val sumR = DoubleArray(HUE_BINS)
        val sumG = DoubleArray(HUE_BINS)
        val sumB = DoubleArray(HUE_BINS)
        var counted = 0
        var allR = 0.0
        var allG = 0.0
        var allB = 0.0

        for (p in pixels) {
            if ((p ushr 24) and 0xFF < 128) continue
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            counted++
            allR += r
            allG += g
            allB += b

            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            if (max == 0) continue
            val v = max / 255.0
            val delta = (max - min).toDouble()
            val s = delta / max
            if (s < 0.15 || v < 0.15) continue  // grey / near-black pixels have no say in the hue

            val hue = hueOf(r, g, b, max, min)
            val bin = ((hue / 360.0) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            val w = s * (0.35 + 0.65 * v)
            weight[bin] += w
            sumR[bin] += r * w
            sumG[bin] += g * w
            sumB[bin] += b * w
        }
        if (counted == 0) return null

        var best = 0
        for (i in 1 until HUE_BINS) if (weight[i] > weight[best]) best = i

        // Barely any colour anywhere: a black-and-white cover. Use its overall brightness.
        if (weight[best] < counted * 0.02) {
            return argb((allR / counted).toInt(), (allG / counted).toInt(), (allB / counted).toInt())
        }
        return argb(
            (sumR[best] / weight[best]).toInt(),
            (sumG[best] / weight[best]).toInt(),
            (sumB[best] / weight[best]).toInt(),
        )
    }

    /**
     * The dark, slightly muted version of [dominant] used as the screen's background: same hue,
     * saturation kept in a pleasant band, brightness low enough for white text. Grey input gives
     * a neutral charcoal that is a touch lighter for lighter covers.
     */
    fun tintFor(dominant: Int): Int {
        val r = (dominant shr 16) and 0xFF
        val g = (dominant shr 8) and 0xFF
        val b = dominant and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val v = max / 255.0
        val s = if (max == 0) 0.0 else (max - min).toDouble() / max

        return if (s < 0.12) {
            val level = (0.20 + 0.10 * v)
            hsv(220.0, 0.08, level)
        } else {
            // Same hue every time, but green / yellow look far brighter than blue / red at equal
            // "value", so darken until white text keeps a comfortable contrast on it.
            val hue = hueOf(r, g, b, max, min)
            val sat = s.coerceIn(0.40, 0.85)
            var value = 0.34
            var tint = hsv(hue, sat, value)
            while (luminanceOf(tint) > MAX_TINT_LUMINANCE && value > 0.10) {
                value -= 0.02
                tint = hsv(hue, sat, value)
            }
            tint
        }
    }

    /** Relative luminance (0 … 1) of an ARGB colour, ignoring gamma — good enough to compare tints. */
    private fun luminanceOf(argb: Int): Double =
        (0.2126 * ((argb shr 16) and 0xFF) + 0.7152 * ((argb shr 8) and 0xFF) + 0.0722 * (argb and 0xFF)) / 255.0

    /** Convenience for the common case: bitmap in, tint out (ARGB), `null` if it cannot be read. */
    fun tintOf(pixels: IntArray): Int? = dominantColor(pixels)?.let { tintFor(it) }

    private fun hueOf(r: Int, g: Int, b: Int, max: Int, min: Int): Double {
        val delta = (max - min).toDouble()
        if (delta == 0.0) return 0.0
        val h = when (max) {
            r -> ((g - b) / delta) % 6.0
            g -> (b - r) / delta + 2.0
            else -> (r - g) / delta + 4.0
        } * 60.0
        return if (h < 0) h + 360.0 else h
    }

    private fun hsv(h: Double, s: Double, v: Double): Int {
        val c = v * s
        val x = c * (1 - kotlin.math.abs((h / 60.0) % 2 - 1))
        val m = v - c
        val (r1, g1, b1) = when {
            h < 60 -> Triple(c, x, 0.0)
            h < 120 -> Triple(x, c, 0.0)
            h < 180 -> Triple(0.0, c, x)
            h < 240 -> Triple(0.0, x, c)
            h < 300 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        return argb(
            ((r1 + m) * 255).toInt().coerceIn(0, 255),
            ((g1 + m) * 255).toInt().coerceIn(0, 255),
            ((b1 + m) * 255).toInt().coerceIn(0, 255),
        )
    }

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
}

/**
 * The background tint (ARGB) for [bitmap], or `null` if it cannot be sampled (e.g. a hardware
 * bitmap the CPU may not read). Samples a 32×32 thumbnail, so it is cheap.
 */
fun tintFromBitmap(bitmap: Bitmap): Int? {
    return try {
        val small = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
        val pixels = IntArray(32 * 32)
        small.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        if (small !== bitmap) small.recycle()
        ArtPalette.tintOf(pixels)
    } catch (e: Exception) {
        null
    }
}

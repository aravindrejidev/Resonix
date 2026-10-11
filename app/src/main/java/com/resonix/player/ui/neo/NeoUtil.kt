package com.resonix.player.ui.neo

import java.util.Locale

/** 3:07, or 1:02:09 for anything an hour or longer. */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    } else {
        String.format(Locale.US, "%d:%02d", m, s)
    }
}

/** "2 h 05 min" / "47 min" — for folder totals. */
fun formatTotalTime(ms: Long): String {
    val totalMin = (ms / 60_000L).coerceAtLeast(0L)
    val h = totalMin / 60L
    val m = totalMin % 60L
    return when {
        h > 0L -> String.format(Locale.US, "%d h %02d min", h, m)
        else -> String.format(Locale.US, "%d min", m)
    }
}

/** "FLAC · 24-bit / 96 kHz" — same wording the old UI used. */
fun formatQualityLabel(format: String, bitDepth: Int, sampleRateHz: Int): String {
    val parts = mutableListOf<String>()
    if (format.isNotBlank()) parts.add(format.uppercase(Locale.ROOT))
    if (bitDepth > 0 && sampleRateHz > 0) {
        val khz = sampleRateHz / 1000f
        val khzText = if (khz % 1f == 0f) khz.toInt().toString() else String.format(Locale.US, "%.1f", khz)
        parts.add("$bitDepth-bit / $khzText kHz")
    } else if (sampleRateHz > 0) {
        val khz = sampleRateHz / 1000f
        val khzText = if (khz % 1f == 0f) khz.toInt().toString() else String.format(Locale.US, "%.1f", khz)
        parts.add("$khzText kHz")
    }
    return parts.joinToString(" · ")
}

/** Anything above CD quality (16-bit / 44.1–48 kHz). */
fun isHiRes(bitDepth: Int, sampleRateHz: Int): Boolean = bitDepth > 16 || sampleRateHz > 48_000

/** "just now", "5 min ago", "3 days ago" … */
fun formatRelativeTime(epochSec: Long, nowMs: Long = System.currentTimeMillis()): String {
    if (epochSec <= 0L) return "never"
    val diffSec = (nowMs / 1000L - epochSec).coerceAtLeast(0L)
    return when {
        diffSec < 60L -> "just now"
        diffSec < 3600L -> "${diffSec / 60L} min ago"
        diffSec < 86_400L -> "${diffSec / 3600L} h ago"
        diffSec < 86_400L * 30L -> "${diffSec / 86_400L} d ago"
        else -> "${diffSec / (86_400L * 30L)} mo ago"
    }
}

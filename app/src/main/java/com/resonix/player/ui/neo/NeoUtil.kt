package com.resonix.player.ui.neo

import com.resonix.player.audio.displayFormatName
import com.resonix.player.audio.isHiResAudio
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

/**
 * "FLAC · 24-bit / 96 kHz" for lossless files, "MP3 · 320 kbps" for lossy ones (their bit
 * depth is meaningless, their bitrate is what you care about).
 */
fun formatQualityLabel(format: String, bitDepth: Int, sampleRateHz: Int, bitrateBps: Long = 0L): String {
    val parts = mutableListOf<String>()
    val name = displayFormatName(format)
    if (name.isNotEmpty()) parts.add(name)

    val khz = sampleRateHz / 1000f
    val khzText = if (khz % 1f == 0f) khz.toInt().toString() else String.format(Locale.US, "%.1f", khz)
    when {
        bitDepth > 0 && sampleRateHz > 0 -> parts.add("$bitDepth-bit / $khzText kHz")
        bitDepth <= 0 && bitrateBps > 0L -> parts.add("${(bitrateBps + 500L) / 1000L} kbps")
        sampleRateHz > 0 -> parts.add("$khzText kHz")
    }
    return parts.joinToString(" · ")
}

/**
 * Anything above CD quality (16-bit / 44.1–48 kHz). The scanner only records a bit depth
 * for lossless files (it is 0 for MP3 / AAC …), so a lossy file is never flagged by depth.
 */
fun isHiRes(bitDepth: Int, sampleRateHz: Int): Boolean = bitDepth > 16 || sampleRateHz > 48_000

/** Same, but also refuses to call a lossy file "hi-res" just because of its sample rate. */
fun isHiRes(format: String, bitDepth: Int, sampleRateHz: Int): Boolean =
    isHiResAudio(format, bitDepth, sampleRateHz)

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

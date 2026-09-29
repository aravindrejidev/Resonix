package com.resonix.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.theme.ResonixSurfaceVariant
import com.resonix.player.ui.theme.ResonixTextSecondary

/** e.g. "FLAC · 24-bit · 96kHz" or "DVC Active". */
@Composable
fun QualityBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = ResonixTextSecondary,
        modifier = modifier
            .background(ResonixSurfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

/** Builds the standard "FORMAT · N-bit · NNkHz" string from track info,
 * omitting a field if it isn't known (0/blank). */
fun formatQualityLabel(format: String, bitDepth: Int, sampleRateHz: Int): String {
    val parts = mutableListOf<String>()
    if (format.isNotBlank()) parts.add(format.uppercase())
    if (bitDepth > 0) parts.add("$bitDepth-bit")
    if (sampleRateHz > 0) {
        val khz = sampleRateHz / 1000.0
        val khzText = if (khz == khz.toInt().toDouble()) "${khz.toInt()}kHz" else "${"%.1f".format(khz)}kHz"
        parts.add(khzText)
    }
    return parts.joinToString(" · ")
}

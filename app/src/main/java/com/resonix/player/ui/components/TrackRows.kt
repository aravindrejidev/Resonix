package com.resonix.player.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.data.Track
import com.resonix.player.ui.neo.NeoAlbumArt
import com.resonix.player.ui.neo.NeoEqBars
import com.resonix.player.ui.neo.NeoHiResTag
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoQualityPill
import com.resonix.player.ui.neo.formatDuration
import com.resonix.player.ui.neo.formatQualityLabel
import com.resonix.player.ui.neo.isHiRes
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/**
 * One song in a list. Rows are flat — no card per song, so a long list stays calm. The song that
 * is loaded gets a faint accent tint, an accent title and bouncing bars.
 */
@Composable
fun TrackRow(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    showBadge: Boolean,
    showArt: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val rowColor by animateColorAsState(
        targetValue = when {
            isCurrent -> neo.accent.copy(alpha = 0.10f)
            pressed -> neo.textPrimary.copy(alpha = 0.06f)
            else -> neo.accent.copy(alpha = 0f)
        },
        label = "trackRowColor",
    )
    val titleColor by animateColorAsState(
        targetValue = if (isCurrent) neo.accent else neo.textPrimary,
        label = "trackRowTitle",
    )
    val subtitle = listOf(track.artist, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
        .ifBlank { "Unknown artist" }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor, RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoAlbumArt(
            uri = if (showArt) track.albumArtUri.ifBlank { track.contentUri } else "",
            modifier = Modifier.size(54.dp),
            cornerRadius = 12.dp,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title.ifBlank { "Unknown title" },
                color = titleColor,
                style = NeoType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                color = neo.textSecondary,
                style = NeoType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val quality = formatQualityLabel(track.format, track.bitDepth, track.sampleRateHz)
            if (showBadge && quality.isNotEmpty()) {
                Spacer(Modifier.height(5.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeoQualityPill(text = quality, modifier = Modifier.weight(1f, fill = false))
                    if (isHiRes(track.bitDepth, track.sampleRateHz)) NeoHiResTag()
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        if (isCurrent) {
            NeoEqBars(playing = isPlaying, color = neo.accent)
        } else {
            Text(
                text = formatDuration(track.durationMs),
                color = neo.textTertiary,
                style = NeoType.caption,
                maxLines = 1,
            )
        }
    }
}

/** A sub-folder inside the folder being browsed. Flat, like the song rows. */
@Composable
fun FolderRow(
    name: String,
    songCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val rowColor by animateColorAsState(
        targetValue = if (pressed) neo.textPrimary.copy(alpha = 0.06f) else neo.textPrimary.copy(alpha = 0f),
        label = "folderRowColor",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(rowColor, RoundedCornerShape(18.dp))
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(icon = NeoIcons.Folder, diameter = 54.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = neo.textPrimary,
                style = NeoType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (songCount == 1) "1 song" else "$songCount songs",
                color = neo.textSecondary,
                style = NeoType.caption,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = NeoIcons.Chevron,
            contentDescription = null,
            tint = neo.textTertiary,
            modifier = Modifier.size(22.dp),
        )
    }
}

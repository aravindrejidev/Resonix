package com.resonix.player.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.neoRaised
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/** A short message ("Can't play …", "7 songs found in …") that floats above the mini player. Tap to dismiss. */
@Composable
fun NeoToast(
    text: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .neoRaised(cornerRadius = 26.dp, elevation = 7.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(icon = NeoIcons.Info, diameter = 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            color = neo.textPrimary,
            style = NeoType.caption,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

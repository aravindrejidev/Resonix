package com.resonix.player.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.resonix.player.ui.neo.NeoDivider
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoSwitch
import com.resonix.player.ui.neo.neoFloating
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/**
 * The three-dot menu of the Now Playing screen: full-frame artwork on / off, keep-screen-on, and a
 * shortcut to the sound tools. Same look and behaviour as the Home menu — an overlay inside the
 * screen (not a separate window), closed by tapping anywhere outside the panel.
 */
@Composable
fun PlayerMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    fullFrameArt: Boolean,
    keepScreenOn: Boolean,
    onToggleFullFrameArt: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onOpenTools: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(140)) + scaleIn(
            animationSpec = tween(180),
            initialScale = 0.88f,
            transformOrigin = TransformOrigin(1f, 0f),
        ),
        exit = fadeOut(tween(110)) + scaleOut(
            animationSpec = tween(120),
            targetScale = 0.92f,
            transformOrigin = TransformOrigin(1f, 0f),
        ),
    ) {
        // Full-screen, invisible: a tap anywhere outside the panel closes the menu.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 70.dp, end = 16.dp, start = 28.dp, bottom = 28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(300.dp)
                        .neoFloating(cornerRadius = 24.dp, elevation = 16.dp)
                        .padding(vertical = 10.dp)
                        // Swallow taps on the panel itself so they don't reach the dismiss layer.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    MenuToggle(
                        icon = NeoIcons.Image,
                        label = "Full-frame artwork",
                        checked = fullFrameArt,
                        onToggle = onToggleFullFrameArt,
                    )
                    MenuToggle(
                        icon = NeoIcons.Phone,
                        label = "Keep screen on",
                        checked = keepScreenOn,
                        onToggle = onToggleKeepScreenOn,
                    )
                    NeoDivider(Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    MenuAction(
                        icon = NeoIcons.Tools,
                        label = "Sound tools",
                        onClick = {
                            onDismiss()
                            onOpenTools()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    val neo = LocalNeoColors.current
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(
                color = if (pressed) neo.accent.copy(alpha = 0.12f) else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(interactionSource = source, indication = null, role = Role.Switch) {
                haptic()
                onToggle()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(icon = icon, diameter = 38.dp, tint = neo.accent)
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = neo.textPrimary,
            style = NeoType.body,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        NeoSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun MenuAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val neo = LocalNeoColors.current
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .background(
                color = if (pressed) neo.accent.copy(alpha = 0.12f) else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(interactionSource = source, indication = null, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(icon = icon, diameter = 38.dp, tint = neo.accent)
        Spacer(Modifier.width(12.dp))
        Text(text = label, color = neo.textPrimary, style = NeoType.body, maxLines = 1)
    }
}

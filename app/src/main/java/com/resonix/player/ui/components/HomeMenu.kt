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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.resonix.player.ui.SortKey
import com.resonix.player.ui.neo.NeoChip
import com.resonix.player.ui.neo.NeoDivider
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoSwitch
import com.resonix.player.ui.neo.neoRaised
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType

/**
 * The three-dot menu on Home: add / scan folders, sort order, and a couple of quick display
 * toggles. Drawn as an overlay inside the screen (not a separate window) so its shadows aren't
 * clipped.
 */
@Composable
fun HomeMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    canRescan: Boolean,
    sortKey: SortKey,
    sortAscending: Boolean,
    showBadges: Boolean,
    showArt: Boolean,
    onAddFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onRescan: () -> Unit,
    onSortKey: (SortKey) -> Unit,
    onToggleDirection: () -> Unit,
    onToggleBadges: () -> Unit,
    onToggleArt: () -> Unit,
    onOpenSettings: () -> Unit,
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
                    .padding(top = 74.dp, end = 12.dp, start = 28.dp, bottom = 28.dp),
            ) {
                Column(
                    modifier = Modifier
                        .width(296.dp)
                        .neoRaised(cornerRadius = 28.dp, elevation = 10.dp)
                        .heightIn(max = 560.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 10.dp)
                        // Swallow taps on the panel itself so they don't reach the dismiss layer.
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        ),
                ) {
                    MenuItem(NeoIcons.FolderAdd, "Add music folder", onClick = { onDismiss(); onAddFolder() })
                    MenuItem(NeoIcons.Phone, "Scan device library", onClick = { onDismiss(); onScanDevice() })
                    MenuItem(
                        icon = NeoIcons.Refresh,
                        label = "Rescan this folder",
                        enabled = canRescan,
                        onClick = { onDismiss(); onRescan() },
                    )
                    NeoDivider(Modifier.padding(horizontal = 18.dp, vertical = 8.dp))

                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SORT SONGS BY",
                                color = LocalNeoColors.current.textTertiary,
                                style = NeoType.overline,
                                modifier = Modifier.weight(1f),
                            )
                            NeoIconButton(
                                icon = if (sortAscending) NeoIcons.Ascending else NeoIcons.Descending,
                                onClick = onToggleDirection,
                                diameter = 36.dp,
                                iconSize = 18.dp,
                                contentDescription = if (sortAscending) "Ascending" else "Descending",
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NeoChip(SortKey.TITLE.label, sortKey == SortKey.TITLE, onClick = { onSortKey(SortKey.TITLE) })
                            NeoChip(SortKey.ARTIST.label, sortKey == SortKey.ARTIST, onClick = { onSortKey(SortKey.ARTIST) })
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NeoChip(SortKey.DATE_ADDED.label, sortKey == SortKey.DATE_ADDED, onClick = { onSortKey(SortKey.DATE_ADDED) })
                            NeoChip(SortKey.DURATION.label, sortKey == SortKey.DURATION, onClick = { onSortKey(SortKey.DURATION) })
                        }
                        Spacer(Modifier.height(6.dp))
                    }

                    NeoDivider(Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    ToggleItem("Quality badges", showBadges, onToggleBadges)
                    ToggleItem("Album art", showArt, onToggleArt)
                    NeoDivider(Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
                    MenuItem(NeoIcons.Settings, "Settings", onClick = { onDismiss(); onOpenSettings() })
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
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
                color = if (pressed && enabled) neo.accent.copy(alpha = 0.12f) else Color.Transparent,
                shape = RoundedCornerShape(20.dp),
            )
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button) {
                haptic()
                onClick()
            }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(
            icon = icon,
            diameter = 38.dp,
            tint = if (enabled) neo.accent else neo.textTertiary,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = if (enabled) neo.textPrimary else neo.textTertiary,
            style = NeoType.body,
            maxLines = 1,
        )
    }
}

@Composable
private fun ToggleItem(label: String, checked: Boolean, onToggle: () -> Unit) {
    val neo = LocalNeoColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = neo.textPrimary,
            style = NeoType.body,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        NeoSwitch(checked = checked, onCheckedChange = { onToggle() })
    }
}

package com.resonix.player.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.resonix.player.data.ScannedFolder
import com.resonix.player.ui.OutputEngine
import com.resonix.player.ui.ReplayGainMode
import com.resonix.player.ui.SettingsState
import com.resonix.player.ui.neo.NeoCard
import com.resonix.player.ui.neo.NeoDivider
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoLoader
import com.resonix.player.ui.neo.NeoSectionLabel
import com.resonix.player.ui.neo.NeoSegmented
import com.resonix.player.ui.neo.NeoSwitch
import com.resonix.player.ui.neo.formatRelativeTime
import com.resonix.player.ui.neo.neoSurface
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.AccentColor
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import com.resonix.player.ui.theme.ThemeMode

/** Settings: appearance, library, display, audio engine and about. */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    folders: List<ScannedFolder>,
    scanningFolderUri: String?,
    isScanningDevice: Boolean,
    scrollState: ScrollState,
    bottomInset: Dp,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onAddFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onRescanFolder: (rootUri: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val neo = LocalNeoColors.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "dev"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenHeader(title = "Settings", subtitle = "Make Resonix yours")

        SettingsGroup("Appearance") {
            ChoiceRow(
                icon = NeoIcons.DarkMode,
                title = "Theme",
                subtitle = "Follow your phone, or pick one",
                options = ThemeMode.entries.map { it.label },
                selectedIndex = settings.themeMode.ordinal,
                onSelect = { index -> onChange { it.copy(themeMode = ThemeMode.entries[index]) } },
            )
            SettingsDivider()
            AccentRow(
                selected = settings.accent,
                onSelect = { accent -> onChange { it.copy(accent = accent) } },
            )
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Haptics,
                title = "Haptic feedback",
                subtitle = "A small tick when you press a control",
                checked = settings.haptics,
                onCheckedChange = { on -> onChange { it.copy(haptics = on) } },
            )
        }

        SettingsGroup("Library") {
            if (folders.isEmpty()) {
                SettingRow(
                    icon = NeoIcons.FolderOpen,
                    title = "No folders yet",
                    subtitle = "Add the folder your music lives in",
                )
            }
            folders.forEach { folder ->
                SettingRow(
                    icon = NeoIcons.Folder,
                    title = folder.displayName.ifBlank { "Music" },
                    subtitle = "Last scanned ${formatRelativeTime(folder.lastScannedAtSec)}",
                    trailing = {
                        if (scanningFolderUri == folder.rootUri) {
                            NeoLoader(diameter = 24.dp)
                        } else {
                            NeoIconButton(
                                icon = NeoIcons.Refresh,
                                onClick = { onRescanFolder(folder.rootUri) },
                                diameter = 40.dp,
                                iconSize = 20.dp,
                                contentDescription = "Rescan ${folder.displayName}",
                            )
                        }
                    },
                )
                SettingsDivider()
            }
            ActionRow(
                icon = NeoIcons.FolderAdd,
                title = "Add music folder",
                subtitle = "Pick a folder to play from",
                onClick = onAddFolder,
            )
            SettingsDivider()
            ActionRow(
                icon = NeoIcons.Phone,
                title = "Scan device library",
                subtitle = "Also find songs indexed by Android",
                onClick = onScanDevice,
                busy = isScanningDevice,
            )
        }

        SettingsGroup("Display") {
            ToggleRow(
                icon = NeoIcons.HiRes,
                title = "Quality badges",
                subtitle = "Show format, bit depth and sample rate in lists",
                checked = settings.showQualityBadges,
                onCheckedChange = { on -> onChange { it.copy(showQualityBadges = on) } },
            )
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Image,
                title = "Album art",
                subtitle = "Show cover thumbnails in lists",
                checked = settings.showAlbumArt,
                onCheckedChange = { on -> onChange { it.copy(showAlbumArt = on) } },
            )
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Phone,
                title = "Keep screen on",
                subtitle = "While the full-screen player is open",
                checked = settings.keepScreenOn,
                onCheckedChange = { on -> onChange { it.copy(keepScreenOn = on) } },
            )
        }

        SettingsGroup("Audio engine") {
            ChoiceRow(
                icon = NeoIcons.Headphones,
                title = "Output",
                subtitle = "Which audio backend to prefer",
                options = OutputEngine.entries.map { it.label },
                selectedIndex = settings.outputEngine.ordinal,
                onSelect = { index -> onChange { it.copy(outputEngine = OutputEngine.entries[index]) } },
            )
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Lock,
                title = "Exclusive mode",
                subtitle = "Ask for sole access to the output stream",
                checked = settings.exclusiveMode,
                onCheckedChange = { on -> onChange { it.copy(exclusiveMode = on) } },
            )
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Bolt,
                title = "Bit-perfect output",
                subtitle = "Match the file's sample rate — no resampling",
                checked = settings.bitPerfect,
                onCheckedChange = { on -> onChange { it.copy(bitPerfect = on) } },
            )
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Volume,
                title = "Direct volume control",
                subtitle = "Set the level inside the engine",
                checked = settings.directVolume,
                onCheckedChange = { on -> onChange { it.copy(directVolume = on) } },
            )
            SettingsDivider()
            ChoiceRow(
                icon = NeoIcons.Equalizer,
                title = "ReplayGain",
                subtitle = "Even out loudness between tracks",
                options = ReplayGainMode.entries.map { it.label },
                selectedIndex = settings.replayGain.ordinal,
                onSelect = { index -> onChange { it.copy(replayGain = ReplayGainMode.entries[index]) } },
            )
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Shield,
                title = "Peak limiter",
                subtitle = "Stop clipping when gain is applied",
                checked = settings.peakLimiter,
                onCheckedChange = { on -> onChange { it.copy(peakLimiter = on) } },
            )
        }

        SettingsGroup("About") {
            SettingRow(
                icon = NeoIcons.Music,
                title = "Resonix",
                subtitle = "Version $versionName",
            )
            SettingsDivider()
            SettingRow(
                icon = NeoIcons.Code,
                title = "Open source",
                subtitle = "Licensed under GPL-3.0",
            )
            SettingsDivider()
            SettingRow(
                icon = NeoIcons.Info,
                title = "Built with",
                subtitle = "Oboe · FFmpeg · Media3 · Jetpack Compose",
            )
        }

        Text(
            text = "Made for people who still care how it sounds.",
            color = neo.textTertiary,
            style = NeoType.caption,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ───────────────────────────── building blocks ─────────────────────────────

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NeoSectionLabel(title)
        NeoCard(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp),
            content = content,
        )
    }
}

@Composable
private fun SettingsDivider() {
    NeoDivider(Modifier.padding(horizontal = 18.dp))
}

/** Icon badge, title / subtitle, optional trailing control. Tappable when [onClick] is given. */
@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val neo = LocalNeoColors.current
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val clickModifier = if (onClick != null) {
        Modifier.clickable(interactionSource = source, indication = null, role = Role.Button) {
            haptic()
            onClick()
        }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .background(
                color = if (pressed) neo.accent.copy(alpha = 0.08f) else Color.Transparent,
                shape = RoundedCornerShape(24.dp),
            )
            .then(clickModifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconBadge(icon = icon, diameter = 42.dp)
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = neo.textPrimary, style = NeoType.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(text = subtitle, color = neo.textSecondary, style = NeoType.caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
        trailing = { NeoSwitch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun ActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    busy: Boolean = false,
) {
    val neo = LocalNeoColors.current
    SettingRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            if (busy) {
                NeoLoader(diameter = 24.dp)
            } else {
                Icon(
                    imageVector = NeoIcons.Chevron,
                    contentDescription = null,
                    tint = neo.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
    )
}

/** A row whose choice is picked from a segmented control underneath the title. */
@Composable
private fun ChoiceRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val neo = LocalNeoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconBadge(icon = icon, diameter = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = neo.textPrimary, style = NeoType.body, maxLines = 1)
                Text(text = subtitle, color = neo.textSecondary, style = NeoType.caption, maxLines = 2)
            }
        }
        Spacer(Modifier.height(14.dp))
        NeoSegmented(options = options, selectedIndex = selectedIndex, onSelect = onSelect)
    }
}

@Composable
private fun AccentRow(selected: AccentColor, onSelect: (AccentColor) -> Unit) {
    val neo = LocalNeoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoIconBadge(icon = NeoIcons.Palette, diameter = 42.dp)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Accent colour", color = neo.textPrimary, style = NeoType.body, maxLines = 1)
                Text(text = selected.label, color = neo.textSecondary, style = NeoType.caption, maxLines = 1)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccentColor.entries.forEach { accent ->
                AccentSwatch(
                    color = if (neo.isDark) accent.onDark else accent.onLight,
                    selected = accent == selected,
                    label = accent.label,
                    onClick = { onSelect(accent) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun AccentSwatch(color: Color, selected: Boolean, label: String, onClick: () -> Unit) {
    val haptic = rememberHaptic()
    val source = remember { MutableInteractionSource() }
    val depth = animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 500f),
        label = "accentSwatchDepth",
    )
    val onColor = if (color.luminance() > 0.45f) Color(0xFF14171C) else Color.White
    Box(
        modifier = Modifier
            .size(50.dp)
            .neoSurface(cornerRadius = 25.dp, elevation = 4.dp, depth = { depth.value })
            .clickable(interactionSource = source, indication = null, role = Role.RadioButton) {
                haptic()
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = NeoIcons.Check,
                    contentDescription = label,
                    tint = onColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

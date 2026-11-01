package com.resonix.player.ui.screens

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.resonix.player.data.ScannedFolder
import com.resonix.player.playback.OutputRoute
import com.resonix.player.playback.RouteKind
import com.resonix.player.ui.DitherMode
import com.resonix.player.ui.OutputEngine
import com.resonix.player.ui.OutputFormat
import com.resonix.player.ui.ReplayGainMode
import com.resonix.player.ui.ResamplerQuality
import com.resonix.player.ui.SettingsState
import com.resonix.player.ui.neo.NeoCard
import com.resonix.player.ui.neo.NeoDivider
import com.resonix.player.ui.neo.NeoIconBadge
import com.resonix.player.ui.neo.NeoIconButton
import com.resonix.player.ui.neo.NeoIcons
import com.resonix.player.ui.neo.NeoLoader
import com.resonix.player.ui.neo.NeoSectionLabel
import com.resonix.player.ui.neo.NeoSegmented
import com.resonix.player.ui.neo.NeoSlider
import com.resonix.player.ui.neo.NeoSwitch
import com.resonix.player.ui.neo.formatRelativeTime
import com.resonix.player.ui.neo.neoSurface
import com.resonix.player.ui.neo.rememberHaptic
import com.resonix.player.ui.theme.AccentColor
import com.resonix.player.ui.theme.LocalNeoColors
import com.resonix.player.ui.theme.NeoType
import com.resonix.player.ui.theme.ThemeMode

/**
 * The sections of Settings. The main page lists them as rows (with a one-line summary of what is
 * currently chosen); each opens its own page of grouped settings.
 */
enum class SettingsPage(val title: String, val tagline: String) {
    APPEARANCE("Appearance", "Theme, colour and feedback"),
    PLAYER("Player", "Now Playing and notifications"),
    LIBRARY("Library", "Folders, scanning and lists"),
    AUDIO("Audio engine", "Output, volume and loudness"),
    ABOUT("About", "Version and licences"),
}

private fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.APPEARANCE -> NeoIcons.Palette
    SettingsPage.PLAYER -> NeoIcons.Music
    SettingsPage.LIBRARY -> NeoIcons.Library
    SettingsPage.AUDIO -> NeoIcons.Equalizer
    SettingsPage.ABOUT -> NeoIcons.Info
}

/**
 * Settings, in sections. [page] is the section that is open (`null` = the main list); the caller
 * owns it so the system Back button can step out of a section.
 */
@Composable
fun SettingsScreen(
    settings: SettingsState,
    folders: List<ScannedFolder>,
    scanningFolderUri: String?,
    isScanningDevice: Boolean,
    scrollState: ScrollState,
    bottomInset: Dp,
    outputRoute: OutputRoute,
    page: SettingsPage?,
    onPageChange: (SettingsPage?) -> Unit,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onAddFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onRescanFolder: (rootUri: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "dev"
    }

    // Whether the playback notification (and with it the lock-screen / quick-settings media
    // player) is allowed to show. Re-checked whenever the user comes back from system settings.
    var notificationsOn by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val activity = context as? ComponentActivity
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    Crossfade(
        targetState = page,
        modifier = modifier.fillMaxSize(),
        animationSpec = tween(200),
        label = "settingsPage",
    ) { shown ->
        if (shown == null) {
            SettingsHome(
                settings = settings,
                outputRoute = outputRoute,
                folderCount = folders.size,
                versionName = versionName,
                scrollState = scrollState,
                bottomInset = bottomInset,
                onOpen = { onPageChange(it) },
            )
        } else {
            SettingsDetail(
                page = shown,
                bottomInset = bottomInset,
                onBack = { onPageChange(null) },
            ) {
                when (shown) {
                    SettingsPage.APPEARANCE -> AppearanceSection(settings, onChange)
                    SettingsPage.PLAYER -> PlayerSection(settings, notificationsOn, onChange) {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            // No settings page to open on this device — nothing more we can do.
                        }
                    }
                    SettingsPage.LIBRARY -> LibrarySection(
                        settings = settings,
                        folders = folders,
                        scanningFolderUri = scanningFolderUri,
                        isScanningDevice = isScanningDevice,
                        onChange = onChange,
                        onAddFolder = onAddFolder,
                        onScanDevice = onScanDevice,
                        onRescanFolder = onRescanFolder,
                    )
                    SettingsPage.AUDIO -> AudioSection(settings, outputRoute, onChange)
                    SettingsPage.ABOUT -> AboutSection(versionName)
                }
            }
        }
    }
}

// ───────────────────────────── main list ─────────────────────────────

@Composable
private fun SettingsHome(
    settings: SettingsState,
    outputRoute: OutputRoute,
    folderCount: Int,
    versionName: String,
    scrollState: ScrollState,
    bottomInset: Dp,
    onOpen: (SettingsPage) -> Unit,
) {
    val neo = LocalNeoColors.current

    fun summary(page: SettingsPage): String = when (page) {
        SettingsPage.APPEARANCE -> "${settings.themeMode.label} · ${settings.accent.label}"
        SettingsPage.PLAYER -> if (settings.fullFrameArt) "Full-frame artwork" else "Artwork on a plate"
        SettingsPage.LIBRARY -> when (folderCount) {
            0 -> "No folders yet"
            1 -> "1 folder"
            else -> "$folderCount folders"
        }
        SettingsPage.AUDIO -> buildString {
            append(outputRoute.kind.label)
            if (settings.exclusiveMode) append(" · Exclusive")
            if (settings.bitPerfect) append(" · Bit-perfect")
            if (settings.directVolume) append(" · DVC")
        }
        SettingsPage.ABOUT -> "Version $versionName"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        ScreenHeader(title = "Settings", subtitle = "Make Resonix yours")

        SettingsGroup("Preferences") {
            SettingsEntry(SettingsPage.APPEARANCE, summary(SettingsPage.APPEARANCE), onOpen)
            SettingsDivider()
            SettingsEntry(SettingsPage.PLAYER, summary(SettingsPage.PLAYER), onOpen)
            SettingsDivider()
            SettingsEntry(SettingsPage.LIBRARY, summary(SettingsPage.LIBRARY), onOpen)
        }

        SettingsGroup("Sound") {
            SettingsEntry(SettingsPage.AUDIO, summary(SettingsPage.AUDIO), onOpen)
        }

        SettingsGroup("App") {
            SettingsEntry(SettingsPage.ABOUT, summary(SettingsPage.ABOUT), onOpen)
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

/** A row on the main list: the section's icon and name, with what is currently chosen under it. */
@Composable
private fun SettingsEntry(page: SettingsPage, summary: String, onOpen: (SettingsPage) -> Unit) {
    ActionRow(
        icon = page.icon(),
        title = page.title,
        subtitle = summary,
        onClick = { onOpen(page) },
    )
}

/** One section's page: a back button and title on top, then its groups. */
@Composable
private fun SettingsDetail(
    page: SettingsPage,
    bottomInset: Dp,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    val neo = LocalNeoColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 22.dp, end = 22.dp, bottom = bottomInset),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(icon = NeoIcons.Back, onClick = onBack, contentDescription = "Back to settings")
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "SETTINGS", color = neo.accent, style = NeoType.overline)
                Text(text = page.title, color = neo.textPrimary, style = NeoType.title, maxLines = 1)
                Text(text = page.tagline, color = neo.textSecondary, style = NeoType.caption, maxLines = 1)
            }
        }
        content()
    }
}

// ───────────────────────────── the sections ─────────────────────────────

@Composable
private fun AppearanceSection(
    settings: SettingsState,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
) {
    SettingsGroup("Theme") {
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
    }
    SettingsGroup("Feedback") {
        ToggleRow(
            icon = NeoIcons.Haptics,
            title = "Haptic feedback",
            subtitle = "A small tick when you press a control",
            checked = settings.haptics,
            onCheckedChange = { on -> onChange { it.copy(haptics = on) } },
        )
    }
}

@Composable
private fun PlayerSection(
    settings: SettingsState,
    notificationsOn: Boolean,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    SettingsGroup("Now Playing") {
        ToggleRow(
            icon = NeoIcons.Image,
            title = "Full-frame artwork",
            subtitle = "The cover fills the top of the player and tints its background. Also in the player's ⋮ menu",
            checked = settings.fullFrameArt,
            onCheckedChange = { on -> onChange { it.copy(fullFrameArt = on) } },
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
    SettingsGroup("Notifications") {
        ActionRow(
            icon = NeoIcons.Headphones,
            title = "Notification controls",
            subtitle = if (notificationsOn) {
                "On — play, pause and skip from the notification and lock screen"
            } else {
                "Off — tap to allow notifications so the phone's media player can show"
            },
            onClick = onOpenNotificationSettings,
        )
    }
}

@Composable
private fun LibrarySection(
    settings: SettingsState,
    folders: List<ScannedFolder>,
    scanningFolderUri: String?,
    isScanningDevice: Boolean,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
    onAddFolder: () -> Unit,
    onScanDevice: () -> Unit,
    onRescanFolder: (rootUri: String) -> Unit,
) {
    SettingsGroup("Music folders") {
        if (folders.isEmpty()) {
            SettingRow(
                icon = NeoIcons.FolderOpen,
                title = "No folders yet",
                subtitle = "Add the folder your music lives in",
            )
            SettingsDivider()
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
    }
    SettingsGroup("Device") {
        ActionRow(
            icon = NeoIcons.Phone,
            title = "Scan device library",
            subtitle = "Also find songs indexed by Android",
            onClick = onScanDevice,
            busy = isScanningDevice,
        )
    }
    SettingsGroup("Song lists") {
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
    }
}

@Composable
private fun AudioSection(
    settings: SettingsState,
    route: OutputRoute,
    onChange: ((SettingsState) -> SettingsState) -> Unit,
) {
    SettingsGroup("Connected output") {
        SettingRow(
            icon = if (route.kind == RouteKind.SPEAKER) NeoIcons.Phone else NeoIcons.Headphones,
            title = route.title(),
            subtitle = route.summary(settings.usbBitPerfect),
        )
    }
    SettingsGroup("Output") {
        ChoiceRow(
            icon = NeoIcons.Headphones,
            title = "Output",
            subtitle = "Auto picks the best backend; if one can't open, the other is used",
            options = OutputEngine.entries.map { it.label },
            selectedIndex = settings.outputEngine.ordinal,
            onSelect = { index -> onChange { it.copy(outputEngine = OutputEngine.entries[index]) } },
        )
        SettingsDivider()
        ToggleRow(
            icon = NeoIcons.Lock,
            title = "Exclusive mode",
            subtitle = "Ask for sole access to the output stream (AAudio) — skips Android's mixer and its effects",
            checked = settings.exclusiveMode,
            onCheckedChange = { on -> onChange { it.copy(exclusiveMode = on) } },
        )
        SettingsDivider()
        ToggleRow(
            icon = NeoIcons.Bolt,
            title = "Bit-perfect output",
            subtitle = "On: each song plays at its own sample rate, or the nearest one the output accepts. Off: everything at the device's rate",
            checked = settings.bitPerfect,
            onCheckedChange = { on -> onChange { it.copy(bitPerfect = on) } },
        )
        SettingsDivider()
        ToggleRow(
            icon = NeoIcons.Storage,
            title = "USB DAC bit-perfect (Android 14+)",
            subtitle = "The system hands the engine's samples to the DAC unchanged — no resampling, no effects. Volume then comes from Direct volume control",
            checked = settings.usbBitPerfect,
            onCheckedChange = { on -> onChange { it.copy(usbBitPerfect = on) } },
        )
    }
    SettingsGroup("Fidelity") {
        ChoiceRow(
            icon = NeoIcons.HiRes,
            title = "Bits per sample",
            subtitle = "What the engine delivers. Auto sends 32-bit float. 16 / 24 round the signal itself, with dither",
            options = OutputFormat.entries.map { it.label },
            selectedIndex = settings.outputFormat.ordinal,
            onSelect = { index -> onChange { it.copy(outputFormat = OutputFormat.entries[index]) } },
        )
        SettingsDivider()
        ChoiceRow(
            icon = NeoIcons.Equalizer,
            title = "Dither",
            subtitle = "Noise that keeps quiet detail intact when rounding to 16 / 24 bit. Auto adds it only when the signal was changed",
            options = DitherMode.entries.map { it.label },
            selectedIndex = settings.dither.ordinal,
            onSelect = { index -> onChange { it.copy(dither = DitherMode.entries[index]) } },
        )
        SettingsDivider()
        ChoiceRow(
            icon = NeoIcons.Tools,
            title = "Resampler quality",
            subtitle = "How carefully a sample-rate change is calculated, when one is needed",
            options = ResamplerQuality.entries.map { it.label },
            selectedIndex = settings.resamplerQuality.ordinal,
            onSelect = { index -> onChange { it.copy(resamplerQuality = ResamplerQuality.entries[index]) } },
        )
    }
    SettingsGroup("Volume") {
        ToggleRow(
            icon = NeoIcons.Volume,
            title = "Direct volume control",
            subtitle = "The engine sets the volume (64-bit, dithered). The volume buttons control it",
            checked = settings.directVolume,
            onCheckedChange = { on -> onChange { it.copy(directVolume = on) } },
        )
        if (settings.directVolume) {
            SettingsDivider()
            ToggleRow(
                icon = NeoIcons.Lock,
                title = "Park system volume at maximum",
                subtitle = "While music plays; put back afterwards at the same loudness. Off: DVC works on top of it",
                checked = settings.dvcLockSystemVolume,
                onCheckedChange = { on -> onChange { it.copy(dvcLockSystemVolume = on) } },
            )
            SettingsDivider()
            SliderRow(
                title = "Volume level",
                valueText = "${(settings.directVolumeLevel * 100f).toInt()}%",
                value = settings.directVolumeLevel,
                onValueChange = { level -> onChange { it.copy(directVolumeLevel = level) } },
            )
        }
    }
    SettingsGroup("Loudness") {
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
}

/** "USB DAC · FiiO KA3", "AUX · Wired headphones", "Bluetooth · WH-1000XM5"… */
private fun OutputRoute.title(): String =
    if (name.isBlank() || kind == RouteKind.SPEAKER) kind.label else "${kind.label} · $name"

/** One line on what Resonix can do with this output. */
private fun OutputRoute.summary(usbBitPerfectOn: Boolean): String = when (kind) {
    RouteKind.USB_DAC -> buildString {
        if (maxSampleRateHz > 0) append("Up to ${maxSampleRateHz / 1000} kHz. ")
        append(
            when {
                Build.VERSION.SDK_INT < 34 -> "Bit-perfect mode needs Android 14 — using the normal path."
                bitPerfectFormats == 0 -> "This DAC does not offer the system's bit-perfect mode."
                usbBitPerfectOn -> "Bit-perfect mode available, used for every song it fits."
                else -> "Bit-perfect mode available but switched off."
            }
        )
    }
    RouteKind.WIRED -> "The phone's own DAC and amplifier. Exclusive mode and Bit-perfect give it the cleanest path."
    RouteKind.BLUETOOTH -> "Sound quality is decided by the Bluetooth codec, not by the player."
    RouteKind.HDMI -> "Digital output to a display or receiver."
    RouteKind.SPEAKER -> "Built-in speaker."
    RouteKind.OTHER -> "Android decides where the sound goes."
}

@Composable
private fun AboutSection(versionName: String) {
    SettingsGroup("Resonix") {
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
            subtitle = "Oboe · FFmpeg · Jetpack Compose",
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

/** A row with a title, its current value and a slider underneath. */
@Composable
private fun SliderRow(
    title: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val neo = LocalNeoColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = title, color = neo.textPrimary, style = NeoType.body, modifier = Modifier.weight(1f))
            Text(text = valueText, color = neo.textSecondary, style = NeoType.caption)
        }
        NeoSlider(value = value, onValueChange = onValueChange)
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

package com.resonix.player.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val LocalNeoColors = staticCompositionLocalOf { neoColors(dark = true, accent = AccentColor.CRIMSON) }

/** Whether neumorphic controls give a small haptic tick when pressed (Settings → Appearance). */
val LocalNeoHaptics = staticCompositionLocalOf { true }

/** Text styles used across the app. Colours are always passed explicitly. */
object NeoType {
    val display = TextStyle(fontSize = 32.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.8).sp)
    val title = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp)
    val heading = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val bodyRegular = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal)
    val caption = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val tiny = TextStyle(fontSize = 10.5.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
    val overline = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.4.sp)
}

@Composable
fun ResonixTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: AccentColor = AccentColor.CRIMSON,
    haptics: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = remember(dark, accent) { neoColors(dark, accent) }

    // Keep the status / navigation bar icons readable on whichever surface
    // colour is active — the in-app theme can differ from the system one.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }

    val materialScheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.surface,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.surface,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
        )
    }

    CompositionLocalProvider(
        LocalNeoColors provides colors,
        LocalNeoHaptics provides haptics,
        LocalTextSelectionColors provides TextSelectionColors(
            handleColor = colors.accent,
            backgroundColor = colors.accent.copy(alpha = 0.30f),
        ),
    ) {
        MaterialTheme(colorScheme = materialScheme, content = content)
    }
}

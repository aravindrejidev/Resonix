package com.resonix.player.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ResonixColorScheme = darkColorScheme(
    primary = ResonixCrimson,
    onPrimary = ResonixTextPrimary,
    secondary = ResonixCrimson,
    onSecondary = ResonixTextPrimary,
    background = ResonixBlack,
    onBackground = ResonixTextPrimary,
    surface = ResonixSurface,
    onSurface = ResonixTextPrimary,
    surfaceVariant = ResonixSurfaceVariant,
    onSurfaceVariant = ResonixTextSecondary,
    error = ResonixCrimson,
    onError = ResonixTextPrimary
)

val ResonixTypography = Typography(
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp)
)

/**
 * Always the fixed pitch-black/crimson scheme — this app's identity is
 * deliberately brand-specific (see the mockups), not wallpaper-adaptive.
 */
@Composable
fun ResonixTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ResonixColorScheme,
        typography = ResonixTypography,
        content = content
    )
}

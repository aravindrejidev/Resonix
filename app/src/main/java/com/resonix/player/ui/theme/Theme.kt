package com.resonix.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ResonixDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB8C4FF),
    secondary = Color(0xFFC2C6DD),
    tertiary = Color(0xFFE5BAD8)
)

private val ResonixLightColorScheme = lightColorScheme(
    primary = Color(0xFF4355B9),
    secondary = Color(0xFF5B5D72),
    tertiary = Color(0xFF75546A)
)

val ResonixTypography = Typography(
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp
    )
)

/**
 * App theme. Uses Material You dynamic color (wallpaper-derived) on
 * Android 12+ when [dynamicColor] is true, falling back to the static
 * Resonix color schemes above on older devices.
 */
@Composable
fun ResonixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> ResonixDarkColorScheme
        else -> ResonixLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ResonixTypography,
        content = content
    )
}

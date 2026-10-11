package com.resonix.player.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Light / dark / follow-the-system, chosen in Settings → Appearance. */
enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

/** Accent presets, chosen in Settings → Appearance. */
enum class AccentColor(val label: String, val onDark: Color, val onLight: Color) {
    CRIMSON("Crimson", Color(0xFFFF5A64), Color(0xFFE5333F)),
    KIWI("Kiwi", Color(0xFF9BE15D), Color(0xFF5BAA1C)),
    OCEAN("Ocean", Color(0xFF4DA3FF), Color(0xFF2B7BE0)),
    VIOLET("Violet", Color(0xFFA78BFA), Color(0xFF7655E3)),
    AMBER("Amber", Color(0xFFFFB547), Color(0xFFE08A00)),
}

/**
 * Everything the neumorphic components need to draw themselves.
 *
 * Neumorphism = one surface colour + two shadows cast from a light source in
 * the top-left: a light one (towards the light) and a dark one (away from it).
 */
@Immutable
class NeoColors(
    val isDark: Boolean,
    /** The one colour every surface (and the screen background) is made of. */
    val surface: Color,
    /** Highlight shadow, cast towards the top-left. */
    val shadowLight: Color,
    /** Depth shadow, cast towards the bottom-right. */
    val shadowDark: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val onAccent: Color,
) {
    val accentHigh: Color get() = lerp(accent, Color.White, 0.22f)
    val accentLow: Color get() = lerp(accent, Color.Black, 0.16f)

    /** Slightly lighter version of [base] — the lit side of a convex surface. */
    fun lit(base: Color): Color = lerp(base, Color.White, if (isDark) 0.055f else 0.55f)

    /** Slightly darker version of [base] — the shaded side of a convex surface. */
    fun shaded(base: Color): Color = lerp(base, Color.Black, if (isDark) 0.17f else 0.07f)

    /** Hairline highlight along the top-left edge of raised surfaces. */
    val edgeLight: Color get() = Color.White.copy(alpha = if (isDark) 0.10f else 0.90f)
}

fun neoColors(dark: Boolean, accent: AccentColor): NeoColors {
    val accentColor = if (dark) accent.onDark else accent.onLight
    val onAccent = if (accentColor.luminance() > 0.45f) Color(0xFF14171C) else Color.White
    return if (dark) {
        NeoColors(
            isDark = true,
            surface = Color(0xFF22252C),
            shadowLight = Color(0xFF30343F),
            shadowDark = Color(0xFF121418),
            textPrimary = Color(0xFFECEFF5),
            textSecondary = Color(0xFF9AA2B5),
            textTertiary = Color(0xFF667085),
            accent = accentColor,
            onAccent = onAccent,
        )
    } else {
        NeoColors(
            isDark = false,
            surface = Color(0xFFE3E7EE),
            shadowLight = Color(0xFFFFFFFF),
            shadowDark = Color(0xFFA9B4C7),
            textPrimary = Color(0xFF2A3042),
            textSecondary = Color(0xFF5F6980),
            textTertiary = Color(0xFF8F99AE),
            accent = accentColor,
            onAccent = onAccent,
        )
    }
}

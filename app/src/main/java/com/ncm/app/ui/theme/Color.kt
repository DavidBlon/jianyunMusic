package com.ncm.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val DefaultGreen500 = Color(0xFF0A84FF)
val DefaultAccentSecondary = Color(0xFF0A84FF)
val DefaultAccentHighlight = Color(0xFF5AC8FA)
val LocalAccentColor = staticCompositionLocalOf { DefaultGreen500 }
val LocalAccentSecondaryColor = staticCompositionLocalOf { DefaultAccentSecondary }
val LocalAccentHighlightColor = staticCompositionLocalOf { DefaultAccentHighlight }
val Green500: Color
    @Composable get() = LocalAccentColor.current
val AccentSecondary: Color
    @Composable get() = LocalAccentSecondaryColor.current
val AccentHighlight: Color
    @Composable get() = LocalAccentHighlightColor.current

@Composable
fun accentBrush(): Brush = Brush.linearGradient(
    listOf(AccentHighlight, Green500, AccentSecondary)
)

val Green600 = Color(0xFF007AFF)
val Green700 = Color(0xFF1C1C1E)
val Green800 = Color(0xFF2C2C2E)
val GreenAccent = Color(0xFF007AFF)

@Immutable
data class AppPalette(
    val isLight: Boolean,
    val bg: Color,
    val bg2: Color,
    val bg3: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val error: Color,
    val orange: Color,
    val blue: Color,
    val purple: Color,
    val glass: Color,
    val glassStrong: Color,
    val glassSoft: Color,
    val glassBorderTop: Color,
    val glassBorderTopStrong: Color,
    val glassBorderBottom: Color,
    val shadowAmbient: Float,
    val shadowSpot: Float
)

val DarkPalette = AppPalette(
    isLight = false,
    bg = Color(0xFF0E0E10),
    bg2 = Color(0xFF1C1C1E),
    bg3 = Color(0xFF2C2C2E),
    surface = Color(0xFF1C1C1E),
    surface2 = Color(0xFF3A3A3C),
    border = Color(0xFF38383A),
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFFB0B0B8),
    textTertiary = Color(0xFF8E8E93),
    error = Color(0xFFFF453A),
    orange = Color(0xFFFF9F0A),
    blue = Color(0xFF0A84FF),
    purple = Color(0xFFBF5AF2),
    glass = Color(0xD91C1C1E),
    glassStrong = Color(0xF01C1C1E),
    glassSoft = Color(0x661C1C1E),
    glassBorderTop = Color(0x1AFFFFFF),
    glassBorderTopStrong = Color(0x24FFFFFF),
    glassBorderBottom = Color(0x08FFFFFF),
    shadowAmbient = 0.10f,
    shadowSpot = 0.30f
)

val LightPalette = AppPalette(
    isLight = true,
    bg = Color(0xFFF2F2F7),
    bg2 = Color(0xFFFFFFFF),
    bg3 = Color(0xFFF2F2F7),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFE5E5EA),
    border = Color(0xFFD1D1D6),
    textPrimary = Color(0xFF1C1C1E),
    textSecondary = Color(0xFF6E6E73),
    textTertiary = Color(0xFF98989F),
    error = Color(0xFFFF3B30),
    orange = Color(0xFFFF9500),
    blue = Color(0xFF007AFF),
    purple = Color(0xFFAF52DE),
    glass = Color(0xE6FFFFFF),
    glassStrong = Color(0xF7FFFFFF),
    glassSoft = Color(0xB3FFFFFF),
    glassBorderTop = Color(0xFFE5E5EA),
    glassBorderTopStrong = Color(0xFFD1D1D6),
    glassBorderBottom = Color(0xFFF0F0F4),
    shadowAmbient = 0.05f,
    shadowSpot = 0.10f
)

val LocalAppPalette = staticCompositionLocalOf { DarkPalette }

val DarkBg: Color
    @Composable get() = LocalAppPalette.current.bg
val DarkBg2: Color
    @Composable get() = LocalAppPalette.current.bg2
val DarkBg3: Color
    @Composable get() = LocalAppPalette.current.bg3
val DarkSurface: Color
    @Composable get() = LocalAppPalette.current.surface
val DarkSurface2: Color
    @Composable get() = LocalAppPalette.current.surface2
val DarkBorder: Color
    @Composable get() = LocalAppPalette.current.border

val TextPrimary: Color
    @Composable get() = LocalAppPalette.current.textPrimary
val TextSecondary: Color
    @Composable get() = LocalAppPalette.current.textSecondary
val TextTertiary: Color
    @Composable get() = LocalAppPalette.current.textTertiary

val RedAccent: Color
    @Composable get() = LocalAppPalette.current.error
val OrangeAccent: Color
    @Composable get() = LocalAppPalette.current.orange
val BlueAccent: Color
    @Composable get() = LocalAppPalette.current.blue
val PurpleAccent: Color
    @Composable get() = LocalAppPalette.current.purple

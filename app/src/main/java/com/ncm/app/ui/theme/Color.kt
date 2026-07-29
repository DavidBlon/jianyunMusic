package com.ncm.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

val DefaultGreen500 = Color(0xFF72D69B)
val DefaultAccentSecondary = Color(0xFF4DB8A5)
val DefaultAccentHighlight = Color(0xFFD6B86B)
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
val Green600 = Color(0xFF54BC69)
val Green700 = Color(0xFF3F9654)
val Green800 = Color(0xFF2F7140)
val GreenAccent = Color(0xFF78E68B)

val DarkBg = Color(0xFF090C10)
val DarkBg2 = Color(0xFF10151A)
val DarkBg3 = Color(0xFF151B21)
val DarkSurface = Color(0xFF1A2027)
val DarkSurface2 = Color(0xFF242B33)
val DarkBorder = Color(0xFF303943)

val TextPrimary = Color(0xFFF5F7F9)
val TextSecondary = Color(0xFFA9B1BA)
val TextTertiary = Color(0xFF7A858F)

val RedAccent = Color(0xFFE74C3C)
val OrangeAccent = Color(0xFFF39C12)
val BlueAccent = Color(0xFF4A90D9)
val PurpleAccent = Color(0xFF9B59B6)

val GradientStart1 = Color(0xFF1A3A1E)
val GradientEnd1 = Color(0xFF0D1F10)
val GradientOverlay1 = Color(0xFF1A1A2E)

val LikeRed = Color(0xFFE74C3C)
val MiniPlayerBg = GlassSurfaceStrong

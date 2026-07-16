package com.ncm.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val DefaultGreen500 = Color(0xFF58B86A)
val LocalAccentColor = staticCompositionLocalOf { DefaultGreen500 }
val Green500: Color
    @Composable get() = LocalAccentColor.current
val Green600 = Color(0xFF4AA85C)
val Green700 = Color(0xFF3D8B4F)
val Green800 = Color(0xFF2D6B3C)
val GreenAccent = Color(0xFF62D975)

val DarkBg = Color(0xFF0A0A0C)
val DarkBg2 = Color(0xFF121214)
val DarkBg3 = Color(0xFF1A1A1E)
val DarkSurface = Color(0xFF1E1E24)
val DarkSurface2 = Color(0xFF28282E)
val DarkBorder = Color(0xFF2A2A30)

val TextPrimary = Color(0xFFF0F0F2)
val TextSecondary = Color(0xFF909098)
val TextTertiary = Color(0xFF55555E)

val RedAccent = Color(0xFFE74C3C)
val OrangeAccent = Color(0xFFF39C12)
val BlueAccent = Color(0xFF4A90D9)
val PurpleAccent = Color(0xFF9B59B6)

val GradientStart1 = Color(0xFF1A3A1E)
val GradientEnd1 = Color(0xFF0D1F10)
val GradientOverlay1 = Color(0xFF1A1A2E)

val LikeRed = Color(0xFFE74C3C)
val MiniPlayerBg = Color(0xFF1E1E24)

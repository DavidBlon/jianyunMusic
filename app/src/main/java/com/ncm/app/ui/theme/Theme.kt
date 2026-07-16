package com.ncm.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun darkColorSchemeFor(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = TextPrimary,
    primaryContainer = Green700,
    onPrimaryContainer = TextPrimary,
    secondary = Green600,
    onSecondary = TextPrimary,
    background = DarkBg,
    onBackground = TextPrimary,
    surface = DarkBg2,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurface,
    onSurfaceVariant = TextSecondary,
    outline = DarkBorder,
    outlineVariant = DarkSurface2,
    error = RedAccent,
    onError = TextPrimary,
    surfaceTint = accent
)

@Composable
fun NeteaseMusicTheme(
    accent: Color = DefaultGreen500,
    content: @Composable () -> Unit
) {
    val colorScheme = darkColorSchemeFor(accent)
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    CompositionLocalProvider(LocalAccentColor provides accent) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}

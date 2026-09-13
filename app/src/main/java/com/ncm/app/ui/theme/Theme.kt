package com.ncm.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun darkColorSchemeFor(
    accent: Color,
    secondaryAccent: Color,
    highlightAccent: Color,
    palette: AppPalette
) = darkColorScheme(
    primary = accent,
    onPrimary = palette.textPrimary,
    primaryContainer = palette.surface2,
    onPrimaryContainer = palette.textPrimary,
    secondary = secondaryAccent,
    onSecondary = palette.textPrimary,
    tertiary = highlightAccent,
    onTertiary = palette.bg,
    background = palette.bg,
    onBackground = palette.textPrimary,
    surface = palette.bg2,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surface,
    onSurfaceVariant = palette.textSecondary,
    outline = palette.border,
    outlineVariant = palette.surface2,
    error = palette.error,
    onError = Color.White,
    surfaceTint = accent,
    inverseSurface = palette.textPrimary,
    inverseOnSurface = palette.bg
)

private fun lightColorSchemeFor(
    accent: Color,
    secondaryAccent: Color,
    highlightAccent: Color,
    palette: AppPalette
) = lightColorScheme(
    primary = accent,
    onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.14f),
    onPrimaryContainer = palette.textPrimary,
    secondary = secondaryAccent,
    onSecondary = Color.White,
    tertiary = highlightAccent,
    onTertiary = Color.White,
    background = palette.bg,
    onBackground = palette.textPrimary,
    surface = palette.bg2,
    onSurface = palette.textPrimary,
    surfaceVariant = palette.surface2,
    onSurfaceVariant = palette.textSecondary,
    outline = palette.border,
    outlineVariant = palette.surface2,
    error = palette.error,
    onError = Color.White,
    surfaceTint = accent,
    inverseSurface = palette.textPrimary,
    inverseOnSurface = palette.bg
)

@Composable
fun NeteaseMusicTheme(
    accentTheme: AccentTheme = AccentTheme.BLUE,
    mode: AppThemeMode = AppThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isLight = when (mode) {
        AppThemeMode.SYSTEM -> !isSystemInDarkTheme()
        AppThemeMode.LIGHT -> true
        AppThemeMode.DARK -> false
    }
    val palette = if (isLight) LightPalette else DarkPalette
    val accent = if (isLight) accentTheme.lightColor else accentTheme.color
    val secondaryAccent = if (isLight) accentTheme.lightSecondary else accentTheme.secondary
    val highlightAccent = if (isLight) accentTheme.lightHighlight else accentTheme.highlight

    val colorScheme = if (isLight) {
        lightColorSchemeFor(accent, secondaryAccent, highlightAccent, palette)
    } else {
        darkColorSchemeFor(accent, secondaryAccent, highlightAccent, palette)
    }
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = isLight
                isAppearanceLightNavigationBars = isLight
            }
        }
    }

    CompositionLocalProvider(
        LocalAppPalette provides palette,
        LocalAccentColor provides accent,
        LocalAccentSecondaryColor provides secondaryAccent,
        LocalAccentHighlightColor provides highlightAccent
    ) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}

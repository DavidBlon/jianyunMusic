package com.ncm.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AppThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

enum class AccentTheme(
    val label: String,
    val color: Color,
    val secondary: Color,
    val highlight: Color,
    val lightColor: Color,
    val lightSecondary: Color,
    val lightHighlight: Color
) {
    BLUE(
        "蓝色",
        Color(0xFF0A84FF), Color(0xFF0A84FF), Color(0xFF5AC8FA),
        Color(0xFF007AFF), Color(0xFF007AFF), Color(0xFF5AC8FA)
    ),
    TEAL(
        "青绿色",
        Color(0xFF64D2FF), Color(0xFF64D2FF), Color(0xFF00C7BE),
        Color(0xFF32ADE6), Color(0xFF32ADE6), Color(0xFF00C7BE)
    ),
    PURPLE(
        "紫色",
        Color(0xFFBF5AF2), Color(0xFFDA62FF), Color(0xFFC99CFF),
        Color(0xFFAF52DE), Color(0xFFA94FE0), Color(0xFFBF5AF2)
    ),
    ORANGE(
        "橙色",
        Color(0xFFFF9F0A), Color(0xFFBF8430), Color(0xFFFFD60A),
        Color(0xFFFF9500), Color(0xFFF08700), Color(0xFFFFCC00)
    ),
    RED(
        "红色",
        Color(0xFFFF453A), Color(0xFFFF6961), Color(0xFFFF8A80),
        Color(0xFFFF3B30), Color(0xFFFF6259), Color(0xFFFF8A80)
    ),
    GREEN(
        "绿色",
        Color(0xFF32D74B), Color(0xFF30D158), Color(0xFF87E8A0),
        Color(0xFF34C759), Color(0xFF28B24C), Color(0xFF30D158)
    )
}

class AccentThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ncm_theme", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(
        AccentTheme.entries.firstOrNull { it.name == prefs.getString("accent", AccentTheme.BLUE.name) }
            ?: AccentTheme.BLUE
    )
    val theme: StateFlow<AccentTheme> = _theme

    private val _mode = MutableStateFlow(
        AppThemeMode.entries.firstOrNull { it.name == prefs.getString("mode", AppThemeMode.SYSTEM.name) }
            ?: AppThemeMode.SYSTEM
    )
    val mode: StateFlow<AppThemeMode> = _mode

    fun setTheme(theme: AccentTheme) {
        prefs.edit().putString("accent", theme.name).apply()
        _theme.value = theme
    }

    fun setMode(mode: AppThemeMode) {
        prefs.edit().putString("mode", mode.name).apply()
        _mode.value = mode
    }
}

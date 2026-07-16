package com.ncm.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AccentTheme(val label: String, val color: Color) {
    GREEN("薄荷绿", Color(0xFF58B86A)), BLUE("海蓝", Color(0xFF4A90D9)),
    PURPLE("紫罗兰", Color(0xFF9B59B6)), ORANGE("暖橙", Color(0xFFF39C12)), RED("珊瑚红", Color(0xFFE74C3C))
}

class AccentThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ncm_theme", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(AccentTheme.entries.firstOrNull { it.name == prefs.getString("accent", AccentTheme.GREEN.name) } ?: AccentTheme.GREEN)
    val theme: StateFlow<AccentTheme> = _theme
    fun setTheme(theme: AccentTheme) { prefs.edit().putString("accent", theme.name).apply(); _theme.value = theme }
}

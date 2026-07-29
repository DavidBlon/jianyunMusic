package com.ncm.app.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class AccentTheme(
    val label: String,
    val color: Color,
    val secondary: Color,
    val highlight: Color
) {
    GREEN("翡翠青", Color(0xFF72D69B), Color(0xFF4DB8A5), Color(0xFFD6B86B)),
    BLUE("深海蓝", Color(0xFF78A9F8), Color(0xFF667FE5), Color(0xFF63CDDA)),
    PURPLE("暮光紫", Color(0xFFB29AEF), Color(0xFF7E8CE8), Color(0xFFD994C4)),
    ORANGE("琥珀橙", Color(0xFFE7A566), Color(0xFFD4765C), Color(0xFFE3C474)),
    RED("酒红玫瑰", Color(0xFFE27B8B), Color(0xFFB65E7B), Color(0xFFE39B70))
}

class AccentThemeSettings(context: Context) {
    private val prefs = context.getSharedPreferences("ncm_theme", Context.MODE_PRIVATE)
    private val _theme = MutableStateFlow(AccentTheme.entries.firstOrNull { it.name == prefs.getString("accent", AccentTheme.GREEN.name) } ?: AccentTheme.GREEN)
    val theme: StateFlow<AccentTheme> = _theme
    fun setTheme(theme: AccentTheme) { prefs.edit().putString("accent", theme.name).apply(); _theme.value = theme }
}

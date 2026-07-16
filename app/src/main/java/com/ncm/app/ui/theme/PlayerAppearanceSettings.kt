package com.ncm.app.ui.theme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class PlayerLayout(val label: String) {
    DISC("唱片"),
    COVER("大封面")
}

enum class PlayerBackground(val label: String) {
    NONE("关闭动效"),
    SNOW("飘雪"),
    STARDUST("星尘"),
    RAIN("雨幕")
}

class PlayerAppearanceSettings(context: Context) {
    private val prefs = context.getSharedPreferences("player_appearance", Context.MODE_PRIVATE)

    private val _layout = MutableStateFlow(enumValueOrDefault(
        prefs.getString(KEY_LAYOUT, PlayerLayout.DISC.name), PlayerLayout.DISC
    ))
    val layout: StateFlow<PlayerLayout> = _layout

    private val _background = MutableStateFlow(enumValueOrDefault(
        prefs.getString(KEY_BACKGROUND, PlayerBackground.NONE.name), PlayerBackground.NONE
    ))
    val background: StateFlow<PlayerBackground> = _background

    fun setLayout(layout: PlayerLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
        _layout.value = layout
    }

    fun setBackground(background: PlayerBackground) {
        prefs.edit().putString(KEY_BACKGROUND, background.name).apply()
        _background.value = background
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val KEY_LAYOUT = "layout"
        const val KEY_BACKGROUND = "background"
    }
}

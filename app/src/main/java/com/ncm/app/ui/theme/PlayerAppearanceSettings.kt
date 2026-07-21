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

enum class PlayerCustomMediaType {
    IMAGE,
    VIDEO
}

data class PlayerCustomBackground(
    val uri: String,
    val type: PlayerCustomMediaType
)

enum class PlayerComponent(val label: String, val description: String) {
    SONG_INFO("歌曲信息", "歌名、歌手与音源标识"),
    ARTWORK("封面与歌词", "唱片、封面以及歌词阅读区"),
    PROGRESS("播放进度", "进度条与时间"),
    TRANSPORT("播放控制", "上一首、播放暂停与下一首"),
    EXTRAS("更多功能", "播放模式、音质、列表与定时"),
    FAVORITE("收藏按钮", "播放页右上角的收藏入口")
}

data class PlayerComponentVisibility(
    val songInfo: Boolean = true,
    val artwork: Boolean = true,
    val progress: Boolean = true,
    val transport: Boolean = true,
    val extras: Boolean = true,
    val favorite: Boolean = true
) {
    fun isVisible(component: PlayerComponent): Boolean = when (component) {
        PlayerComponent.SONG_INFO -> songInfo
        PlayerComponent.ARTWORK -> artwork
        PlayerComponent.PROGRESS -> progress
        PlayerComponent.TRANSPORT -> transport
        PlayerComponent.EXTRAS -> extras
        PlayerComponent.FAVORITE -> favorite
    }

    fun withVisibility(component: PlayerComponent, visible: Boolean): PlayerComponentVisibility = when (component) {
        PlayerComponent.SONG_INFO -> copy(songInfo = visible)
        PlayerComponent.ARTWORK -> copy(artwork = visible)
        PlayerComponent.PROGRESS -> copy(progress = visible)
        PlayerComponent.TRANSPORT -> copy(transport = visible)
        PlayerComponent.EXTRAS -> copy(extras = visible)
        PlayerComponent.FAVORITE -> copy(favorite = visible)
    }
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

    private val _customBackground = MutableStateFlow(loadCustomBackground())
    val customBackground: StateFlow<PlayerCustomBackground?> = _customBackground

    private val _componentVisibility = MutableStateFlow(loadComponentVisibility())
    val componentVisibility: StateFlow<PlayerComponentVisibility> = _componentVisibility

    private val _showLyrics = MutableStateFlow(prefs.getBoolean(KEY_SHOW_LYRICS, false))
    val showLyrics: StateFlow<Boolean> = _showLyrics

    fun setLayout(layout: PlayerLayout) {
        prefs.edit().putString(KEY_LAYOUT, layout.name).apply()
        _layout.value = layout
    }

    fun setBackground(background: PlayerBackground) {
        prefs.edit().putString(KEY_BACKGROUND, background.name).apply()
        _background.value = background
    }

    fun setCustomBackground(uri: String, mimeType: String?) {
        val type = if (mimeType?.startsWith("video/") == true) {
            PlayerCustomMediaType.VIDEO
        } else {
            PlayerCustomMediaType.IMAGE
        }
        val value = PlayerCustomBackground(uri, type)
        prefs.edit()
            .putString(KEY_CUSTOM_MEDIA_URI, uri)
            .putString(KEY_CUSTOM_MEDIA_TYPE, type.name)
            .remove(KEY_VIDEO_POSITION_URI)
            .remove(KEY_VIDEO_POSITION_MS)
            .apply()
        _customBackground.value = value
    }

    fun clearCustomBackground() {
        prefs.edit()
            .remove(KEY_CUSTOM_MEDIA_URI)
            .remove(KEY_CUSTOM_MEDIA_TYPE)
            .remove(KEY_VIDEO_POSITION_URI)
            .remove(KEY_VIDEO_POSITION_MS)
            .apply()
        _customBackground.value = null
    }

    fun videoResumePosition(uri: String): Long {
        if (prefs.getString(KEY_VIDEO_POSITION_URI, null) != uri) return 0L
        return prefs.getLong(KEY_VIDEO_POSITION_MS, 0L).coerceAtLeast(0L)
    }

    fun saveVideoResumePosition(uri: String, positionMs: Long) {
        val current = _customBackground.value
        if (current?.type != PlayerCustomMediaType.VIDEO || current.uri != uri) return
        prefs.edit()
            .putString(KEY_VIDEO_POSITION_URI, uri)
            .putLong(KEY_VIDEO_POSITION_MS, positionMs.coerceAtLeast(0L))
            .apply()
    }

    fun setComponentVisible(component: PlayerComponent, visible: Boolean) {
        val value = _componentVisibility.value.withVisibility(component, visible)
        prefs.edit().putBoolean(component.preferenceKey, visible).apply()
        _componentVisibility.value = value
    }

    fun setShowLyrics(showLyrics: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_LYRICS, showLyrics).apply()
        _showLyrics.value = showLyrics
    }

    fun applyImmersivePreset() {
        updateComponentVisibility(
            PlayerComponentVisibility(
                songInfo = false,
                artwork = false,
                progress = false,
                transport = true,
                extras = false,
                favorite = false
            )
        )
    }

    fun resetComponentVisibility() {
        updateComponentVisibility(PlayerComponentVisibility())
    }

    private fun loadCustomBackground(): PlayerCustomBackground? {
        val uri = prefs.getString(KEY_CUSTOM_MEDIA_URI, null)?.takeIf { it.isNotBlank() } ?: return null
        val type = enumValueOrDefault(
            prefs.getString(KEY_CUSTOM_MEDIA_TYPE, PlayerCustomMediaType.IMAGE.name),
            PlayerCustomMediaType.IMAGE
        )
        return PlayerCustomBackground(uri, type)
    }

    private fun loadComponentVisibility() = PlayerComponentVisibility(
        songInfo = prefs.getBoolean(PlayerComponent.SONG_INFO.preferenceKey, true),
        artwork = prefs.getBoolean(PlayerComponent.ARTWORK.preferenceKey, true),
        progress = prefs.getBoolean(PlayerComponent.PROGRESS.preferenceKey, true),
        transport = prefs.getBoolean(PlayerComponent.TRANSPORT.preferenceKey, true),
        extras = prefs.getBoolean(PlayerComponent.EXTRAS.preferenceKey, true),
        favorite = prefs.getBoolean(PlayerComponent.FAVORITE.preferenceKey, true)
    )

    private fun updateComponentVisibility(value: PlayerComponentVisibility) {
        prefs.edit().apply {
            PlayerComponent.entries.forEach { component ->
                putBoolean(component.preferenceKey, value.isVisible(component))
            }
        }.apply()
        _componentVisibility.value = value
    }

    private val PlayerComponent.preferenceKey: String
        get() = "component_${name.lowercase()}"

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private companion object {
        const val KEY_LAYOUT = "layout"
        const val KEY_BACKGROUND = "background"
        const val KEY_CUSTOM_MEDIA_URI = "custom_media_uri"
        const val KEY_CUSTOM_MEDIA_TYPE = "custom_media_type"
        const val KEY_VIDEO_POSITION_URI = "video_position_uri"
        const val KEY_VIDEO_POSITION_MS = "video_position_ms"
        const val KEY_SHOW_LYRICS = "show_lyrics"
    }
}

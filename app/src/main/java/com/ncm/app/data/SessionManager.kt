package com.ncm.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 本地会话（P6T2 后）：网易云 Cookie/登录态已移除。
 * 仅保留本地用户标识（匿名 0）、播放音质偏好与会话版本号。
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ncm_session", Context.MODE_PRIVATE)

    val userId: Long
        get() = prefs.getLong("user_id", 0)

    var playbackQuality: String
        get() = prefs.getString("playback_quality", "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit().putString("playback_quality", value).apply()

    /** 本地资料版本号，用于让过期生成任务放弃写缓存。 */
    var sessionGeneration: Int
        get() = prefs.getInt("session_generation", 0)
        private set(value) = prefs.edit().putInt("session_generation", value).apply()

    fun invalidate() {
        sessionGeneration++
    }

}

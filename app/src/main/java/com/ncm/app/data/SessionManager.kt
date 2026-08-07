package com.ncm.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ncm.app.data.model.UserProfile

/**
 * 本地会话（P6T2 后）：网易云 Cookie/登录态已移除。
 * 仅保留本地用户标识（匿名 0）、播放音质偏好与会话版本号。
 */
class SessionManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("ncm_session", Context.MODE_PRIVATE)

    var userId: Long
        get() = prefs.getLong("user_id", 0)
        set(value) = prefs.edit().putLong("user_id", value).apply()

    var nickname: String
        get() = prefs.getString("nickname", "") ?: ""
        set(value) = prefs.edit().putString("nickname", value).apply()

    var avatar: String
        get() = prefs.getString("avatar", "") ?: ""
        set(value) = prefs.edit().putString("avatar", value).apply()

    var vipType: Int
        get() = prefs.getInt("vip_type", 0)
        set(value) = prefs.edit().putInt("vip_type", value).apply()

    var playbackQuality: String
        get() = prefs.getString("playback_quality", "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit().putString("playback_quality", value).apply()

    /** 会话版本号：登录/退出时单调递增，用于让过期生成任务放弃写缓存。 */
    var sessionGeneration: Int
        get() = prefs.getInt("session_generation", 0)
        private set(value) = prefs.edit().putInt("session_generation", value).apply()

    fun invalidate() {
        sessionGeneration++
    }

    /** 本地模式：无在线账号登录（网易云登录已移除）。 */
    val isLoggedIn: Boolean
        get() = false

    val profile: UserProfile?
        get() = UserProfile(userId, nickname.ifBlank { "本地用户" }, avatar.ifEmpty { null }, vipType)

    fun saveLoginInfo(userId: Long, nickname: String, avatar: String?, vipType: Int) {
        this.userId = userId
        this.nickname = nickname
        this.avatar = avatar ?: ""
        this.vipType = vipType
        sessionGeneration++
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

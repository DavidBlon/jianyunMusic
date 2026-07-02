package com.ncm.app.data

import android.content.Context
import android.content.SharedPreferences
import com.ncm.app.data.model.UserProfile

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

    var cookie: String
        get() = prefs.getString("cookie", "") ?: ""
        set(value) = prefs.edit().putString("cookie", value).apply()

    var playbackQuality: String
        get() = prefs.getString("playback_quality", "STANDARD") ?: "STANDARD"
        set(value) = prefs.edit().putString("playback_quality", value).apply()

    var qrDeviceId: String
        get() = prefs.getString("qr_device_id", "") ?: ""
        set(value) = prefs.edit().putString("qr_device_id", value).apply()

    val isLoggedIn: Boolean
        get() = cookie.contains("MUSIC_U")

    val profile: UserProfile?
        get() = if (isLoggedIn) UserProfile(userId, nickname, avatar.ifEmpty { null }, vipType) else null

    fun saveLoginInfo(userId: Long, nickname: String, avatar: String?, vipType: Int) {
        this.userId = userId
        this.nickname = nickname
        this.avatar = avatar ?: ""
        this.vipType = vipType
    }

    fun saveCookie(cookie: String) {
        this.cookie = cookie
    }

    fun clear() {
        prefs.edit().clear().apply()
    }
}

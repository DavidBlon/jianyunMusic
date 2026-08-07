package com.ncm.app.data.store

import android.content.Context
import com.ncm.app.plugin.auth.LinglanAuthState

data class OnlineSourcePrefs(
    val authState: String = LinglanAuthState.DISCONNECTED.name,
    val selectedPluginId: String? = null,
    val lastManifestVersion: Int = 0,
    val lastVerifiedAtEpochMs: Long? = null
)

/** 在线音乐来源持久化设置。密钥绝不入库/入偏好（GC #4/#8），只存状态标识与插件 ID。 */
class MusicSourceSettings(
    context: Context,
    private val prefsName: String = DEFAULT_PREFS_NAME
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    val currentPluginId: String?
        get() = prefs.getString(KEY_SELECTED_PLUGIN, null)

    fun read(): OnlineSourcePrefs = OnlineSourcePrefs(
        authState = prefs.getString(KEY_AUTH_STATE, LinglanAuthState.DISCONNECTED.name)
            ?: LinglanAuthState.DISCONNECTED.name,
        selectedPluginId = prefs.getString(KEY_SELECTED_PLUGIN, null),
        lastManifestVersion = prefs.getInt(KEY_MANIFEST_VERSION, 0),
        lastVerifiedAtEpochMs =
            if (prefs.contains(KEY_VERIFIED_AT)) prefs.getLong(KEY_VERIFIED_AT, 0L) else null
    )

    fun write(prefs: OnlineSourcePrefs) {
        val editor = this.prefs.edit()
            .putString(KEY_AUTH_STATE, prefs.authState)
            .putInt(KEY_MANIFEST_VERSION, prefs.lastManifestVersion)
        if (prefs.selectedPluginId != null) {
            editor.putString(KEY_SELECTED_PLUGIN, prefs.selectedPluginId)
        } else {
            editor.remove(KEY_SELECTED_PLUGIN)
        }
        if (prefs.lastVerifiedAtEpochMs != null) {
            editor.putLong(KEY_VERIFIED_AT, prefs.lastVerifiedAtEpochMs)
        } else {
            editor.remove(KEY_VERIFIED_AT)
        }
        editor.apply()
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        const val DEFAULT_PREFS_NAME = "music_source"
        private const val KEY_AUTH_STATE = "auth_state"
        private const val KEY_SELECTED_PLUGIN = "selected_plugin_id"
        private const val KEY_MANIFEST_VERSION = "last_manifest_version"
        private const val KEY_VERIFIED_AT = "last_verified_at_epoch_ms"
    }
}

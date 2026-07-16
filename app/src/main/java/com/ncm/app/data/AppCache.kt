package com.ncm.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class AppCache(context: Context) {
    @PublishedApi
    internal val prefs = context.getSharedPreferences("ncm_app_cache", Context.MODE_PRIVATE)

    @PublishedApi
    internal val gson = Gson()

    inline fun <reified T> get(key: String): T? {
        val json = prefs.getString(key, null) ?: return null
        return runCatching {
            gson.fromJson<T>(json, object : TypeToken<T>() {}.type)
        }.getOrNull()
    }

    fun put(key: String, value: Any) {
        prefs.edit().putString(key, gson.toJson(value)).apply()
    }

    fun removePrefix(prefix: String) {
        val keys = prefs.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        prefs.edit().apply {
            keys.forEach { remove(it) }
        }.apply()
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clearUserData() {
        val playlistKeys = prefs.all.keys.filter { it.startsWith(KEY_PLAYLIST_PREFIX) }
        prefs.edit().apply {
            remove(KEY_MY)
            playlistKeys.forEach { remove(it) }
        }.apply()
    }

    companion object {
        const val KEY_DISCOVER = "discover"
        const val KEY_MY = "my"
        const val KEY_QUICK_PREFIX = "quick:"
        const val KEY_PLAYLIST_PREFIX = "playlist:"
        const val KEY_SEARCH_HISTORY_PREFIX = "search_history:"
    }
}

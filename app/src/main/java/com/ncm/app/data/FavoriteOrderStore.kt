package com.ncm.app.data

import com.ncm.app.data.model.Song
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey

/** Maintains one newest-first order across local and provider-owned favorites. */
class FavoriteOrderStore(
    private val cache: AppCache,
    private val userId: () -> Long
) {

    @Synchronized
    fun updateLocal(songId: Long, liked: Boolean) = update(localKey(songId), liked)

    @Synchronized
    fun updateOnline(key: ProviderTrackKey, liked: Boolean) = update(onlineKey(key), liked)

    /**
     * Returns all current favorites in stack order. Existing installs are migrated lazily:
     * their current display order is retained, while every subsequent like is pushed on top.
     */
    @Synchronized
    fun orderedKeys(localSongs: List<Song>, onlineTracks: List<OnlineTrack>): List<String> {
        val currentKeys = buildList {
            localSongs.forEach { add(localKey(it.id)) }
            onlineTracks.forEach { add(onlineKey(it.key)) }
        }.distinct()
        val currentSet = currentKeys.toSet()
        val stored = load()
        val reconciled = stored.filter { it in currentSet } + currentKeys.filterNot { it in stored }
        if (reconciled != stored) save(reconciled)
        return reconciled
    }

    private fun update(key: String, liked: Boolean) {
        val current = load().filterNot { it == key }
        save(if (liked) listOf(key) + current else current)
    }

    private fun load(): List<String> = cache.get<List<String>>(storageKey()).orEmpty().distinct()

    private fun save(keys: List<String>) = cache.put(storageKey(), keys)

    private fun storageKey(): String = "$KEY_PREFIX${userId()}"

    companion object {
        private const val KEY_PREFIX = "favorite_order:"

        fun localKey(songId: Long): String = "local:$songId"

        fun onlineKey(key: ProviderTrackKey): String = "online:${key.asComposite()}"
    }
}

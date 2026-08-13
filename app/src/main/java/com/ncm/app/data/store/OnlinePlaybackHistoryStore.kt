package com.ncm.app.data.store

import com.google.gson.Gson
import com.ncm.app.data.AppCache
import com.ncm.app.plugin.model.OnlineTrack

/** Per-user playback history for source-owned tracks. Resolved audio URLs are never stored. */
class OnlinePlaybackHistoryStore(
    private val cache: AppCache,
    private val currentUserId: () -> Long,
    private val gson: Gson = Gson(),
    private val limit: Int = 100
) {
    fun load(): List<OnlineTrack> = cache
        .get<List<OnlineSongEntity>>(cacheKey())
        .orEmpty()
        .mapNotNull { it.toOnlineTrack(gson) }

    fun remember(track: OnlineTrack) {
        val entity = track.toStoredEntity(gson)
        val updated = listOf(entity) + cache
            .get<List<OnlineSongEntity>>(cacheKey())
            .orEmpty()
            .filterNot { it.pluginId == entity.pluginId && it.remoteId == entity.remoteId }
        cache.put(cacheKey(), updated.take(limit))
    }

    private fun cacheKey(): String = AppCache.KEY_ONLINE_PLAY_HISTORY_PREFIX + currentUserId()
}

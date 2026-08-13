package com.ncm.app.data.store

import com.google.gson.Gson
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey

/** Persistent favorites for source-owned tracks, keyed by provider plus remote id. */
class OnlineFavoriteStore(
    private val dao: OnlineSongDao,
    private val gson: Gson = Gson()
) {
    suspend fun isLiked(key: ProviderTrackKey): Boolean =
        dao.findByCompositeKey(key.pluginId, key.remoteId) != null

    suspend fun allFavorites(): List<OnlineTrack> =
        dao.all().mapNotNull { it.toOnlineTrack(gson) }

    suspend fun setLiked(track: OnlineTrack, liked: Boolean) {
        if (liked) {
            dao.upsert(track.toStoredEntity(gson))
        } else {
            dao.deleteByKey(track.key.pluginId, track.key.remoteId)
        }
    }
}

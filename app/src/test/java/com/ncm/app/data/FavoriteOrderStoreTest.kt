package com.ncm.app.data

import com.ncm.app.data.model.Song
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class FavoriteOrderStoreTest {

    private fun store(userId: Long) = FavoriteOrderStore(
        cache = AppCache(RuntimeEnvironment.getApplication()),
        userId = { userId }
    )

    @Test
    fun newestFavoriteIsAlwaysFirstAcrossSources() {
        val store = store(31L)
        val local = Song(id = 1L, name = "Local")
        val online = onlineTrack("linglan.kg", "2")

        store.updateLocal(local.id, liked = true)
        store.updateOnline(online.key, liked = true)

        assertEquals(
            listOf(FavoriteOrderStore.onlineKey(online.key), FavoriteOrderStore.localKey(local.id)),
            store.orderedKeys(listOf(local), listOf(online))
        )
    }

    @Test
    fun unlikedSongIsRemovedAndExistingDataIsMigrated() {
        val store = store(32L)
        val oldLocal = Song(id = 10L, name = "Old local")
        val oldOnline = onlineTrack("linglan.tx", "20")

        assertEquals(
            listOf(FavoriteOrderStore.localKey(oldLocal.id), FavoriteOrderStore.onlineKey(oldOnline.key)),
            store.orderedKeys(listOf(oldLocal), listOf(oldOnline))
        )
        store.updateLocal(oldLocal.id, liked = false)

        assertEquals(
            listOf(FavoriteOrderStore.onlineKey(oldOnline.key)),
            store.orderedKeys(emptyList(), listOf(oldOnline))
        )
    }

    private fun onlineTrack(pluginId: String, remoteId: String) = OnlineTrack(
        key = ProviderTrackKey(pluginId, remoteId),
        producedByPluginVersion = "1",
        payloadSchemaVersion = 1,
        title = "Online",
        artists = emptyList(),
        album = null,
        durationMs = null,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )
}

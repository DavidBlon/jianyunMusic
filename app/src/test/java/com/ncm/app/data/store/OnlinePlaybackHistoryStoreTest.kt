package com.ncm.app.data.store

import com.ncm.app.data.AppCache
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnlinePlaybackHistoryStoreTest {

    @Test
    fun playedOnlineTracksArePersistedNewestFirstAndDeduplicatedByProviderKey() {
        val context = RuntimeEnvironment.getApplication()
        val cache = AppCache(context)
        cache.remove(AppCache.KEY_ONLINE_PLAY_HISTORY_PREFIX + "42")
        val store = OnlinePlaybackHistoryStore(cache, currentUserId = { 42L })
        val qq = track("linglan.tx", "same-id", "QQ first")
        val kuwo = track("linglan.kw", "same-id", "Kuwo")

        store.remember(qq)
        store.remember(kuwo)
        store.remember(qq.copy(title = "QQ latest"))

        val restored = OnlinePlaybackHistoryStore(cache, currentUserId = { 42L }).load()
        assertEquals(listOf("QQ latest", "Kuwo"), restored.map { it.title })
        assertEquals(listOf("linglan.tx", "linglan.kw"), restored.map { it.key.pluginId })
    }

    private fun track(pluginId: String, remoteId: String, title: String) = OnlineTrack(
        key = ProviderTrackKey(pluginId, remoteId),
        producedByPluginVersion = "7",
        payloadSchemaVersion = 1,
        title = title,
        artists = listOf(OnlineArtist("artist", "Artist")),
        album = null,
        durationMs = 120_000,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(mapOf("id" to remoteId))
    )
}

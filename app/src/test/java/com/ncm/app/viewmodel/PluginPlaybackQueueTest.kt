package com.ncm.app.viewmodel

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginPlaybackQueueTest {

    @Test
    fun nextAndPreviousWrapAcrossMixedSources() {
        val kugou = track("linglan.kg", "kg-1")
        val qq = track("linglan.tx", "tx-1")
        val kuwo = track("linglan.kw", "kw-1")
        val queue = PluginPlaybackQueue()

        queue.set(listOf(kugou, qq, kuwo), startIndex = 1)

        assertEquals(kuwo.key, queue.next()?.key)
        assertEquals(kugou.key, queue.next()?.key)
        assertEquals(kuwo.key, queue.previous()?.key)
    }

    @Test
    fun resolvedFallbackReplacesOnlySelectedQueueItem() {
        val kugou = track("linglan.kg", "kg-1")
        val requestedQq = track("linglan.tx", "tx-1")
        val resolvedKuwo = track("linglan.kw", "kw-replacement")
        val queue = PluginPlaybackQueue()
        queue.set(listOf(kugou, requestedQq), startIndex = 1)

        queue.replaceSelected(requestedQq, resolvedKuwo)

        assertEquals(listOf(kugou.key, resolvedKuwo.key), queue.tracks.map { it.key })
        assertEquals(resolvedKuwo.key, queue.current()?.key)
    }

    @Test
    fun queueSelectionUsesFullProviderTrackIdentity() {
        val kugou = track("linglan.kg", "same-id")
        val qq = track("linglan.tx", "same-id")
        val queue = PluginPlaybackQueue()
        queue.set(listOf(kugou, qq))

        assertEquals(true, queue.select(qq))
        assertEquals(qq.key, queue.trackForSongId(pluginShellSongId(qq.key))?.key)
        assertEquals(qq.key, queue.current()?.key)
    }

    private fun track(pluginId: String, remoteId: String) = OnlineTrack(
        key = ProviderTrackKey(pluginId, remoteId),
        producedByPluginVersion = "1",
        payloadSchemaVersion = 1,
        title = remoteId,
        artists = listOf(OnlineArtist("artist", "artist")),
        album = null,
        durationMs = 180_000,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )
}

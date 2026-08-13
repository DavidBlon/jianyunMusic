package com.ncm.app.viewmodel

import com.ncm.app.data.FavoriteOrderStore
import com.ncm.app.data.model.Song
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistPlaybackQueueTest {

    @Test
    fun nextFollowsTheDisplayedMixedSourceOrder() {
        val first = Song(id = 1L, name = "first")
        val online = track("linglan.tx", "online")
        val last = Song(id = 3L, name = "last")
        val queue = PlaylistPlaybackQueue()

        queue.set(
            songs = listOf(first, last),
            onlineTracks = listOf(online),
            order = listOf(
                FavoriteOrderStore.localKey(first.id),
                FavoriteOrderStore.onlineKey(online.key),
                FavoriteOrderStore.localKey(last.id)
            ),
            selectedKey = FavoriteOrderStore.localKey(first.id)
        )

        assertEquals(online.key, (queue.next() as PlaylistPlaybackEntry.Online).track.key)
        assertEquals(last.id, (queue.next() as PlaylistPlaybackEntry.Local).song.id)
        assertEquals(first.id, (queue.next() as PlaylistPlaybackEntry.Local).song.id)
    }

    @Test
    fun nextUsesVisualOrderInsteadOfRawSourceOrder() {
        val above = track("linglan.kg", "above")
        val current = track("linglan.tx", "current")
        val below = track("linglan.kw", "below")
        val queue = PlaylistPlaybackQueue()

        queue.set(
            songs = emptyList(),
            onlineTracks = listOf(below, current, above),
            order = listOf(
                FavoriteOrderStore.onlineKey(above.key),
                FavoriteOrderStore.onlineKey(current.key),
                FavoriteOrderStore.onlineKey(below.key)
            ),
            selectedKey = FavoriteOrderStore.onlineKey(current.key)
        )

        assertEquals(below.key, (queue.next() as PlaylistPlaybackEntry.Online).track.key)
    }

    @Test
    fun fallbackReplacementKeepsTheSelectedPosition() {
        val first = track("linglan.kg", "first")
        val requested = track("linglan.tx", "requested")
        val fallback = track("linglan.kw", "fallback")
        val last = track("linglan.kg", "last")
        val queue = PlaylistPlaybackQueue()
        queue.set(
            songs = emptyList(),
            onlineTracks = listOf(first, requested, last),
            order = emptyList(),
            selectedKey = FavoriteOrderStore.onlineKey(requested.key)
        )

        queue.replaceSelected(requested, fallback)

        assertEquals(fallback.key, (queue.current() as PlaylistPlaybackEntry.Online).track.key)
        assertEquals(last.key, (queue.next() as PlaylistPlaybackEntry.Online).track.key)
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

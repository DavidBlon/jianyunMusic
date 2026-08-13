package com.ncm.app.data

import com.ncm.app.data.model.AlbumBrief
import com.ncm.app.data.model.Song
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class UserPlaylistStoreTest {

    private fun store(userId: Long = 7L) = UserPlaylistStore(
        cache = AppCache(RuntimeEnvironment.getApplication()),
        userId = { userId },
        now = { 1234L }
    )

    @Test
    fun createAddAndDeletePlaylist() {
        val store = store()
        val playlistId = requireNotNull(store.create(" 通勤 "))
        val song = Song(id = 42L, name = "Song", album = AlbumBrief(2L, "Album", "cover"))

        assertEquals(PlaylistMutationResult.ADDED, store.addSong(playlistId, song))
        assertEquals(PlaylistMutationResult.ALREADY_EXISTS, store.addSong(playlistId, song))
        assertEquals("通勤", store.summaries().single().name)
        assertEquals(1, store.content(playlistId)?.playlist?.trackCount)
        assertEquals("cover", store.content(playlistId)?.playlist?.cover)

        assertEquals(true, store.delete(playlistId))
        assertNull(store.content(playlistId))
    }

    @Test
    fun onlineTracksRoundTripAndStayAccountScoped() {
        val store = store(userId = 11L)
        val playlistId = requireNotNull(store.create("在线"))
        val track = OnlineTrack(
            key = ProviderTrackKey("linglan.kg", "hash-1"),
            producedByPluginVersion = "7",
            payloadSchemaVersion = 1,
            title = "Online song",
            artists = listOf(OnlineArtist("artist-1", "Artist")),
            album = null,
            durationMs = 180_000,
            artworkUrl = "https://example.com/cover.jpg",
            pluginPayload = BoundedJsonObject.fromMap(mapOf("hash" to "hash-1"))
        )

        assertEquals(PlaylistMutationResult.ADDED, store.addOnlineTrack(playlistId, track))
        assertEquals(track.key, store.content(playlistId)?.onlineTracks?.single()?.key)
        assertEquals("hash-1", store.content(playlistId)?.onlineTracks?.single()?.pluginPayload?.toMap()?.get("hash"))
        assertEquals(0, store(userId = 12L).summaries().size)
    }

    @Test
    fun removeOnlyRequestedTrack() {
        val store = store(userId = 13L)
        val playlistId = requireNotNull(store.create("收藏夹"))
        store.addSong(playlistId, Song(id = 1L, name = "One"))
        store.addSong(playlistId, Song(id = 2L, name = "Two"))

        assertEquals(true, store.removeSong(playlistId, 1L))
        assertEquals(listOf(2L), store.content(playlistId)?.songs?.map(Song::id))
    }
}

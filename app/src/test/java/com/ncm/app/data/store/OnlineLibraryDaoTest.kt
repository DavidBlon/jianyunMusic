package com.ncm.app.data.store

import androidx.room.Room
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OnlineLibraryDaoTest {

    private fun db(): OnlineLibraryDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), OnlineLibraryDatabase::class.java).build()

    @Test
    fun upsertAndQueryByCompositeKey() = runBlocking {
        val dao = db().onlineSongDao()
        val a = OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1)
        dao.upsert(a)
        val loaded = dao.findByCompositeKey("linglan.kw", "123")
        assertEquals("A", loaded?.title)
    }

    @Test
    fun sameRemoteIdDifferentPluginCoexist() = runBlocking {
        val dao = db().onlineSongDao()
        dao.upsert(OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1))
        dao.upsert(OnlineSongEntity("linglan.tx", "123", "B", "[]", null, 1000, null, "{}", "1.0.0", 1))
        assertEquals(2, dao.countAll())
    }

    @Test
    fun deleteByCompositeKeyRemovesOnlyThatKey() = runBlocking {
        val dao = db().onlineSongDao()
        dao.upsert(OnlineSongEntity("linglan.kw", "123", "A", "[]", null, 1000, null, "{}", "1.0.0", 1))
        dao.upsert(OnlineSongEntity("linglan.kw", "456", "B", "[]", null, 1000, null, "{}", "1.0.0", 1))
        dao.deleteByKey("linglan.kw", "123")
        assertNull(dao.findByCompositeKey("linglan.kw", "123"))
        assertEquals("B", dao.findByCompositeKey("linglan.kw", "456")?.title)
    }

    @Test
    fun onlineFavoriteStorePersistsAndRemovesByProviderTrackKey() = runBlocking {
        val dao = db().onlineSongDao()
        val store = OnlineFavoriteStore(dao)
        val track = OnlineTrack(
            key = ProviderTrackKey("linglan.kg", "hash-1"),
            producedByPluginVersion = "7",
            payloadSchemaVersion = 1,
            title = "测试歌曲",
            artists = listOf(OnlineArtist("artist-1", "测试歌手")),
            album = null,
            durationMs = 123_000,
            artworkUrl = "https://img.example/song.jpg",
            pluginPayload = BoundedJsonObject.fromMap(mapOf("hash" to "hash-1"))
        )

        store.setLiked(track, true)
        assertEquals(true, store.isLiked(track.key))
        assertEquals("测试歌曲", dao.findByCompositeKey("linglan.kg", "hash-1")?.title)

        store.setLiked(track, false)
        assertEquals(false, store.isLiked(track.key))
    }

    @Test
    fun onlineFavoriteStoreListsRestorableTracks() = runBlocking {
        val dao = db().onlineSongDao()
        val store = OnlineFavoriteStore(dao)
        val track = OnlineTrack(
            key = ProviderTrackKey("linglan.tx", "favorite-1"),
            producedByPluginVersion = "7",
            payloadSchemaVersion = 1,
            title = "Favorite song",
            artists = listOf(OnlineArtist("artist-1", "Favorite artist")),
            album = null,
            durationMs = 180_000,
            artworkUrl = "https://img.example/favorite.jpg",
            pluginPayload = BoundedJsonObject.fromMap(mapOf("songmid" to "favorite-1"))
        )

        store.setLiked(track, true)

        val restored = store.allFavorites().single()
        assertEquals(track.key, restored.key)
        assertEquals("Favorite song", restored.title)
        assertEquals("Favorite artist", restored.artists.single().name)
        assertEquals("favorite-1", restored.pluginPayload.toMap()["songmid"])
    }
}

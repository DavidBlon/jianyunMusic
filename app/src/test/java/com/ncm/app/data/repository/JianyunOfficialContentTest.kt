package com.ncm.app.data.repository

import com.ncm.app.data.model.SearchResponse
import com.ncm.app.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JianyunOfficialContentTest {

    @Test
    fun `searching Jianyun returns the official song with exact metadata`() {
        val result = JianyunOfficialContent.searchSongs("简云").single()

        assertEquals(JianyunOfficialContent.SONG_ID, result.id)
        assertEquals("简云漫游", result.name)
        assertEquals("简云官方", result.artists?.single()?.name)
        assertEquals(JianyunOfficialContent.ARTIST_ID, result.artists?.single()?.id)
        assertEquals(JianyunOfficialContent.albumCoverUrl, result.album?.picUrl)
    }

    @Test
    fun `official media uses the requested server paths`() {
        assertTrue(JianyunOfficialContent.songUrl.endsWith("/%E7%AE%80%E4%BA%91%E6%BC%AB%E6%B8%B8.mp3"))
        assertTrue(JianyunOfficialContent.albumCoverUrl.endsWith("/assets/app-icon.png"))
        assertTrue(JianyunOfficialContent.artistPhotoUrl.endsWith("/assets/%E7%AE%80%E5%A8%98.png"))
        assertEquals(JianyunOfficialContent.artistPhotoUrl, JianyunOfficialContent.artist().avatarUrl)
    }

    @Test
    fun `local result is first and does not duplicate an existing result`() {
        val duplicate = JianyunOfficialContent.defaultSong()
        val remoteSong = Song(id = 42L, name = "Remote")
        val merged = JianyunOfficialContent.mergeSearchResponse(
            keywords = "简云",
            remote = SearchResponse(songs = listOf(remoteSong, duplicate), songCount = 2)
        )

        assertEquals(listOf(JianyunOfficialContent.SONG_ID, 42L), merged.songs.map(Song::id))
        assertEquals(2, merged.songCount)
    }

    @Test
    fun `catalog maps every root mp3 to the official artist and shared cover`() {
        val songs = JianyunOfficialContent.parseCatalog(
            """{"songs":[{"file":"简云漫游.mp3","name":"简云漫游","durationMs":109000},{"file":"云端相遇.mp3","name":"云端相遇","durationMs":0}]}"""
        )

        assertEquals(listOf("简云漫游", "云端相遇"), songs.map(Song::name))
        assertEquals(2, songs.map(Song::id).distinct().size)
        assertTrue(songs.all { it.artists?.single()?.name == "简云官方" })
        assertTrue(songs.all { it.album?.picUrl == JianyunOfficialContent.albumCoverUrl })
        assertTrue(songs.all { JianyunOfficialContent.isOfficialSongId(it.id) })
        assertTrue(
            JianyunOfficialContent.songUrlFor(songs.last())
                .endsWith("/%E4%BA%91%E7%AB%AF%E7%9B%B8%E9%81%87.mp3")
        )
    }
}

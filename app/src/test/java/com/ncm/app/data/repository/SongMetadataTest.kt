package com.ncm.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongMetadataTest {

    @Test
    fun `fills missing artwork from song detail without replacing compact search metadata`() {
        val compact = Song(
            id = 42L,
            name = "Search title",
            album = AlbumBrief(id = 7L, name = "Search album", picUrl = null)
        )
        val detail = Song(
            id = 42L,
            name = "Detail title",
            album = AlbumBrief(
                id = 7L,
                name = "Detail album",
                picUrl = "https://p1.music.126.net/cover.jpg"
            )
        )

        val enriched = compact.withArtworkFrom(detail)

        assertEquals("Search title", enriched.name)
        assertEquals("Search album", enriched.album?.name)
        assertEquals("https://p1.music.126.net/cover.jpg", enriched.album?.picUrl)
    }

    @Test
    fun `keeps existing artwork and ignores detail for another song`() {
        val existingArtwork = Song(
            id = 42L,
            album = AlbumBrief(id = 7L, name = "Album", picUrl = "https://example.com/original.jpg")
        )
        val otherSongDetail = Song(
            id = 99L,
            album = AlbumBrief(id = 8L, name = "Other", picUrl = "https://example.com/other.jpg")
        )
        val missingArtwork = Song(
            id = 42L,
            album = AlbumBrief(id = 7L, name = "Album", picUrl = null)
        )

        assertEquals(
            "https://example.com/original.jpg",
            existingArtwork.withArtworkFrom(otherSongDetail).album?.picUrl
        )
        assertNull(missingArtwork.withArtworkFrom(otherSongDetail).album?.picUrl)
    }
}

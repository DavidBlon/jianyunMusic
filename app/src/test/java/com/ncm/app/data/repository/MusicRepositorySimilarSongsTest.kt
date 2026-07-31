package com.ncm.app.data.repository

import com.google.gson.JsonParser
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.domain.weekly.SimilarSong
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicRepositorySimilarSongsTest {

    private fun json(text: String) = JsonParser.parseString(text).asJsonObject

    @Test
    fun parseSimilarSongs_readsSongsWithArtists() {
        val root = json(
            """{"songs":[{"id":186016,"name":"晴天","artists":[{"id":6452,"name":"周杰伦"}]},{"id":2,"name":"B","artists":[{"id":20,"name":"Y"}]}]}"""
        )
        val result = parseSimilarSongs(root)
        assertEquals(
            listOf(
                SimilarSong(186016, "晴天", listOf(ArtistBrief(6452, "周杰伦"))),
                SimilarSong(2, "B", listOf(ArtistBrief(20, "Y")))
            ),
            result
        )
    }

    @Test
    fun parseSimilarSongs_skipsInvalidEntriesAndHandlesMissingArtists() {
        val root = json(
            """{"songs":[{"id":0,"name":"bad"},{"name":"noname"},{"id":3,"name":"C","artists":[]},{"id":4,"name":"D","artists":null}]}"""
        )
        val result = parseSimilarSongs(root)
        assertEquals(listOf(SimilarSong(3, "C", emptyList()), SimilarSong(4, "D", emptyList())), result)
    }

    @Test
    fun parseSimilarSongs_returnsEmptyForNonArraySongs() {
        assertEquals(emptyList<SimilarSong>(), parseSimilarSongs(json("""{"songs":{}}""")))
        assertEquals(emptyList<SimilarSong>(), parseSimilarSongs(json("""{"message":"unauthorized"}""")))
    }
}

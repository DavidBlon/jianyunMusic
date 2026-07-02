package com.ncm.app.data.repository

import com.ncm.app.data.model.ArtistBrief
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSongMatcherTest {

    @Test
    fun normalize_removesVersionNoiseAndPunctuation() {
        assertEquals("晴天", BackupSongMatcher.normalize("晴天 (Live版)！！"))
        assertEquals("告白气球", BackupSongMatcher.normalize("告白气球 - 完整版"))
    }

    @Test
    fun selectBest_acceptsBalancedNameArtistAndDurationMatch() {
        val result = BackupSongMatcher.selectBest(
            songName = "晴天",
            artists = listOf(ArtistBrief(1, "周杰伦")),
            duration = 269000,
            candidates = listOf(
                BackupSongMatcher.Candidate(
                    fileHash = "wrong",
                    name = "晴天娃娃",
                    duration = 180000,
                    artists = "其他歌手"
                ),
                BackupSongMatcher.Candidate(
                    fileHash = "right",
                    name = "晴天 Live版",
                    duration = 268500,
                    artists = "周杰伦"
                )
            )
        )

        assertEquals("right", result?.fileHash)
    }

    @Test
    fun selectBest_rejectsSameNameWithWrongArtistAndDuration() {
        val result = BackupSongMatcher.selectBest(
            songName = "晴天",
            artists = listOf(ArtistBrief(1, "周杰伦")),
            duration = 269000,
            candidates = listOf(
                BackupSongMatcher.Candidate(
                    fileHash = "bad",
                    name = "晴天",
                    duration = 90000,
                    artists = "不是周杰伦"
                )
            )
        )

        assertNull(result)
    }
}

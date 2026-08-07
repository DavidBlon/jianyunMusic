package com.ncm.app.plugin.model

import com.ncm.app.data.model.AlbumBrief
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyConversionTest {

    @Test
    fun legacyNeteaseKeyIsStableAndReadable() {
        val key = legacyNeteaseKey(123456L)
        assertEquals("legacy-netease", key.pluginId)
        assertEquals("123456", key.remoteId)
        assertTrue(key.isLegacyNetease())
    }

    @Test
    fun songConvertsToLegacyOnlineTrackPreservingMetadata() {
        val song = Song(
            id = 123456L, name = "测试", dt = 200_000,
            artists = listOf(ArtistBrief(1, "甲")),
            album = AlbumBrief(id = 2, name = "专辑", picUrl = "https://x/a.jpg")
        )
        val track = requireNotNull(song.toLegacyOnlineTrack())
        assertEquals("legacy-netease", track.key.pluginId)
        assertEquals("测试", track.title)
        assertEquals(200_000L, track.durationMs)
        assertEquals(listOf(OnlineArtist("1", "甲")), track.artists)
        assertEquals("网易云 #123456", track.key.toDisplayKey())
    }

    @Test
    fun localOrOfficialSongsDoNotConvert() {
        // 本地文件用 mediaFileName 标识，简云官方 id 有哨兵区段，都不该转成 legacy-netease
        assertNull(Song(id = 90001L, name = "本地", mediaFileName = "local.mp3").toLegacyOnlineTrack())
        assertNull(Song(id = 9_900_000_001L, name = "简云漫游").toLegacyOnlineTrack())
        assertNull(Song(id = 0L, name = "空").toLegacyOnlineTrack())
    }
}

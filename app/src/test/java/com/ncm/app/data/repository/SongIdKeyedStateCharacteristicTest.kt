package com.ncm.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 1 会把 `Song.id` 从网易云 Long 改造成来源感知键；本测试锁定「现状」防止回归（spec §14 阶段 0）。
 * 简云官方 id 必须与网易云 Long id 区间分离，且同一文件名生成稳定 id。
 */
class SongIdKeyedStateCharacteristicTest {

    @Test
    fun officialSongIdRangeIsDisjointFromNeteaseIds() {
        // 简云官方 id 通过 JianyunOfficialContent.song(...) 生成，必须与网易云 Long id 区间分离。
        val official = JianyunOfficialContent.song("demo.mp3")
        assertTrue(JianyunOfficialContent.isOfficialSongId(official.id))
        // 网易云真实 id（如新歌榜回退 id 3779629）不在官方区段
        assertFalse(JianyunOfficialContent.isOfficialSongId(3779629L))
        assertFalse(JianyunOfficialContent.isOfficialSongId(0L))
        assertFalse(JianyunOfficialContent.isOfficialSongId(-1L))
    }

    @Test
    fun officialSongIdIsDeterministicPerFileName() {
        val a = JianyunOfficialContent.song("demo.mp3").id
        val b = JianyunOfficialContent.song("demo.mp3").id
        val other = JianyunOfficialContent.song("another.mp3").id
        assertEquals(a, b)
        assertTrue(a != other)
        assertTrue(JianyunOfficialContent.isOfficialSongId(a))
    }

    @Test
    fun defaultSongUsesFixedSentinelId() {
        assertEquals(JianyunOfficialContent.SONG_ID, JianyunOfficialContent.defaultSong().id)
    }

    @Test
    fun catalogSongIdsAllResideInOfficialRange() {
        val songs = JianyunOfficialContent.parseCatalog(
            """{"songs":[{"file":"a.mp3","name":"A"},{"file":"b.mp3","name":"B"}]}"""
        )
        assertTrue(songs.all { JianyunOfficialContent.isOfficialSongId(it.id) })
    }
}

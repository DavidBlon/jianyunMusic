package com.ncm.app.data.repository

import com.ncm.app.data.cache.LinglanCachePolicy
import com.ncm.app.data.model.PlaybackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinglanCachePolicyTest {

    @Test
    fun `backup bitrates share the same two stable cache qualities`() {
        assertEquals(128000, LinglanCachePolicy.normalizeBitrate(128000))
        assertEquals(320000, LinglanCachePolicy.normalizeBitrate(192000))
        assertEquals(320000, LinglanCachePolicy.normalizeBitrate(999000))
    }

    @Test
    fun `cache key contains song and normalized bitrate`() {
        val key = LinglanCachePolicy.cacheKey(12345L, 192000)

        assertEquals("linglan-audio:12345:320000", key)
        assertTrue(LinglanCachePolicy.isLinglanCacheKey(key))
        assertEquals(12345L, LinglanCachePolicy.songIdFromKey(key))
        assertNull(LinglanCachePolicy.songIdFromKey("https://music.example/song.mp3"))
    }

    @Test
    fun `only remote and cached Linglan sources persist audio`() {
        assertTrue(LinglanCachePolicy.shouldPersist(PlaybackSource.LINGLAN))
        assertTrue(LinglanCachePolicy.shouldPersist(PlaybackSource.LINGLAN_CACHE))
        assertFalse(LinglanCachePolicy.shouldPersist(PlaybackSource.NETEASE))
        assertFalse(LinglanCachePolicy.shouldPersist(PlaybackSource.KUGOU))
    }

    @Test
    fun `only Kugou is transient queue state`() {
        assertTrue(LinglanCachePolicy.isTransientQueueSource(PlaybackSource.KUGOU))
        assertFalse(LinglanCachePolicy.isTransientQueueSource(PlaybackSource.NETEASE))
        assertFalse(LinglanCachePolicy.isTransientQueueSource(PlaybackSource.LINGLAN))
        assertFalse(LinglanCachePolicy.isTransientQueueSource(PlaybackSource.LINGLAN_CACHE))
    }

    @Test
    fun `prefetch accepts direct playable sources but not paid cache sources`() {
        assertTrue(LinglanCachePolicy.isAllowedForPrefetch(PlaybackSource.NETEASE))
        assertTrue(LinglanCachePolicy.isAllowedForPrefetch(PlaybackSource.KUGOU))
        assertTrue(LinglanCachePolicy.isAllowedForPrefetch(PlaybackSource.OFFICIAL))
        assertFalse(LinglanCachePolicy.isAllowedForPrefetch(PlaybackSource.LINGLAN))
        assertFalse(LinglanCachePolicy.isAllowedForPrefetch(PlaybackSource.LINGLAN_CACHE))
    }
}

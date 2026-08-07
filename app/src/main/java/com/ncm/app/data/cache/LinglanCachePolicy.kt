package com.ncm.app.data.cache

import com.ncm.app.data.model.PlaybackSource

/**
 * Pure cache policy kept separate from Media3 so the source boundary can be unit tested.
 */
object LinglanCachePolicy {
    private const val CACHE_KEY_PREFIX = "linglan-audio:"
    private const val STANDARD_BITRATE = 128000
    private const val HIGH_BITRATE = 320000

    fun normalizeBitrate(bitrate: Int): Int {
        return if (bitrate in 1..STANDARD_BITRATE) STANDARD_BITRATE else HIGH_BITRATE
    }

    fun cacheKey(songId: Long, bitrate: Int): String {
        return "$CACHE_KEY_PREFIX$songId:${normalizeBitrate(bitrate)}"
    }

    fun isLinglanCacheKey(key: String?): Boolean {
        return key?.startsWith(CACHE_KEY_PREFIX) == true
    }

    fun songIdFromKey(key: String): Long? {
        if (!isLinglanCacheKey(key)) return null
        return key.removePrefix(CACHE_KEY_PREFIX).substringBefore(':').toLongOrNull()
    }

    fun shouldPersist(source: String): Boolean {
        return source == PlaybackSource.LINGLAN || source == PlaybackSource.LINGLAN_CACHE
    }

    fun isTransientQueueSource(source: String): Boolean {
        return source == PlaybackSource.KUGOU
    }

    fun isAllowedForPrefetch(source: String): Boolean {
        return source == PlaybackSource.NETEASE ||
            source == PlaybackSource.KUGOU ||
            source == PlaybackSource.OFFICIAL
    }
}

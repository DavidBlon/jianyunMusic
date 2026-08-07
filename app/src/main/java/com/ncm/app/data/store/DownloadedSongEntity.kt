package com.ncm.app.data.store

import java.io.File
import java.net.URI

/**
 * 已下载歌曲的本地文件记录（GC #12）：本地文件用独立主键 + URI，不经过在线插件。
 * [complete]=true 且文件仍存在才可播放；残缺/临时缓存不视为有效下载（spec §12）。
 */
data class DownloadedSongEntity(
    val localId: String,
    val sourceTrackKey: String?,      // 来源 ProviderTrackKey 复合键（legacy 或插件）
    val uri: String,
    val title: String,
    val artistsJson: String = "[]",
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val complete: Boolean
) {
    fun isPlayable(): Boolean {
        if (!complete) return false
        if (!uri.startsWith("file:")) return false
        return try {
            // Windows 上 File.toURI() 可能产生单斜杠 file:/C:/...，统一走 URI 解析
            File(URI(uri)).exists()
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        /** 把 LinglanAudioCache 的完整缓存项映射为本地文件记录（阶段 5 迁移入口）。 */
        fun fromLinglanCacheKey(cacheKey: String, uri: String): DownloadedSongEntity? {
            val prefix = "linglan-audio:"
            if (!cacheKey.startsWith(prefix)) return null
            val songId = cacheKey.removePrefix(prefix).substringBefore(":")
            return DownloadedSongEntity(
                localId = "cache-$songId",
                sourceTrackKey = "legacy-netease#$songId",
                uri = uri,
                title = "缓存歌曲 $songId",
                artistsJson = "[]",
                durationMs = null,
                artworkUrl = null,
                complete = true
            )
        }
    }
}

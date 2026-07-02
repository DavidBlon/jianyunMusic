package com.ncm.app.data.repository

import com.google.gson.JsonParser
import com.ncm.app.data.model.ArtistBrief
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 本地替代音源管理器
 *
 * 不依赖外部代理服务器，直接在 Android 本地查询其他平台（如酷狗）
 * 为版权受限的网易云歌曲寻找可播放的替代音源
 */
class UnblockManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    data class SongInfo(
        val id: Long = 0,
        val name: String,
        val artists: List<ArtistBrief> = emptyList(),
        val duration: Long = 0,
        val quality: String = "320k"
    )

    data class MatchResult(
        val url: String,
        val source: String,
        val bitrate: Int = 320000
    )

    suspend fun unblock(info: SongInfo): MatchResult? {
        tryProvider { huibqWy(info) }?.let { return it }
        tryProvider { ikunWy(info) }?.let { return it }
        tryProvider { kugouSearchAndTrack(info)?.let { url -> MatchResult(url, "kugou", 320000) } }
            ?.let { return it }
        return null
    }

    private suspend fun tryProvider(block: suspend () -> MatchResult?): MatchResult? {
        return try {
            withTimeoutOrNull(5_000) { block() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun huibqWy(info: SongInfo): MatchResult? {
        if (info.id <= 0) return null
        val quality = info.quality.takeIf { it == "128k" || it == "320k" } ?: "320k"
        val url = "https://lxmusicapi.onrender.com/url/wy/${info.id}/$quality"
        val body = httpGet(
            url,
            mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "lx-music-mobile/ncm-app",
                "X-Request-Key" to "share-v3"
            )
        )
        val json = JsonParser.parseString(body).asJsonObject
        if ((json.get("code")?.asInt ?: -1) != 0) return null
        val playUrl = json.get("url")?.asString?.takeIf { it.isNotBlank() } ?: return null
        if (!hasEnoughAudioData(playUrl, info.duration)) return null
        return MatchResult(playUrl, "huibq-wy", qualityToBitrate(quality))
    }

    private suspend fun ikunWy(info: SongInfo): MatchResult? {
        if (info.id <= 0) return null
        val quality = info.quality.takeIf { it == "128k" || it == "320k" } ?: "320k"
        val url = "https://api.ikunshare.com/url".toHttpUrl().newBuilder()
            .addQueryParameter("source", "wy")
            .addQueryParameter("songId", info.id.toString())
            .addQueryParameter("quality", quality)
            .build()
            .toString()
        val body = httpGet(
            url,
            mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "lx-music-mobile/ncm-app",
                "X-Request-Key" to ""
            )
        )
        val json = JsonParser.parseString(body).asJsonObject
        if ((json.get("code")?.asInt ?: -1) != 200) return null
        val playUrl = json.get("url")?.asString?.takeIf { it.isNotBlank() } ?: return null
        if (!hasEnoughAudioData(playUrl, info.duration)) return null
        return MatchResult(playUrl, "ikun-wy", qualityToBitrate(quality))
    }

    // ==================== 酷狗音乐 ====================

    private suspend fun kugouSearchAndTrack(info: SongInfo): String? {
        val keyword = buildString {
            append(info.name)
            if (info.artists.isNotEmpty()) {
                append(' ')
                append(info.artists.joinToString(" ") { it.name })
            }
        }

        // Step 1: 搜索
        val searchUrl = "http://songsearch.kugou.com/song_search_v2?keyword=${
            java.net.URLEncoder.encode(keyword, "UTF-8")
        }&page=1&pagesize=10"

        val searchBody = httpGet(searchUrl)
        val searchJson = JsonParser.parseString(searchBody).asJsonObject
        val lists = searchJson
            .getAsJsonObject("data")?.getAsJsonArray("lists") ?: return null

        if (lists.size() == 0) return null

        val candidates = lists.mapNotNull { element ->
            val item = element.asJsonObject
            BackupSongMatcher.Candidate(
                fileHash = item.get("FileHash")?.asString
                    ?: item.get("hash")?.asString
                    ?: return@mapNotNull null,
                name = item.get("SongName")?.asString ?: "",
                duration = (item.get("Duration")?.asInt ?: 0) * 1000L,
                artists = item.get("SingerName")?.asString ?: ""
            )
        }

        // Step 2: 匹配
        val matched = BackupSongMatcher.selectBest(
            songName = info.name,
            artists = info.artists,
            duration = info.duration,
            candidates = candidates
        ) ?: return null

        // Step 3: 获取播放地址
        return kugouGetUrl(matched.fileHash)
    }

    private suspend fun kugouGetUrl(hash: String): String? {
        val key = md5("${hash}kgcloudv2")
        val trackUrl = "http://trackercdn.kugou.com/i/v2/?key=$key&hash=$hash&br=hq&appid=1005&pid=2&cmd=25&behavior=play"
        val body = httpGet(trackUrl)
        val json = JsonParser.parseString(body).asJsonObject
        val urls = json.getAsJsonArray("url") ?: return null
        return urls.firstOrNull()?.asString
    }

    // ==================== 工具函数 ====================

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun qualityToBitrate(quality: String): Int {
        return if (quality == "128k") 128000 else 320000
    }

    private suspend fun hasEnoughAudioData(url: String, durationMs: Long): Boolean {
        if (durationMs <= 0) return true
        val contentLength = httpContentLength(url) ?: return true
        if (contentLength <= 0) return true

        val minimumExpectedBytes = durationMs / 1000 * 96_000 / 8 / 2
        return contentLength >= minimumExpectedBytes
    }

    private suspend fun httpContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            response.header("Content-Length")?.toLongOrNull()
        }
    }

    private suspend fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}: ${response.message}")
            response.body?.string() ?: throw Exception("Empty body")
        }
    }
}

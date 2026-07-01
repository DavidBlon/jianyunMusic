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

    private data class KugouCandidate(
        val fileHash: String,
        val name: String,
        val duration: Long,
        val artists: String
    )

    private data class CandidateScore(
        val candidate: KugouCandidate,
        val total: Double,
        val nameSimilarity: Double,
        val artistSimilarity: Double,
        val durationRatio: Double
    )

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
            KugouCandidate(
                fileHash = item.get("FileHash")?.asString
                    ?: item.get("hash")?.asString
                    ?: return@mapNotNull null,
                name = item.get("SongName")?.asString ?: "",
                duration = (item.get("Duration")?.asInt ?: 0) * 1000L,
                artists = item.get("SingerName")?.asString ?: ""
            )
        }

        // Step 2: 匹配
        val matched = matchSong(info, candidates) ?: return null

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

    // ==================== 歌曲匹配引擎 ====================

    /**
     * 规范化歌名：去括号后缀、去多余空格、去特殊符号
     */
    private fun normalize(str: String): String {
        return str.lowercase()
            .replace(Regex("[（(][^）)]*[）)]"), "")             // 去掉所有括号内容
            .replace(Regex("\\s*(live|伴奏|铃声|完整版|纯音乐|instrumental|cover|remix|版|ver\\.?)\\s*"), " ")
            .replace(Regex("[\\s　]+"), " ")
            .replace(Regex("[，。！？、；：\"\"''【】《》\\-–—·～&@#\$%^*+=\\\\|<>?/]+"), " ")
            .trim()
    }

    /**
     * 基于字符级别的 Jaccard 相似度（更适用于中文）
     */
    private fun charSimilarity(a: String, b: String): Double {
        val na = normalize(a); val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0

        // 如果归一化后相等，直接满分
        if (na == nb) return 1.0
        // 一方包含另一方，高分
        if (na.contains(nb) || nb.contains(na)) return 0.9

        val setA = na.replace(" ", "").toSet()
        val setB = nb.replace(" ", "").toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0.0

        val inter = setA.intersect(setB).size.toDouble()
        val union = setA.union(setB).size.toDouble()
        if (union == 0.0) return 0.0
        return inter / union
    }

    /**
     * 逐个字符顺序匹配比率（按位置比较两首歌名的相似度）
     * 防止字完全不同的歌因为字符集相似而误匹配
     */
    private fun sequentialSimilarity(a: String, b: String): Double {
        val na = normalize(a); val nb = normalize(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        if (na.contains(nb) || nb.contains(na)) return 0.85

        val sa = na.replace(" ", "")
        val sb = nb.replace(" ", "")
        if (sa.isEmpty() || sb.isEmpty()) return 0.0

        // 最长公共子序列比率
        val lcs = longestCommonSubsequence(sa, sb)
        val maxLen = maxOf(sa.length, sb.length)
        return if (maxLen == 0) 0.0 else lcs.toDouble() / maxLen
    }

    private fun longestCommonSubsequence(a: String, b: String): Int {
        val m = a.length; val n = b.length
        if (m == 0 || n == 0) return 0
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) {
                    dp[i - 1][j - 1] + 1
                } else {
                    maxOf(dp[i - 1][j], dp[i][j - 1])
                }
            }
        }
        return dp[m][n]
    }

    /**
     * 艺人名精确匹配：检查候选歌手的名字是否与目标歌手明确匹配
     */
    private fun artistMatch(neteaseArtists: List<String>, candidateArtistStr: String): Double {
        if (neteaseArtists.isEmpty()) return 1.0 // 无艺人信息时不影响评分

        val ca = candidateArtistStr.split(Regex("[、,，/]"))
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() && it.length >= 2 } // 长度至少2才有效

        if (ca.isEmpty()) return 0.0

        // 对网易云每个艺人，检查是否在候选艺人中存在明确匹配
        val matchCount = neteaseArtists.map { na ->
            val name = na.lowercase().trim()
            if (name.isEmpty()) return@map false
            ca.any { cand ->
                // who contains whom — but must be at least 2 chars overlap
                val minLen = minOf(name.length, cand.length)
                if (minLen < 2) return@any false
                cand.contains(name) || name.contains(cand)
            }
        }.count { it }

        return matchCount.toDouble() / maxOf(neteaseArtists.size, 1)
    }

    private fun matchSong(info: SongInfo, candidates: List<KugouCandidate>): KugouCandidate? {
        if (candidates.isEmpty()) return null

        val neteaseArtists = info.artists
            .map { normalizeArtistName(it.name) }
            .filter { it.isNotEmpty() }

        val scored = candidates.map { candidate ->
            val charSim = charSimilarity(info.name, candidate.name)
            val seqSim = sequentialSimilarity(info.name, candidate.name)
            val nameSimilarity = maxOf(charSim, seqSim)
            if (nameSimilarity < 0.45) {
                return@map CandidateScore(candidate, 0.0, nameSimilarity, 0.0, 0.0)
            }

            val artistSimilarity = artistMatch(neteaseArtists, candidate.artists)
            var durationRatio = 1.0
            if (info.duration > 0 && candidate.duration > 0) {
                durationRatio = minOf(info.duration, candidate.duration).toDouble() /
                    maxOf(info.duration, candidate.duration)
            }

            val total = nameSimilarity * 50.0 + artistSimilarity * 35.0 + durationRatio * 15.0
            CandidateScore(candidate, total, nameSimilarity, artistSimilarity, durationRatio)
        }

        val best = scored.maxByOrNull { it.total } ?: return null

        val hasArtist = neteaseArtists.isNotEmpty()
        val hasDuration = info.duration > 0 && best.candidate.duration > 0
        val artistOk = !hasArtist || best.artistSimilarity >= 0.45
        val artistStrong = !hasArtist || best.artistSimilarity >= 0.75
        val durationOk = !hasDuration || best.durationRatio >= 0.78
        val durationStrong = !hasDuration || best.durationRatio >= 0.9

        val strongNameMatch = best.nameSimilarity >= 0.86 && (artistOk || durationOk)
        val balancedMatch = best.nameSimilarity >= 0.68 && artistOk && durationOk
        val metadataMatch = best.nameSimilarity >= 0.52 && artistStrong && durationStrong
        if (!strongNameMatch && !balancedMatch && !metadataMatch) return null

        val origName = normalize(info.name)
        val bestName = normalize(best.candidate.name)
        if (origName.isNotEmpty() && bestName.isNotEmpty()) {
            val hasOverlap = origName.any { c -> bestName.contains(c) }
                || bestName.any { c -> origName.contains(c) }
            if (!hasOverlap) return null
        }

        return best.candidate
    }

    private fun normalizeArtistName(name: String): String {
        return name.lowercase().trim()
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

package com.ncm.app.data.repository

import com.google.gson.JsonParser
import com.ncm.app.BuildConfig
import com.ncm.app.data.MusicSourceSettings
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.data.model.PlaybackSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 本地替代音源管理器
 *
 * 不依赖外部代理服务器，直接在 Android 本地查询其他平台（如酷狗）
 * 为版权受限的网易云歌曲寻找可播放的替代音源
 */
class UnblockManager(
    private val musicSourceSettings: MusicSourceSettings
) {

    private companion object {
        private const val PROVIDER_TIMEOUT_MS = 5_000L
        private const val PROVIDER_HTTP_TIMEOUT_MS = 4_500L
        private const val PROVIDER_FAILURE_THRESHOLD = 2
        private const val PROVIDER_OPEN_DURATION_MS = 60_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(PROVIDER_HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    private val linglanClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val linglanCircuitBreaker = providerCircuitBreaker()
    private val kugouCircuitBreaker = providerCircuitBreaker()

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

    suspend fun unblock(
        info: SongInfo,
        excludedSources: Set<String> = emptySet()
    ): MatchResult? = BackupSourceStrategy.resolve(
        exactProviders = if (
            musicSourceSettings.cardKey.value.isNotBlank() &&
            PlaybackSource.LINGLAN !in excludedSources
        ) {
            listOf(
                {
                    linglanCircuitBreaker.executeAttempt {
                        attemptProvider { linglanWy(info) }
                    }
                }
            )
        } else {
            emptyList()
        },
        searchProviders = if (PlaybackSource.KUGOU !in excludedSources) {
            listOf({ attemptKugou(info) })
        } else {
            emptyList()
        }
    )

    /** Dedicated free-provider path used by queue prefetching. */
    suspend fun kugouOnly(info: SongInfo): MatchResult? {
        return attemptKugou(info)
    }

    private suspend fun attemptKugou(info: SongInfo): MatchResult? {
        return kugouCircuitBreaker.executeAttempt {
            attemptProvider {
                kugouSearchAndTrack(info)?.let { url ->
                    MatchResult(url, PlaybackSource.KUGOU, 320000)
                }
            }
        }
    }

    private fun providerCircuitBreaker() = ProviderCircuitBreaker(
        failureThreshold = PROVIDER_FAILURE_THRESHOLD,
        openDurationMs = PROVIDER_OPEN_DURATION_MS
    )

    private suspend fun attemptProvider(
        block: suspend () -> MatchResult?
    ): ProviderAttempt<MatchResult> {
        return try {
            withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                block()?.let { ProviderAttempt.Success(it) } ?: ProviderAttempt.Miss
            } ?: ProviderAttempt.Failure
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            ProviderAttempt.Failure
        }
    }

    private suspend fun linglanWy(info: SongInfo): MatchResult? {
        if (info.id <= 0) return null
        val apiUrl = BuildConfig.PAID_MUSIC_API_URL.trim().trimEnd('/')
        val apiKey = musicSourceSettings.cardKey.value.trim()
        if (apiUrl.isBlank() || apiKey.isBlank()) return null

        val quality = info.quality.takeIf { it == "128k" || it == "320k" } ?: "320k"
        val baseUrl = apiUrl.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        val url = baseUrl.newBuilder()
            .addPathSegment("url")
            .addQueryParameter("source", "wy")
            .addQueryParameter("songId", info.id.toString())
            .addQueryParameter("quality", quality)
            .build()
            .toString()
        val body = httpGet(
            url,
            mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to "lx-music-android/${BuildConfig.VERSION_NAME}",
                "X-API-Key" to apiKey
            ),
            httpClient = linglanClient
        )
        val json = JsonParser.parseString(body).asJsonObject
        if ((json.get("code")?.asInt ?: -1) != 200) return null
        val playUrl = json.get("url")?.asString?.takeIf { it.isNotBlank() } ?: return null
        if (!hasEnoughAudioData(playUrl, info.duration)) return null
        return MatchResult(playUrl, PlaybackSource.LINGLAN, qualityToBitrate(quality))
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

    private suspend fun httpContentLength(url: String): Long? {
        val request = Request.Builder()
            .url(url)
            .head()
            .build()
        return execute(request) { response ->
            if (response.isSuccessful) {
                response.header("Content-Length")?.toLongOrNull()
            } else {
                null
            }
        }
    }

    private suspend fun httpGet(
        url: String,
        headers: Map<String, String> = emptyMap(),
        httpClient: OkHttpClient = client
    ): String {
        val requestBuilder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.build()
        return execute(request, httpClient) { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }
            response.body?.string() ?: throw IOException("Empty body")
        }
    }

    private suspend fun <T> execute(
        request: Request,
        httpClient: OkHttpClient = client,
        transform: (Response) -> T
    ): T = suspendCancellableCoroutine { continuation ->
        val call = httpClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        try {
                            val result = transform(response)
                            if (continuation.isActive) continuation.resume(result)
                        } catch (error: Exception) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                }
            }
        )
    }
}

package com.ncm.app.data.repository

import android.util.Log
import com.ncm.app.data.SessionManager
import com.ncm.app.data.model.LyricResponse
import com.ncm.app.data.model.SearchResponse
import com.ncm.app.data.model.Song
import com.ncm.app.data.model.SongUrlResponse
import com.ncm.app.domain.weekly.SimilarSong
import com.ncm.app.domain.weekly.WeeklyRecommendationSource
import com.ncm.app.plugin.PluginSearchService
import com.ncm.app.plugin.provider.SearchOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * 简云资料库（P6T2 后）：本地（简云官方目录）+ 插件在线来源。
 * 网易云 API/登录/Cookie/兜底链已移除（spec §13）：网易云旧数据作为 legacy
 * 只读记录保留在本地（P5），不再有平台专用解析逻辑。
 */
class MusicRepository(
    private val pluginSearchService: PluginSearchService? = null
) : WeeklyRecommendationSource {

    private companion object {
        private const val NETWORK_MAX_ATTEMPTS = 3
        private const val NETWORK_RETRY_DELAY_MS = 450L
        private const val JIANYUN_CATALOG_CACHE_MS = 60_000L
        private const val CATALOG_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private val catalogHttp = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
    private val jianyunCatalogMutex = Mutex()
    private var jianyunCatalogSongs: List<Song>? = null
    private var jianyunCatalogFetchedAt: Long = 0L

    // ---- 在线推荐（依赖网易云相似歌曲/推荐歌单的入口已隐藏，spec §18）----

    override suspend fun getSimilarSongs(songId: Long): List<SimilarSong> = emptyList()

    suspend fun getSimilarSongsResult(songId: Long): Result<List<SimilarSong>> = Result.success(emptyList())

    override suspend fun getSongDetails(ids: List<Long>): List<Song> =
        getSongDetail(ids).getOrThrow()

    fun onMusicSourceKeyChanged() {
        // 兜底链已移除（P6T1）：卡密变更不再需要重置任何状态
    }

    // ---- 搜索 ----

    /** 本地搜索：简云官方目录（本地能力不依赖聆澜授权，GC #13）。 */
    suspend fun search(keywords: String, type: Int = 1, offset: Int = 0): Result<SearchResponse> {
        val officialSongs = if (type == 1) getJianyunCatalogSongs() else emptyList()
        val localSongs = JianyunOfficialContent.searchSongs(keywords, officialSongs)
        return Result.success(
            SearchResponse(
                songs = localSongs,
                songCount = localSongs.size
            )
        )
    }

    /** 插件来源搜索：委托给当前来源的插件，失败不跨来源兜底（GC #6）。 */
    suspend fun searchFromPlugin(query: String, page: Int, type: String): Result<SearchOutcome> {
        val service = pluginSearchService
            ?: return Result.failure(IllegalStateException("插件搜索服务未配置"))
        return service.search(query, page, type)
    }

    // ---- 歌曲详情/播放/歌词（仅简云官方；网易云 legacy 不可播放，spec §12）----

    suspend fun getSongDetail(ids: List<Long>): Result<List<Song>> {
        val requestedIds = ids.distinct()
        if (requestedIds.isEmpty()) return Result.success(emptyList())
        val officialById = if (requestedIds.any(JianyunOfficialContent::isOfficialSongId)) {
            getJianyunCatalogSongs().associateBy(Song::id)
        } else {
            emptyMap()
        }
        return Result.success(requestedIds.mapNotNull(officialById::get))
    }

    suspend fun getSongUrl(songId: Long, br: Int = 128000, fee: Int = 0): Result<SongUrlResponse> = safeCall {
        getJianyunSongUrlResponse(songId, br)?.let { return@safeCall it }
        SongUrlResponse(
            url = null,
            br = br,
            code = 404,
            loggedIn = false,
            error = "该歌曲是历史网易云条目，暂不可播放（可在设置中迁移到当前来源）"
        )
    }

    suspend fun getSongUrlWithFallbackTimeout(songId: Long, br: Int = 128000, fee: Int = 0): Result<SongUrlResponse> =
        getSongUrl(songId, br, fee)

    suspend fun getSongUrlForPrefetch(songId: Long, br: Int = 128000, fee: Int = 0): Result<SongUrlResponse> =
        getSongUrl(songId, br, fee)

    suspend fun getLyric(id: Long): Result<LyricResponse> = safeCall {
        if (JianyunOfficialContent.isOfficialSongId(id)) LyricResponse() else LyricResponse()
    }

    /** 歌手详情：仅简云官方歌手（网易云歌手接口已移除）。 */
    suspend fun getArtistDetail(id: Long): Result<com.ncm.app.data.model.ArtistDetail> = safeCall {
        if (id == JianyunOfficialContent.ARTIST_ID) {
            JianyunOfficialContent.artist(getJianyunCatalogSongs())
        } else {
            throw IOException("该歌手来自历史网易云数据，详情暂不可用")
        }
    }

    // ---- 简云官方目录 ----

    private suspend fun getJianyunCatalogSongs(): List<Song> = withContext(Dispatchers.IO) {
        jianyunCatalogMutex.withLock {
            val now = System.currentTimeMillis()
            val cached = jianyunCatalogSongs
            if (cached != null && now - jianyunCatalogFetchedAt < JIANYUN_CATALOG_CACHE_MS) {
                return@withLock cached
            }
            val fetched = runCatching {
                val request = Request.Builder()
                    .url(JianyunOfficialContent.catalogUrl)
                    .get()
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-cache")
                    .header("User-Agent", CATALOG_USER_AGENT)
                    .build()
                catalogHttp.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("简云音乐目录请求失败：HTTP ${response.code}")
                    }
                    JianyunOfficialContent.parseCatalog(response.body?.string().orEmpty())
                }
            }.getOrNull()

            val resolved = fetched
                ?.takeIf { it.isNotEmpty() }
                ?: JianyunOfficialContent.fallbackSongs()
            jianyunCatalogSongs = resolved
            jianyunCatalogFetchedAt = now
            resolved
        }
    }

    private suspend fun getJianyunSongUrlResponse(
        songId: Long,
        bitrate: Int
    ): SongUrlResponse? {
        if (!JianyunOfficialContent.isOfficialSongId(songId)) return null
        val song = getJianyunCatalogSongs().firstOrNull { it.id == songId } ?: return null
        return JianyunOfficialContent.songUrlResponse(song, bitrate)
    }

    // ---- 网络工具 ----

    private suspend fun <T> safeCall(call: suspend () -> T): Result<T> {
        return withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            repeat(NETWORK_MAX_ATTEMPTS) { attempt ->
                try {
                    return@withContext Result.success(call())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    if (!e.isRetryableNetworkError() || attempt == NETWORK_MAX_ATTEMPTS - 1) {
                        return@withContext Result.failure(e.toUserFacingException())
                    }
                    delay(NETWORK_RETRY_DELAY_MS * (attempt + 1))
                }
            }
            Result.failure((lastError ?: IllegalStateException("Unknown network error")).toUserFacingException())
        }
    }

    private fun Exception.isRetryableNetworkError(): Boolean {
        return this is IOException || cause is IOException
    }

    private fun Exception.toUserFacingException(): Exception {
        val friendlyMessage = when {
            this is UnknownHostException || cause is UnknownHostException ->
                "网络解析失败，请检查 DNS 或网络后重试"
            this is SocketTimeoutException || cause is SocketTimeoutException ->
                "网络超时，请稍后重试"
            this is IOException || cause is IOException ->
                "网络连接异常，请稍后重试"
            else -> message
        }
        return if (friendlyMessage == message || friendlyMessage.isNullOrBlank()) {
            this
        } else {
            RuntimeException(friendlyMessage, this)
        }
    }
}

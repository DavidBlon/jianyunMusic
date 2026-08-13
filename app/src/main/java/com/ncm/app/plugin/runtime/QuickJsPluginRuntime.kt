package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.OnlinePlaylist
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.PluginException
import com.ncm.app.plugin.provider.PlaylistOutcome
import com.ncm.app.plugin.provider.SearchOutcome
import kotlinx.coroutines.CancellationException

/** Rebuilds a MusicFree item while preserving source-specific fields needed for playback. */
internal fun pluginInputFor(track: OnlineTrack): Map<String, Any?> = buildMap {
    val payload = track.pluginPayload.toMap()
    (payload["raw"] as? Map<*, *>)?.forEach { (key, value) ->
        if (key is String) put(key, value)
    }
    payload.forEach { (key, value) ->
        if (key != "raw") put(key, value)
    }

    put("id", track.key.remoteId)
    put("name", track.title)
    if (!containsKey("title")) put("title", track.title)
    put("artist", track.artists.joinToString("/") { it.name })
    track.album?.let { album ->
        put("album", album.name)
        album.artworkUrl?.let { artwork ->
            put("cover", artwork)
            if (!containsKey("artwork")) put("artwork", artwork)
        }
    }
    track.durationMs?.let { put("duration", it) }
}

internal fun pluginInputFor(playlist: OnlinePlaylist): Map<String, Any?> = buildMap {
    val payload = playlist.pluginPayload.toMap()
    (payload["raw"] as? Map<*, *>)?.forEach { (key, value) ->
        if (key is String) put(key, value)
    }
    payload.forEach { (key, value) ->
        if (key != "raw") put(key, value)
    }
    put("id", playlist.remoteId)
    put("title", playlist.title)
    playlist.artworkUrl?.let { put("artwork", it) }
}

/**
 * 单个插件的 QuickJS 运行时（GC #7 每插件独立上下文）。可直接作为 P3T7 的 runtimeFactory。
 *
 * 两步装载（过渡模式，2026-08 联调前）：
 * 第一步：禁用真实网络的上下文求值脚本并解析元数据（loadModule）。
 * 第二步：导出方法存在性探针（search/getMediaSource/getLyric 必须存在）。
 * 真实脚本的 search 必须联网，无法在禁用网络的探针里调用（§7.3 的固定响应探针
 * 仅适用于假插件契约测试）；装载后 [httpExecutor] 替换为受控 HTTP 桥供真实调用。
 */
class QuickJsPluginRuntime(
    private val pluginId: String,
    private val script: String,
    private val hostParams: Map<String, Any?> = emptyMap(),
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val httpExecutor: HttpExecutor? = null
) : PluginRuntime {

    private val engine = QuickJsRuntime(callTimeoutMs = callTimeoutMs)
    private var provider: MusicProvider? = null

    /** 契约探针专用执行器：只应答 probe 固定域名，其余一律拒绝（GC #11 不访问真实网络）。 */
    private val probeExecutor: HttpExecutor = { spec ->
        if (spec.url.startsWith("https://probe.example/")) {
            HttpResult(200, mapOf("content-type" to "application/json"), """{"ok":true}""".toByteArray())
        } else {
            throw IllegalStateException("contract probe must not reach real network")
        }
    }

    init {
        // GC #11 第一步：禁用真实网络的上下文求值脚本并解析元数据。
        val meta = engine.loadModule(pluginId, script, hostParams)
        // 第二步：导出方法存在性探针（真实脚本的 search 需联网，不能在探针中调用）。
        val missing = REQUIRED_EXPORTS.filter { name ->
            !engine.hasExport(pluginId, name)
        }
        if (missing.isNotEmpty() || meta.platform.isBlank()) {
            throw PluginException("PROBE_FAILED", "契约探针未通过：缺少导出 ${missing.joinToString()}", retryable = false)
        }
        // 真实调用阶段：受控 HTTP 桥（SSRF 前置校验由 NeteaseApp 组装），缺省回落探针执行器
        engine.useHttpExecutor(httpExecutor ?: probeExecutor)
    }

    override fun providerFor(id: String): MusicProvider? {
        if (id != pluginId) return null
        return provider ?: Provider().also { provider = it }
    }

    override fun load(pluginId: String, script: String, hostParams: Map<String, Any?>): MusicProvider =
        throw UnsupportedOperationException("单插件运行时不可再装载；PluginRegistry 用 runtimeFactory 创建")

    override fun destroy() { engine.destroy(); provider = null }
    override fun isHealthy(): Boolean = provider != null

    private inner class Provider : MusicProvider {
        override val pluginId: String get() = this@QuickJsPluginRuntime.pluginId

        override suspend fun search(query: String, page: Int, type: String): SearchOutcome {
            val raw = engine.invokeMethod(pluginId, "search", arrayOf(query, page, type))
            val data = pluginResultItems(raw, "data", "musicList", "list", "items")
            val normalized = normalizeSearchResult(data) { _, id -> ProviderTrackKey(pluginId, id.toString()) }
            val items = enrichNeteaseTracks(normalized)
            return SearchOutcome(items, isEnd = pluginResultIsEnd(raw, default = items.isEmpty()))
        }

        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia {
            val raw = engine.invokeMethod(pluginId, "getMediaSource", arrayOf(pluginInputFor(track), quality))
            return normalizeResolvedMedia(raw)
        }

        override suspend fun lyric(track: OnlineTrack): LyricOutcome {
            val raw = engine.invokeMethod(pluginId, "getLyric", arrayOf(pluginInputFor(track)))
            if (raw is String) return LyricOutcome(raw, null, null, null)
            val map = raw as? Map<*, *> ?: return LyricOutcome(null, null, null, null)
            return LyricOutcome(
                rawLrc = (map["rawLrc"] ?: map["lrc"] ?: map["lyric"]) as? String,
                translation = (map["translation"] ?: map["tlyric"]) as? String,
                romaLrc = map["romaLrc"] as? String,
                wordLrc = map["wordLrc"] as? String
            )
        }

        override fun supportsTrackInfo(): Boolean =
            pluginId == NETEASE_PLUGIN_ID || engine.hasExport(pluginId, "getMusicInfo")

        override suspend fun trackInfo(track: OnlineTrack): OnlineTrack? {
            if (!supportsTrackInfo()) return null
            if (pluginId == NETEASE_PLUGIN_ID) {
                try {
                    fetchNeteaseTrackInfo(track)?.let { return it }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Fall through to the provider export when the compatibility request fails.
                }
            }
            if (!engine.hasExport(pluginId, "getMusicInfo")) return null
            val raw = engine.invokeMethod(pluginId, "getMusicInfo", arrayOf(pluginInputFor(track)))
            return normalizeTrackInfo(track, raw)
        }

        private suspend fun fetchNeteaseTrackInfo(track: OnlineTrack): OnlineTrack? {
            return fetchNeteaseTrackInfos(listOf(track))
                .singleOrNull()
                ?.takeUnless { it == track }
        }

        private suspend fun enrichNeteaseTracks(tracks: List<OnlineTrack>): List<OnlineTrack> {
            if (pluginId != NETEASE_PLUGIN_ID || tracks.isEmpty()) return tracks
            return try {
                fetchNeteaseTrackInfos(tracks)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                tracks
            }
        }

        private suspend fun fetchNeteaseTrackInfos(tracks: List<OnlineTrack>): List<OnlineTrack> {
            val remoteIds = tracks
                .map { it.key.remoteId }
                .filter { id -> id.isNotBlank() && id.all(Char::isDigit) }
                .distinct()
            if (remoteIds.isEmpty()) return tracks
            val executor = httpExecutor ?: return tracks
            val encodedIds = remoteIds.joinToString("%2C")
            val result = executor(
                HttpRequestSpec(
                    url = "https://music.163.com/api/song/detail/?ids=%5B$encodedIds%5D",
                    method = "GET",
                    headers = mapOf(
                        "Referer" to "https://music.163.com/",
                        "User-Agent" to "JianYunMusic"
                    )
                )
            )
            if (result.status !in 200..299) return tracks
            return normalizeNeteaseTrackInfoResponses(
                originals = tracks,
                responseJson = result.data.toString(Charsets.UTF_8)
            )
        }

        override fun supportsRecommendedSheets(): Boolean =
            engine.hasExport(pluginId, "getRecommendSheetsByTag") ||
                engine.hasExport(pluginId, "getTopLists")

        override suspend fun recommendedSheets(page: Int): PlaylistOutcome {
            if (engine.hasExport(pluginId, "getRecommendSheetsByTag")) {
                val tagCandidates = listOf(
                    mapOf("id" to "10000000", "title" to "推荐"),
                    mapOf("id" to "", "title" to "推荐")
                )
                var firstFailure: Throwable? = null
                tagCandidates.forEach { tag ->
                    val result = runCatching {
                        val raw = engine.invokeMethod(
                            pluginId,
                            "getRecommendSheetsByTag",
                            arrayOf(tag, page)
                        )
                        val items = normalizePlaylistResult(
                            pluginResultItems(raw, "data", "list", "items"),
                            pluginId
                        )
                        PlaylistOutcome(items, pluginResultIsEnd(raw, default = items.isEmpty()))
                    }
                    result.onFailure { if (firstFailure == null) firstFailure = it }
                    result.getOrNull()?.takeIf { it.items.isNotEmpty() }?.let { return it }
                }
                if (!engine.hasExport(pluginId, "getTopLists") && firstFailure != null) {
                    throw firstFailure as Throwable
                }
            }
            if (engine.hasExport(pluginId, "getTopLists")) {
                val raw = engine.invokeMethod(pluginId, "getTopLists", emptyArray())
                val items = normalizeTopListResult(raw, pluginId)
                return PlaylistOutcome(items, isEnd = true)
            }
            return PlaylistOutcome(emptyList(), isEnd = true)
        }

        override fun supportsMusicSheet(): Boolean =
            engine.hasExport(pluginId, "getMusicSheetInfo") ||
                engine.hasExport(pluginId, "getTopListDetail")

        override suspend fun musicSheetInfo(sheet: Any, page: Int): List<OnlineTrack> {
            val onlineSheet = sheet as? OnlinePlaylist ?: return emptyList()
            val isTopList = onlineSheet.pluginPayload.toMap()["hostCapability"] == "top-list"
            val method = if (isTopList) "getTopListDetail" else "getMusicSheetInfo"
            if (!engine.hasExport(pluginId, method)) return emptyList()
            val args: Array<Any?> = if (isTopList) {
                arrayOf(pluginInputFor(onlineSheet))
            } else {
                arrayOf(pluginInputFor(onlineSheet), page)
            }
            val raw = engine.invokeMethod(pluginId, method, args)
            val musicList = pluginResultItems(raw, "musicList", "data", "list", "items")
            val normalized = normalizeSearchResult(musicList) { _, id ->
                ProviderTrackKey(pluginId, id.toString())
            }
            return enrichNeteaseTracks(normalized)
        }

        override fun supportsTopLists(): Boolean = engine.hasExport(pluginId, "getTopLists")

        override suspend fun topLists(): List<Any> {
            if (!supportsTopLists()) return emptyList()
            return normalizeTopListResult(
                engine.invokeMethod(pluginId, "getTopLists", emptyArray()),
                pluginId
            )
        }

        override suspend fun topListDetail(topList: Any): List<OnlineTrack> =
            musicSheetInfo(topList, page = 1)

    }

    private companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 10_000L
        const val NETEASE_PLUGIN_ID = "linglan.wy"
        val REQUIRED_EXPORTS = listOf("search", "getMediaSource", "getLyric")
    }
}

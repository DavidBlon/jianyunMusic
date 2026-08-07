package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.PluginException
import com.ncm.app.plugin.provider.SearchOutcome

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
            engine.invokeMethod(pluginId, "hasExport", arrayOf(name)) != true
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
            val map = raw as? Map<*, *> ?: return SearchOutcome(emptyList(), isEnd = true)
            val data = map["data"] as? List<*> ?: emptyList<Any?>()
            val items = normalizeSearchResult(data) { _, id -> ProviderTrackKey(pluginId, id.toString()) }
            return SearchOutcome(items, isEnd = map["isEnd"] as? Boolean ?: false)
        }

        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia {
            val raw = engine.invokeMethod(pluginId, "getMediaSource", arrayOf(toJsItem(track), quality))
            return normalizeResolvedMedia(raw)
        }

        override suspend fun lyric(track: OnlineTrack): LyricOutcome {
            val raw = engine.invokeMethod(pluginId, "getLyric", arrayOf(toJsItem(track)))
            val map = raw as? Map<*, *> ?: return LyricOutcome(null, null, null, null)
            return LyricOutcome(
                rawLrc = map["rawLrc"] as? String,
                translation = map["translation"] as? String,
                romaLrc = map["romaLrc"] as? String,
                wordLrc = map["wordLrc"] as? String
            )
        }
    }

    /** 把 Kotlin OnlineTrack 重建为插件输入 item：标准字段 + 受控 pluginPayload（spec §6.2）。 */
    private fun toJsItem(track: OnlineTrack): Map<String, Any?> = buildMap {
        put("id", track.key.remoteId)
        put("name", track.title)
        put("artist", track.artists.joinToString("/") { it.name })
        track.album?.let { album ->
            put("album", album.name)
            album.artworkUrl?.let { put("cover", it) }
        }
        track.durationMs?.let { put("duration", it) }
        putAll(track.pluginPayload.toMap())
    }

    private companion object {
        const val DEFAULT_CALL_TIMEOUT_MS = 10_000L
        val REQUIRED_EXPORTS = listOf("search", "getMediaSource", "getLyric")
    }
}

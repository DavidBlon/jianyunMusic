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
 * 注：契约探针（GC #11 第二步）在禁用真实网络的环境下调用插件 search；
 * 真实脚本联调（阶段 4）前需与聆澜确认探针策略（§17）。
 */
class QuickJsPluginRuntime(
    private val pluginId: String,
    private val script: String,
    private val hostParams: Map<String, Any?> = emptyMap(),
    private val callTimeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
    private val probeTimeoutMs: Long = PROBE_TIMEOUT_MS
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
        // GC #11 两步装载：
        // 第一步：禁用真实网络的上下文求值脚本并解析元数据（P3T1.loadModule）。
        engine.loadModule(pluginId, script, hostParams)
        // 探针期间把受控 HTTP 桥固定为 probeExecutor（只应答 probe 域名，不访问真实网络）。
        engine.useHttpExecutor(probeExecutor)
        // 第二步：宿主固定 HTTP 响应执行契约探针（P3T4）；未通过则抛错，registry 不激活候选。
        val probe = runContractProbe(pluginId) {
            engine.invokeMethod(pluginId, "search", arrayOf("__probe__", 1, "music"))
        }
        if (!probe.healthy) {
            throw PluginException("PROBE_FAILED", "契约探针未通过：${probe.reason}", retryable = false)
        }
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
        const val PROBE_TIMEOUT_MS = 2_000L
    }
}

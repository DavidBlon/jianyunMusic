package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.compat.MUSICFREE_PROTOCOL_VERSION
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia

/** 单页结果条数上限：拒绝超大响应（GC #7，配合桥的响应体上限）。 */
const val MAX_RESULTS_PER_PAGE = 64

/** 插件返回值的结构/协议/URL/大小校验后进入应用模型（GC #7）。必需字段缺失拒绝该条。 */
fun normalizeSearchResult(
    raw: List<*>,
    keyFor: (pluginId: String, id: Any) -> ProviderTrackKey
): List<OnlineTrack> = raw.mapNotNull { element ->
    val item = element as? Map<*, *> ?: return@mapNotNull null
    val name = item["name"] as? String
    val id = item["id"]
    if (name.isNullOrBlank() || id == null) return@mapNotNull null
    OnlineTrack(
        key = keyFor("", id),
        producedByPluginVersion = "probe",
        payloadSchemaVersion = MUSICFREE_PROTOCOL_VERSION,
        title = name,
        artists = (item["artist"] as? String)?.split("/")?.map { OnlineArtist(it.trim(), it.trim()) }.orEmpty(),
        album = null,
        durationMs = (item["duration"] as? Number)?.toLong(),
        artworkUrl = item["cover"] as? String,
        pluginPayload = BoundedJsonObject.fromMap(
            mapOf("raw" to item)
        )
    )
}.take(MAX_RESULTS_PER_PAGE)

fun normalizeResolvedMedia(raw: Any?): ResolvedMedia {
    val map = raw as? Map<*, *> ?: throw IllegalStateException("resolved media must be an object")
    val url = map["url"] as? String
    if (url.isNullOrBlank()) throw IllegalStateException("resolved media missing url")
    if (!url.startsWith("http://") && !url.startsWith("https://")) {
        throw IllegalStateException("resolved media url must be http(s)")
    }
    val headers = (map["headers"] as? Map<*, *>)?.entries
        ?.filter { it.key is String && it.value is String }
        ?.associate { it.key as String to it.value as String }
        .orEmpty()
    return ResolvedMedia(
        url = url,
        headers = headers,
        userAgent = map["userAgent"] as? String,
        quality = map["quality"] as? String,
        expiresAtEpochMs = (map["expiresAt"] as? Number)?.toLong()
    )
}

data class ProbeResult(val healthy: Boolean, val reason: String? = null)

/**
 * 契约探针（GC #11 第二步）：调用 [invokeProbe]（宿主固定 HTTP 响应环境下的插件 search，
 * 见 P3T8 的 probeExecutor），验证返回合法结果。任何指向真实网络的请求都会抛错 → 探针失败。
 */
fun runContractProbe(
    pluginId: String,
    invokeProbe: () -> Any?
): ProbeResult = try {
    val map = invokeProbe() as? Map<*, *>
    if (map?.get("data") !is List<*>) {
        ProbeResult(false, "契约探针未返回 data 数组")
    } else {
        ProbeResult(true)
    }
} catch (e: Exception) {
    ProbeResult(false, "契约探针失败：${e.message}")
}

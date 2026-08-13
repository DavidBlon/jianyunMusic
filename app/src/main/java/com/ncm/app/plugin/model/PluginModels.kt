package com.ncm.app.plugin.model

/**
 * 在线音乐来源的稳定描述（来自聆澜清单，spec §9）。
 * [downloadUrl] 只存在于清单获取与下载过程的内存中；含用户凭据的 URL 不得持久化（GC #4）。
 */
data class PluginDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val protocolVersion: Int,
    val minHostVersion: String?,
    val downloadUrl: String,
    val category: PluginCategory,
    val integrity: String?,   // sha256 摘要，见 GC #10
    val status: PluginReleaseStatus
)

enum class PluginCategory { MUSIC, OTHER }

enum class PluginReleaseStatus { ACTIVE, MANDATORY_UPDATE, REVOKED, DISABLED }

fun pluginSourceDisplayName(pluginId: String): String = when (pluginId) {
    "linglan.kw" -> "酷我"
    "linglan.kg" -> "酷狗"
    "linglan.tx" -> "QQ音乐"
    "linglan.wy" -> "网易云"
    else -> pluginId
}

/**
 * 来源感知的歌曲主键：pluginId + remoteId。
 * 收藏、历史、队列、缓存和下载记录都以它标识在线歌曲（spec §5）。
 */
data class ProviderTrackKey(
    val pluginId: String,
    val remoteId: String
) {
    fun asComposite(): String = "$pluginId#$remoteId"

    companion object {
        fun fromComposite(value: String): ProviderTrackKey? {
            val index = value.indexOf('#')
            if (index <= 0 || index == value.length - 1) return null
            val plugin = value.substring(0, index)
            val remote = value.substring(index + 1)
            if (plugin.isBlank() || remote.isBlank()) return null
            return ProviderTrackKey(pluginId = plugin, remoteId = remote)
        }
    }
}

data class OnlineArtist(val remoteId: String, val name: String)

data class OnlineAlbum(val remoteId: String, val name: String, val artworkUrl: String? = null)

/** 在线歌曲实体。附加字段放在受控 [pluginPayload] 中，不把整个脚本返回对象无限持久化（GC #14）。 */
data class OnlineTrack(
    val key: ProviderTrackKey,
    val producedByPluginVersion: String,
    val payloadSchemaVersion: Int,
    val title: String,
    val artists: List<OnlineArtist>,
    val album: OnlineAlbum?,
    val durationMs: Long?,
    val artworkUrl: String?,
    val pluginPayload: BoundedJsonObject
)

/** A source-owned playlist returned by the active MusicFree provider. */
data class OnlinePlaylist(
    val pluginId: String,
    val remoteId: String,
    val title: String,
    val artworkUrl: String?,
    val description: String?,
    val playCount: Long,
    val creator: String?,
    val pluginPayload: BoundedJsonObject
)

/** 已解析的播放媒体：播放地址通常具有时效性，不作为歌曲实体永久保存（spec §5）。 */
data class ResolvedMedia(
    val url: String,
    val headers: Map<String, String>,
    val userAgent: String?,
    val quality: String?,
    val expiresAtEpochMs: Long?
)

/**
 * 经过清洗、可序列化、有大小与层级上限的插件负载容器（GC #14）。
 * 不保存密钥/授权头/个性化 URL；越界数据被整体拒绝而非截断猜测。
 */
class BoundedJsonObject private constructor(private val entries: Map<String, Any?>) {

    fun toMap(): Map<String, Any?> = entries

    fun sizeBytes(): Int = try {
        GSON.toJson(entries).toByteArray(Charsets.UTF_8).size
    } catch (_: Exception) {
        0
    }

    companion object {
        private val GSON = com.google.gson.Gson()
        private const val MAX_DEPTH = 6
        private const val MAX_ENTRIES = 64

        fun fromMap(raw: Map<String, Any?>): BoundedJsonObject {
            if (exceedsLimits(raw, depth = 0)) return BoundedJsonObject(emptyMap())
            val cleaned = sanitize(raw, depth = 0)
            return BoundedJsonObject(cleaned as? Map<String, Any?> ?: emptyMap())
        }

        /** 任一子树超过层级或条目上限即拒绝整个负载（GC #14「拒绝而非截断」）。 */
        private fun exceedsLimits(value: Any?, depth: Int): Boolean = when (value) {
            is Map<*, *> ->
                depth >= MAX_DEPTH || value.size > MAX_ENTRIES ||
                    value.entries.any { it.key is String && exceedsLimits(it.value, depth + 1) }
            is Iterable<*> ->
                depth >= MAX_DEPTH || value.count() > MAX_ENTRIES ||
                    value.any { exceedsLimits(it, depth + 1) }
            else -> false
        }

        /** 深度复制并只保留合法类型；上限已由 [exceedsLimits] 保证，此处为防御性兜底。 */
        private fun sanitize(value: Any?, depth: Int): Any? = when (value) {
            null, is String, is Number, is Boolean -> value
            is Map<*, *> -> if (depth >= MAX_DEPTH || value.size > MAX_ENTRIES) {
                null
            } else {
                value.entries
                    .filter { it.key is String }
                    .take(MAX_ENTRIES)
                    .associate { it.key as String to sanitize(it.value, depth + 1) }
            }
            is Iterable<*> -> if (depth >= MAX_DEPTH) null else value.take(MAX_ENTRIES).map { sanitize(it, depth + 1) }
            else -> null
        }
    }
}

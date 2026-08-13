package com.ncm.app.plugin.runtime

import com.google.gson.JsonParser
import com.ncm.app.plugin.compat.MUSICFREE_PROTOCOL_VERSION
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineAlbum
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlinePlaylist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.model.normalizedTrackDurationMs

/** 单页结果条数上限：拒绝超大响应（GC #7，配合桥的响应体上限）。 */
const val MAX_RESULTS_PER_PAGE = 64

/**
 * MusicFree scripts in the wild use all three of these shapes for collection results:
 * a direct array, an object containing the array, or an extra object wrapper around it.
 * Keep that variation at the plugin boundary so view models only see stable models.
 */
internal fun pluginResultItems(raw: Any?, vararg keys: String): List<*> {
    if (raw is List<*>) return raw
    val map = raw as? Map<*, *> ?: return emptyList<Any?>()
    keys.forEach { key ->
        when (val value = map[key]) {
            is List<*> -> return value
            is Map<*, *> -> {
                val nested = pluginResultItems(value, *keys)
                if (nested.isNotEmpty()) return nested
            }
        }
    }
    return emptyList<Any?>()
}

internal fun pluginResultIsEnd(raw: Any?, default: Boolean = false): Boolean =
    ((raw as? Map<*, *>)?.get("isEnd") as? Boolean) ?: default

/** 插件返回值的结构/协议/URL/大小校验后进入应用模型（GC #7）。必需字段缺失拒绝该条。 */
fun normalizeSearchResult(
    raw: List<*>,
    keyFor: (pluginId: String, id: Any) -> ProviderTrackKey
): List<OnlineTrack> = raw.mapNotNull { element ->
    val item = element as? Map<*, *> ?: return@mapNotNull null
    val name = (item["name"] as? String).orEmpty().ifBlank {
        (item["title"] as? String).orEmpty()
    }
    val id = item["id"]
    if (name.isBlank() || id == null) return@mapNotNull null
    val artwork = (item["cover"] as? String).orEmpty().ifBlank {
        (item["artwork"] as? String).orEmpty()
    }.ifBlank { null }
    val albumName = item["album"] as? String
    val album = albumName?.takeIf { it.isNotBlank() }?.let {
        OnlineAlbum(
            remoteId = (item["albumid"] ?: item["albumId"] ?: it).toString(),
            name = it,
            artworkUrl = artwork
        )
    }
    OnlineTrack(
        key = keyFor("", id),
        producedByPluginVersion = "probe",
        payloadSchemaVersion = MUSICFREE_PROTOCOL_VERSION,
        title = name,
        artists = (item["artist"] as? String)
            ?.split(Regex("[/,，、]+"))
            ?.mapNotNull { artist -> artist.trim().takeIf(String::isNotEmpty) }
            ?.map { artist -> OnlineArtist(artist, artist) }
            .orEmpty(),
        album = album,
        durationMs = normalizedTrackDurationMs(item),
        artworkUrl = artwork,
        pluginPayload = BoundedJsonObject.fromMap(
            mapOf("raw" to item)
        )
    )
}.take(MAX_RESULTS_PER_PAGE)

/** Merges a provider's getMusicInfo response while keeping the original provider identity. */
internal fun normalizeTrackInfo(original: OnlineTrack, raw: Any?): OnlineTrack? {
    val item = raw as? Map<*, *> ?: return null
    val merged = buildMap<Any?, Any?> {
        item.forEach { (key, value) -> put(key, value) }
        putIfAbsent("id", original.key.remoteId)
        putIfAbsent("title", original.title)
        putIfAbsent("artist", original.artists.joinToString("/") { it.name })
        original.album?.let { album ->
            putIfAbsent("album", album.name)
            putIfAbsent("albumid", album.remoteId)
        }
    }
    val normalized = normalizeSearchResult(listOf(merged)) { _, _ -> original.key }
        .singleOrNull() ?: return null
    return original.copy(
        durationMs = normalized.durationMs ?: original.durationMs,
        artworkUrl = original.artworkUrl ?: normalized.artworkUrl,
        album = original.album ?: normalized.album,
        pluginPayload = normalized.pluginPayload
    )
}

/** Compatibility for the NetEase plugin, whose getMusicInfo export drops artwork and duration. */
internal fun normalizeNeteaseTrackInfoResponses(
    originals: List<OnlineTrack>,
    responseJson: String
): List<OnlineTrack> = runCatching {
        val root = JsonParser.parseString(responseJson).asJsonObject
        val songsById = root.getAsJsonArray("songs")
            ?.mapNotNull { element ->
                val song = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = song.get("id")?.asString ?: return@mapNotNull null
                id to song
            }
            ?.toMap()
            .orEmpty()
        originals.map { original ->
            val song = songsById[original.key.remoteId] ?: return@map original
            normalizeNeteaseTrackInfoSong(original, song) ?: original
        }
    }.getOrDefault(originals)

internal fun normalizeNeteaseTrackInfoResponse(original: OnlineTrack, responseJson: String): OnlineTrack? =
    normalizeNeteaseTrackInfoResponses(listOf(original), responseJson)
        .singleOrNull()
        ?.takeUnless { it == original }

private fun normalizeNeteaseTrackInfoSong(
    original: OnlineTrack,
    song: com.google.gson.JsonObject
): OnlineTrack? = runCatching {
        val album = (song.getAsJsonObject("album") ?: song.getAsJsonObject("al"))
        val artists = (song.getAsJsonArray("artists") ?: song.getAsJsonArray("ar"))
            ?.mapNotNull { artist ->
                artist.asJsonObject.get("name")?.asString?.takeIf(String::isNotBlank)
            }
            .orEmpty()
        val durationMs = (song.get("duration") ?: song.get("dt"))
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
            ?.asLong
        val payload = buildMap<String, Any?> {
            put("id", song.get("id")?.asString ?: original.key.remoteId)
            put("title", song.get("name")?.asString ?: original.title)
            put("artist", artists.joinToString("/").ifBlank {
                original.artists.joinToString("/") { it.name }
            })
            album?.get("name")?.asString?.let { put("album", it) }
            album?.get("id")?.asString?.let { put("albumid", it) }
            album?.get("picUrl")?.asString?.let { put("artwork", it) }
            durationMs?.let { put("durationMs", it) }
        }
        normalizeTrackInfo(original, payload)
    }.getOrNull()

fun normalizePlaylistResult(raw: List<*>, pluginId: String): List<OnlinePlaylist> =
    raw.mapNotNull { element ->
        val item = element as? Map<*, *> ?: return@mapNotNull null
        val id = (item["id"] ?: item["remoteId"] ?: item["specialid"] ?: item["dissid"])
            ?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val title = ((item["title"] ?: item["name"]) as? String)
            ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val artwork = ((item["artwork"] ?: item["cover"] ?: item["coverImg"]
            ?: item["coverImgUrl"] ?: item["pic"] ?: item["imgurl"]) as? String)
            ?.takeIf(String::isNotBlank)
        val rawMap = item.entries
            .filter { it.key is String }
            .associate { it.key as String to it.value }
        val creatorValue = item["artist"] ?: item["creator"] ?: item["nickname"]
        val creator = when (creatorValue) {
            is String -> creatorValue
            is Map<*, *> -> (creatorValue["name"] ?: creatorValue["nickname"]) as? String
            else -> null
        }?.takeIf(String::isNotBlank)
        val rawPlayCount = item["playCount"] ?: item["playcount"] ?: item["listenNum"]
        OnlinePlaylist(
            pluginId = pluginId,
            remoteId = id,
            title = title,
            artworkUrl = artwork,
            description = ((item["description"] ?: item["desc"] ?: item["intro"]) as? String)
                ?.takeIf(String::isNotBlank),
            playCount = when (rawPlayCount) {
                is Number -> rawPlayCount.toLong()
                is String -> rawPlayCount.toLongOrNull() ?: 0L
                else -> 0L
            },
            creator = creator,
            pluginPayload = BoundedJsonObject.fromMap(mapOf("raw" to rawMap))
        )
    }.take(MAX_RESULTS_PER_PAGE)

/** Turns grouped chart exports into the same playlist model used by recommendation cards. */
internal fun normalizeTopListResult(raw: Any?, pluginId: String): List<OnlinePlaylist> {
    val roots = pluginResultItems(raw, "data", "list", "topLists")
    val flattened = roots.flatMap { element ->
        val group = element as? Map<*, *> ?: return@flatMap emptyList<Any?>()
        val children = group["data"] ?: group["list"] ?: group["items"]
        (children as? List<*>) ?: listOf(group)
    }
    return normalizePlaylistResult(flattened, pluginId).map { playlist ->
        playlist.copy(
            pluginPayload = BoundedJsonObject.fromMap(
                mapOf(
                    "raw" to playlist.pluginPayload.toMap()["raw"],
                    "hostCapability" to "top-list"
                )
            )
        )
    }
}

fun normalizeResolvedMedia(raw: Any?): ResolvedMedia {
    val map = when (raw) {
        is String -> mapOf("url" to raw)
        is Map<*, *> -> raw
        else -> throw IllegalStateException("resolved media must be an object or URL")
    }
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

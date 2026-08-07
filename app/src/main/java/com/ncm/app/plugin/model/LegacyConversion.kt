package com.ncm.app.plugin.model

import com.ncm.app.data.model.Song
import com.ncm.app.data.repository.JianyunOfficialContent

const val LEGACY_NETEASE_PLUGIN_ID = "legacy-netease"

fun legacyNeteaseKey(songId: Long): ProviderTrackKey =
    ProviderTrackKey(pluginId = LEGACY_NETEASE_PLUGIN_ID, remoteId = songId.toString())

fun ProviderTrackKey.isLegacyNetease(): Boolean = pluginId == LEGACY_NETEASE_PLUGIN_ID

/** UI 展示用，隐藏 pluginId 细节（spec §5）。 */
fun ProviderTrackKey.toDisplayKey(): String =
    if (isLegacyNetease()) "网易云 #$remoteId" else remoteId

/** 网易云来源歌曲 → 只读 legacy 记录；本地/简云官方来源不转换（spec §12）。 */
fun Song.toLegacyOnlineTrack(): OnlineTrack? {
    if (mediaFileName != null) return null          // 本地文件
    if (JianyunOfficialContent.isOfficialSongId(id)) return null  // 简云官方
    if (id <= 0) return null
    return OnlineTrack(
        key = legacyNeteaseKey(id),
        producedByPluginVersion = "legacy",
        payloadSchemaVersion = 0,
        title = name,
        artists = artists.orEmpty().map { OnlineArtist(it.id.toString(), it.name) },
        album = album?.let { OnlineAlbum(it.id.toString(), it.name, it.picUrl) },
        durationMs = dt.takeIf { it > 0 },
        artworkUrl = album?.picUrl,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )
}

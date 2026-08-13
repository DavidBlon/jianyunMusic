package com.ncm.app.data.store

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineAlbum
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.normalizedTrackDurationMs

internal fun OnlineTrack.toStoredEntity(gson: Gson): OnlineSongEntity = OnlineSongEntity(
    pluginId = key.pluginId,
    remoteId = key.remoteId,
    title = title,
    artistsJson = gson.toJson(artists),
    albumJson = album?.let(gson::toJson),
    durationMs = durationMs,
    artworkUrl = artworkUrl,
    pluginPayloadJson = gson.toJson(pluginPayload.toMap()),
    producedByPluginVersion = producedByPluginVersion,
    payloadSchemaVersion = payloadSchemaVersion
)

internal fun OnlineSongEntity.toOnlineTrack(gson: Gson): OnlineTrack? = runCatching {
    val artistsType = object : TypeToken<List<OnlineArtist>>() {}.type
    val payloadType = object : TypeToken<Map<String, Any?>>() {}.type
    val artists: List<OnlineArtist> = gson.fromJson(artistsJson, artistsType) ?: emptyList()
    val payload: Map<String, Any?> = gson.fromJson(pluginPayloadJson, payloadType) ?: emptyMap()

    OnlineTrack(
        key = ProviderTrackKey(pluginId, remoteId),
        producedByPluginVersion = producedByPluginVersion,
        payloadSchemaVersion = payloadSchemaVersion,
        title = title,
        artists = artists,
        album = albumJson?.let { gson.fromJson(it, OnlineAlbum::class.java) },
        durationMs = (payload["raw"] as? Map<*, *>)
            ?.let(::normalizedTrackDurationMs)
            ?: durationMs,
        artworkUrl = artworkUrl,
        pluginPayload = BoundedJsonObject.fromMap(payload)
    )
}.getOrNull()

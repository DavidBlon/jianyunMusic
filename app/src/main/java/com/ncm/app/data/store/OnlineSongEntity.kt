package com.ncm.app.data.store

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "online_songs",
    primaryKeys = ["pluginId", "remoteId"],
    indices = [Index("pluginId")]
)
data class OnlineSongEntity(
    val pluginId: String,
    val remoteId: String,
    val title: String,
    val artistsJson: String = "[]",        // JSON 数组 [{remoteId,name}]
    val albumJson: String? = null,         // JSON {remoteId,name,artworkUrl}
    val durationMs: Long? = null,
    val artworkUrl: String? = null,
    val pluginPayloadJson: String = "{}",  // BoundedJsonObject.toMap() 的 JSON
    val producedByPluginVersion: String = "",
    val payloadSchemaVersion: Int = 1
) {
    fun asCompositeKey(): String = "$pluginId#$remoteId"
}

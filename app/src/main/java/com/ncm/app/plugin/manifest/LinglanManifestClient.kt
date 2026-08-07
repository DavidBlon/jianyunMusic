package com.ncm.app.plugin.manifest

import com.google.gson.JsonParser
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus

data class ManifestItem(
    val id: String,
    val name: String,
    val version: String,
    val url: String,
    val category: PluginCategory,
    val protocolVersion: Int,
    val minHostVersion: String?,
    val status: PluginReleaseStatus,
    val sha256: String?,
    val signature: String? = null,            // 生产环境必需（spec §9），Base64
    val signatureTimestamp: Long? = null      // 签名时间（毫秒），防重放（GC #10）
)

/** 拉取聆澜插件清单。HTTP 通过构造注入，生产用 OkHttp（P2T5），单测用固定响应。 */
class LinglanManifestClient(
    private val http: suspend (String) -> String
) {
    /**
     * 解析清单 JSON。畸形响应按空表处理是契约要求（「清单不可用按空处理」），
     * 不是隐藏错误，调用方据此展示重试入口。
     */
    suspend fun fetch(): List<ManifestItem> = try {
        val root = JsonParser.parseString(http(ENDPOINT)).asJsonObject
        val plugins = root.getAsJsonArray("plugins") ?: return emptyList()
        plugins.mapNotNull { element ->
            val item = element.asJsonObject
            val statusText = item.get("status")?.asString ?: "active"
            val status = PluginReleaseStatus.entries
                .firstOrNull { it.name.equals(statusText, ignoreCase = true) }
                ?: return@mapNotNull null
            ManifestItem(
                id = item.get("id")?.asString?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                name = item.get("name")?.asString ?: "",
                version = item.get("version")?.asString ?: "",
                url = item.get("url")?.asString ?: "",
                category = if ((item.get("category")?.asString ?: "") == "music") {
                    PluginCategory.MUSIC
                } else {
                    PluginCategory.OTHER
                },
                protocolVersion = item.get("protocolVersion")?.asInt ?: 1,
                minHostVersion = item.get("minHostVersion")?.asString,
                status = status,
                sha256 = item.get("sha256")?.asString,
                signature = item.get("signature")?.asString,
                signatureTimestamp = item.get("signedAt")?.asLong ?: item.get("signatureTimestamp")?.asLong
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    private companion object {
        const val ENDPOINT = "https://linglan.invalid/manifest"  // 生产替换，见 P2T5
    }
}

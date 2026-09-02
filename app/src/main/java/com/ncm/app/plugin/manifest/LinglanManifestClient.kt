package com.ncm.app.plugin.manifest

import com.google.gson.JsonParser
import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

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
    val signature: String? = null,
    val signatureTimestamp: Long? = null
)

/** 在线来源清单客户端。密钥由调用方通过请求头传递，不进入 URL 查询参数。 */
class LinglanManifestClient(
    private val http: suspend (url: String, secret: String) -> String,
    private val endpointTemplate: String = DEFAULT_ENDPOINT_TEMPLATE
) {
    suspend fun fetch(secret: String): List<ManifestItem> = try {
        val url = requestUrl()
        val root = JsonParser.parseString(
            withContext(Dispatchers.IO) { http(url, secret) }
        ).asJsonObject
        val plugins = root.getAsJsonArray("plugins") ?: return emptyList()
        plugins.mapNotNull { element ->
            val item = element.asJsonObject
            val statusText = item.get("status")?.asString ?: "active"
            val status = PluginReleaseStatus.entries
                .firstOrNull { it.name.equals(statusText, ignoreCase = true) }
                ?: return@mapNotNull null
            val url = item.get("url")?.asString?.trim() ?: return@mapNotNull null
            ManifestItem(
                id = item.get("id")?.asString?.trim()?.takeIf { it.isNotBlank() }
                    ?: inferStablePluginIdFromUrl(url)
                    ?: return@mapNotNull null,
                name = item.get("name")?.asString ?: "",
                version = item.get("version")?.asString ?: "",
                url = url,
                category = PluginCategory.MUSIC,
                protocolVersion = item.get("protocolVersion")?.asInt ?: 1,
                minHostVersion = item.get("minHostVersion")?.asString,
                status = status,
                sha256 = item.get("sha256")?.asString,
                signature = item.get("signature")?.asString,
                signatureTimestamp = item.get("signedAt")?.asLong ?: item.get("signatureTimestamp")?.asLong
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    internal fun requestUrl(): String {
        val endpoint = endpointTemplate.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("\u5728\u7ebf\u6765\u6e90\u5730\u5740\u65e0\u6548")
        return endpoint.toString()
    }

    companion object {
        const val DEFAULT_ENDPOINT_TEMPLATE = "https://source.shiqianjiang.cn/api/script/mf.json"
    }
}

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

/**
 * 拉取聆澜插件清单（真实端点：{apiRoot}/script/mf.json?key={密钥}）。
 *
 * 密钥只存在于构造 URL 的内存中：不持久化、不写入日志/崩溃报告/分析事件（GC #4/#15）。
 * 清单当前无稳定 id 字段（已探测确认），按 GC #5 内置 URL 路径映射推断稳定 ID
 * （/mf/kg.js → linglan.kg 等）；推断不出（如 bilibili/git.js）的条目被丢弃。
 */
class LinglanManifestClient(
    private val http: suspend (String) -> String,
    private val endpointTemplate: String = DEFAULT_ENDPOINT_TEMPLATE
) {
    /**
     * 解析清单 JSON。畸形响应按空表处理是契约要求（「清单不可用按空处理」），
     * 不是隐藏错误，调用方据此展示重试入口。
     */
    suspend fun fetch(secret: String): List<ManifestItem> = try {
        val url = endpointTemplate + secret
        val root = JsonParser.parseString(http(url)).asJsonObject
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
                // 聆澜 mf.json 无 category 字段：按 MUSIC 处理，来源过滤由 allowlist 把关（GC #9）
                category = PluginCategory.MUSIC,
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

    companion object {
        /** 生产端点模板：密钥由调用方拼接（仅内存使用）；可经构造注入覆盖。 */
        const val DEFAULT_ENDPOINT_TEMPLATE = "https://source.shiqianjiang.cn/api/script/mf.json?key="
    }
}

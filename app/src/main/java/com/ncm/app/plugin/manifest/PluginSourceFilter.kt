package com.ncm.app.plugin.manifest

import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus

data class SourceAllowRule(
    val hostPrefix: String,
    val pathPrefix: String,
    val sourceType: String
) {
    fun matches(url: String): Boolean {
        val clean = url.substringBefore('?')            // 去除查询参数（GC #5）
        val host = clean.substringAfter("//").substringBefore('/').lowercase()
        val path = clean.substringAfter(host).ifEmpty { "/" }.lowercase()
        return host.startsWith(hostPrefix.lowercase()) &&
            path.startsWith(pathPrefix) &&
            sourceType.isNotBlank()
    }
}

/** 允许的来源类型集合：仅酷狗/酷我/QQ/网易云（GC #9）。 */
val ALLOWED_SOURCE_TYPES: Set<String> = setOf("kw", "kugou", "kg", "tx", "qq", "wy")

/** 临时允许列表：预置主机 + 路径 + 精确来源类型；不含 Bilibili/GitCode（GC #9）。 */
val DEFAULT_SOURCE_ALLOW_RULES: List<SourceAllowRule> = listOf(
    SourceAllowRule("provider.example", "/kw", "kw"),
    SourceAllowRule("provider.example", "/kugou", "kugou"),
    SourceAllowRule("provider.example", "/tx", "tx"),
    SourceAllowRule("provider.example", "/qq", "qq"),
    SourceAllowRule("provider.example", "/wy", "wy")
)

/**
 * 来源过滤（spec §9）：已撤销/停用项与 Bilibili/GitCode 等非允许来源永远不放行，
 * 稳定 ID 只代表「清单背书」，不能绕过来源类型与状态检查；显示名不参与决策。
 */
fun allowedManifestItems(
    items: List<ManifestItem>,
    rules: List<SourceAllowRule>
): List<ManifestItem> = items.filter { item ->
    when {
        item.status == PluginReleaseStatus.REVOKED ||
            item.status == PluginReleaseStatus.DISABLED -> false
        item.category != PluginCategory.MUSIC -> false
        else -> sourceTypeOf(item, rules) in ALLOWED_SOURCE_TYPES
    }
}

/** 从稳定 ID 或下载 URL 提取来源类型；无法命中返回 null（不可产生持久数据）。 */
fun sourceTypeOf(item: ManifestItem, rules: List<SourceAllowRule>): String? {
    if (item.id.isNotBlank()) {
        val last = item.id.substringAfterLast('.')
        return last.takeIf { it in ALLOWED_SOURCE_TYPES }
    }
    return rules.firstOrNull { it.matches(item.url) }?.sourceType
}

/** 清单无稳定 id 时，按 GC #5 版本化精确映射生成；无法命中返回 null。 */
fun inferStablePluginId(item: ManifestItem): String? = when {
    item.id.isNotBlank() -> item.id
    item.url.contains("/kw") -> "linglan.kw"
    item.url.contains("/kugou") || item.url.contains("/kg") -> "linglan.kg"
    item.url.contains("/tx") -> "linglan.tx"
    item.url.contains("/wy") -> "linglan.wy"
    else -> null
}

package com.ncm.app.plugin.compat

/**
 * JianyunMusicFreeCompat/1 契约常量（spec §6.2 冻结）。
 * 页码从 1 开始；缺失列表按空列表处理；必需字段缺失拒绝该条结果。
 */
const val MUSICFREE_PROTOCOL_VERSION = 1

val SUPPORTED_SEARCH_TYPES: Set<String> = setOf("music", "album", "artist", "sheet")

fun missingRequiredFieldMessage(field: String): String = "搜索结果缺少必需字段: $field"

/** 返回缺失的必需字段列表；空表即合法。 */
fun validateSearchResultShape(name: String?, id: Any?): List<String> = buildList {
    if (name.isNullOrBlank()) add("name")
    if (id == null) add("id")
}

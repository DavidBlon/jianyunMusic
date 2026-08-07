package com.ncm.app.data.model

/**
 * 播放来源标识（P6T5，spec §13）：保留历史平台字符串以兼容存量缓存/快照数据，
 * 新增可扩展的中性来源（[LOCAL]/[OFFICIAL]/[LEGACY_NETEASE]/[PLUGIN_PREFIX]）。
 */
object PlaybackSource {
    // 历史来源（存量数据兼容；产品层不再产生）
    const val NETEASE = "netease"
    const val LINGLAN = "linglan-wy"
    const val LINGLAN_CACHE = "linglan-cache"
    const val KUGOU = "kugou"

    // 中性可扩展来源
    const val LOCAL = "local"
    const val OFFICIAL = "jianyun-official"
    const val LEGACY_NETEASE = "legacy-netease"
    const val PLUGIN_PREFIX = "plugin:"

    fun isLinglan(source: String): Boolean {
        return source == LINGLAN || source == LINGLAN_CACHE
    }

    fun isPlugin(source: String): Boolean = source.startsWith(PLUGIN_PREFIX)

    fun pluginIdOf(source: String): String? =
        source.removePrefix(PLUGIN_PREFIX).takeIf { it.isNotBlank() }
}

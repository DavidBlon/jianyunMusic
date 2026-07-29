package com.ncm.app.data.model

/**
 * Stable source identifiers stored with Media3 queue items.
 */
object PlaybackSource {
    const val NETEASE = "netease"
    const val LINGLAN = "linglan-wy"
    const val LINGLAN_CACHE = "linglan-cache"
    const val KUGOU = "kugou"

    fun isLinglan(source: String): Boolean {
        return source == LINGLAN || source == LINGLAN_CACHE
    }
}

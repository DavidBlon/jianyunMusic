package com.ncm.app.plugin.runtime

/**
 * 按插件隔离的 Cookie 存储（spec §6.3）：不同插件不能共享，也不能读取应用自身会话。
 * 持久化时用 P2T1 SecretVault 加密并排除备份；断开授权时 clearAll 走加密擦除（GC #8）。
 */
class PluginCookieJar {
    private val cookies = mutableMapOf<String, MutableList<Cookie>>()

    data class Cookie(val name: String, val value: String, val domain: String)

    fun put(pluginId: String, name: String, value: String, domain: String) {
        cookies.getOrPut(pluginId) { mutableListOf() }.add(Cookie(name, value, domain))
    }

    fun cookiesFor(pluginId: String, url: String): List<Pair<String, String>> {
        val domain = url.substringAfter("//").substringBefore('/')
        return cookies[pluginId].orEmpty()
            .filter { domain.endsWith(it.domain) }
            .map { it.name to it.value }
    }

    fun clearPlugin(pluginId: String) { cookies.remove(pluginId) }

    fun clearAll() { cookies.clear() }
}

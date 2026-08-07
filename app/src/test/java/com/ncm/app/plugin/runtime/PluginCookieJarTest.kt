package com.ncm.app.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginCookieJarTest {

    @Test
    fun cookiesAreIsolatedPerPlugin() {
        val jar = PluginCookieJar()
        jar.put("linglan.kw", "session", "kw-secret", domain = "k.api")
        jar.put("linglan.tx", "session", "tx-secret", domain = "t.api")
        // 插件 A 的请求只带自己的 Cookie，拿不到 B 的
        assertEquals(listOf("session" to "kw-secret"), jar.cookiesFor("linglan.kw", "https://k.api/x"))
        assertEquals(listOf("session" to "tx-secret"), jar.cookiesFor("linglan.tx", "https://t.api/x"))
    }

    @Test
    fun clearPluginRemovesOnlyThatPlugin() {
        val jar = PluginCookieJar()
        jar.put("linglan.kw", "a", "1", domain = "k.api")
        jar.put("linglan.tx", "b", "2", domain = "t.api")
        jar.clearPlugin("linglan.kw")
        assertNull(jar.cookiesFor("linglan.kw", "https://k.api/x").firstOrNull())
        assertEquals(listOf("b" to "2"), jar.cookiesFor("linglan.tx", "https://t.api/x"))
    }

    @Test
    fun clearAllWipesEveryPlugin() {
        val jar = PluginCookieJar()
        jar.put("linglan.kw", "a", "1", domain = "k.api")
        jar.put("linglan.tx", "b", "2", domain = "t.api")
        jar.clearAll()
        assertEquals(emptyList<Pair<String, String>>(), jar.cookiesFor("linglan.kw", "https://k.api/x"))
        assertEquals(emptyList<Pair<String, String>>(), jar.cookiesFor("linglan.tx", "https://t.api/x"))
    }
}

package com.ncm.app.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PluginScriptCacheTest {

    private fun tmpDir(): File = Files.createTempDirectory("plugin-cache-test").toFile()

    @Test
    fun cacheKeyIsIdentityDigestPlusPluginPlusVersion() {
        val cache = PluginScriptCache(tmpDir(), identityDigest = "user-hash-abc")
        val key = cache.cacheKeyFor("linglan.kw", "1.0.0")
        assertEquals("user-hash-abc", key.substringBefore("_"))
        assertEquals("linglan.kw_1.0.0", key.substringAfter("_"))
    }

    @Test
    fun activateKeepsPreviousVersionUntilSuccess() {
        val cache = PluginScriptCache(tmpDir(), identityDigest = "u")
        cache.stageCandidate("kw", "1.0.0", "script-a")
        val active = cache.activateCandidate("kw", "1.0.0")
        assertNotNull(active)
        assertEquals("script-a", cache.loadActive("kw")?.script)
        // 候选未激活前不覆盖当前版
        cache.stageCandidate("kw", "1.0.1", "script-b")
        assertEquals("script-a", cache.loadActive("kw")?.script)
        // 激活成功后原子切换
        cache.activateCandidate("kw", "1.0.1")
        assertEquals("script-b", cache.loadActive("kw")?.script)
    }

    @Test
    fun cacheKeyNeverContainsRawSecret() {
        // 键 = 授权身份的不可逆摘要 + pluginId + version；原始密钥绝不进入路径/文件名（GC #10）
        val cache = PluginScriptCache(tmpDir(), identityDigest = "user-hash-abc")
        assertNull(cache.cacheKeyFor("kw", "1.0.0").takeIf { it.contains("linglan-secret") })
    }

    @Test
    fun deleteAllRemovesEveryVersionOfPlugin() {
        val cache = PluginScriptCache(tmpDir(), identityDigest = "u")
        cache.stageCandidate("kw", "1.0.0", "script-a")
        cache.activateCandidate("kw", "1.0.0")
        cache.stageCandidate("kw", "1.0.1", "script-b")
        cache.deleteAll("kw")
        assertNull(cache.loadActive("kw"))
    }
}

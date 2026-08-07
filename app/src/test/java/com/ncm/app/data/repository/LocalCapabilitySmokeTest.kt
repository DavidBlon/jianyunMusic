package com.ncm.app.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本地能力冒烟测试（P6T2，GC #13）：不连接聆澜、无任何在线账号时，
 * 本地搜索与简云官方内容必须可用（网易云 API/登录/Cookie 已从产品移除）。
 */
class LocalCapabilitySmokeTest {

    @Test
    fun localSearchWorksWithoutPluginOrAccount() = runBlocking {
        val repo = MusicRepository(pluginSearchService = null)
        val result = repo.search("简云")
        assertTrue("本地搜索应成功", result.isSuccess)
        assertTrue("应能返回简云官方内容", result.getOrThrow().songs.isNotEmpty())
    }

    @Test
    fun similarSongsAreEmptyWithoutNeteaseDependency() = runBlocking {
        val repo = MusicRepository(pluginSearchService = null)
        val result = repo.getSimilarSongsResult(123456L)
        assertTrue(result.isSuccess)
        assertTrue(result.getOrThrow().isEmpty())
    }
}

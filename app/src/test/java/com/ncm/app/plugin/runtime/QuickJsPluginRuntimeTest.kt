package com.ncm.app.plugin.runtime

import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * QuickJsPluginRuntime 端到端契约（hello.cjs：search/getMediaSource/getLyric）。
 * 引擎执行用例统一 @Ignore：QuickJS 原生库仅 Android 可用，JVM 单测无法加载
 * （见 QuickJsRuntimeTest 说明），需真机/模拟器 QA 执行。
 */
@Ignore("QuickJS 原生库仅 Android 可用：JVM 单测无法加载，需真机 QA（spec P0T2 决策记录）")
class QuickJsPluginRuntimeTest {

    private fun helloScript(): String =
        File("src/test/resources/fakeplugins/hello.cjs").readText()

    @Test
    fun helloPluginSearchReturnsNormalizedTrack() {
        val runtime = QuickJsPluginRuntime(pluginId = "fake-hello", script = helloScript())
        val provider = runtime.providerFor("fake-hello")
        val outcome = kotlinx.coroutines.runBlocking { provider!!.search("测试", page = 1, type = "music") }
        org.junit.Assert.assertTrue(outcome.items.isNotEmpty())
        org.junit.Assert.assertEquals("测试 示例", outcome.items.first().title)
        runtime.destroy()
    }

    @Test
    fun helloPluginResolvesMediaWithHeadersAndQuality() {
        val runtime = QuickJsPluginRuntime(pluginId = "fake-hello", script = helloScript())
        val provider = runtime.providerFor("fake-hello")
        val outcome = kotlinx.coroutines.runBlocking { provider!!.search("测试", 1, "music") }
        val media = kotlinx.coroutines.runBlocking { provider!!.resolveMedia(outcome.items.first(), "320k") }
        org.junit.Assert.assertEquals("https://media.example/hello-1.mp3", media.url)
        org.junit.Assert.assertEquals("320k", media.quality)
        runtime.destroy()
    }
}

package com.ncm.app.plugin.runtime

import org.junit.Ignore
import org.junit.Test
import java.io.File

/**
 * 假插件契约矩阵（spec §15.1）：正常 / 缺字段 / 错误类型 / 超大响应 / 超时 / 异常抛出。
 *
 * 契约语义（与计划文档注记一致）：
 * - throws.cjs / timeout.cjs 在契约探针（GC #11 第二步）阶段即被拒绝装载：
 *   探针环境禁用真实网络，search 抛错或永不返回 = 契约探针不通过，这正是期望行为。
 * - huge-response.cjs 的 2000 条结果在归一化层被截断到单页上限。
 *
 * 引擎执行用例统一 @Ignore：QuickJS 原生库仅 Android 可用，JVM 单测无法加载
 * （见 QuickJsRuntimeTest 说明），需真机/模拟器 QA 执行。
 */
@Ignore("QuickJS 原生库仅 Android 可用：JVM 单测无法加载，需真机 QA（spec P0T2 决策记录）")
class FakePluginMatrixTest {

    private fun load(name: String): String = File("src/test/resources/fakeplugins/$name").readText()

    @Test
    fun helloPluginSearchReturnsNormalizedTrack() {
        val runtime = QuickJsPluginRuntime("fake-hello", load("hello.cjs"))
        val outcome = kotlinx.coroutines.runBlocking {
            runtime.providerFor("fake-hello")!!.search("测试", 1, "music")
        }
        org.junit.Assert.assertTrue(outcome.items.isNotEmpty())
        org.junit.Assert.assertEquals("测试 示例", outcome.items.first().title)
        runtime.destroy()
    }

    @Test
    fun missingRequiredFieldRejectsThatResultItem() {
        val runtime = QuickJsPluginRuntime("fake-missing", load("missing-field.cjs"))
        val outcome = kotlinx.coroutines.runBlocking {
            runtime.providerFor("fake-missing")!!.search("x", 1, "music")
        }
        org.junit.Assert.assertTrue(outcome.items.isEmpty())
        runtime.destroy()
    }

    @Test
    fun throwingPluginIsRejectedAtContractProbe() {
        // search 抛错 → 契约探针不通过 → 不允许装载（GC #11）
        val result = try {
            QuickJsPluginRuntime("fake-throws", load("throws.cjs"))
            null
        } catch (e: com.ncm.app.plugin.provider.PluginException) {
            e
        }
        org.junit.Assert.assertNotNull(result)
        org.junit.Assert.assertEquals("PROBE_FAILED", result?.code)
    }

    @Test
    fun hugeResponseIsBounded() {
        val runtime = QuickJsPluginRuntime("fake-huge", load("huge-response.cjs"))
        val outcome = kotlinx.coroutines.runBlocking {
            runtime.providerFor("fake-huge")!!.search("x", 1, "music")
        }
        org.junit.Assert.assertTrue(outcome.items.size <= MAX_RESULTS_PER_PAGE)
        runtime.destroy()
    }

    @Test
    fun hangingPluginTimesOutWithRetryableError() {
        // search 永不返回 → 装载探针超时 → 可重试错误，不允许激活
        val result = try {
            QuickJsPluginRuntime("fake-timeout", load("timeout.cjs"))
            null
        } catch (e: com.ncm.app.plugin.provider.PluginException) {
            e
        }
        org.junit.Assert.assertNotNull(result)
        org.junit.Assert.assertEquals(true, result?.retryable)
    }
}

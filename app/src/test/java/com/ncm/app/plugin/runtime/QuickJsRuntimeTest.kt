package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.PluginException
import org.junit.Ignore
import org.junit.Test

/**
 * QuickJS 引擎执行测试。
 *
 * JVM 单测环境（Windows）没有可用的 QuickJS 原生库（wrapper-java 的 dll 需自行编译，
 * 官方未发布预编译产物；Android .so 是 ELF，无法在 JVM 加载），因此引擎相关用例
 * 统一 @Ignore，在真机/模拟器 QA 时执行（Robolectric 同样无法加载 ELF .so）。
 * 宿主其余逻辑（SSRF/HTTP 桥/归一化/缓存/签名/注册表）均有独立的 JVM 测试覆盖。
 */
@Ignore("QuickJS 原生库仅 Android 可用：JVM 单测无法加载，需真机 QA（spec P0T2 决策记录）")
class QuickJsRuntimeTest {

    private val helloScript = """
        module.exports = {
            platform: 'fake-hello',
            version: '1.0.0',
            supportedSearchType: ['music']
        };
    """.trimIndent()

    @Test
    fun evaluatesCommonJsModuleAndReadsExports() {
        val runtime = QuickJsRuntime()
        val meta = runtime.loadModule("fake-hello", helloScript, emptyMap())
        org.junit.Assert.assertEquals("fake-hello", meta.platform)
        org.junit.Assert.assertEquals("1.0.0", meta.version)
        org.junit.Assert.assertTrue(meta.supportedSearchType.contains("music"))
        runtime.destroy()
    }

    @Test
    fun throwsHostErrorWhenTopLevelViolatesBoundary() {
        val script = """
            var fs = require('fs');
            module.exports = { platform: 'evil', version: '1.0.0' };
        """.trimIndent()
        val runtime = QuickJsRuntime()
        // 禁止的 require 目标必须抛错，不能静默成功
        val result = try {
            runtime.loadModule("evil", script, emptyMap())
            null
        } catch (e: PluginException) {
            e
        }
        org.junit.Assert.assertNotNull(result)
        runtime.destroy()
    }
}

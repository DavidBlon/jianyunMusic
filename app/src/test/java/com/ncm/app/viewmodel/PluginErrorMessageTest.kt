package com.ncm.app.viewmodel

import com.ncm.app.plugin.provider.PluginException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginErrorMessageTest {

    @Test
    fun javascriptCompatibilityFailureDoesNotExposeStackTrace() {
        val result = pluginSearchErrorMessage(
            PluginException(
                "SEARCH_FAILED",
                "UnhandledPromiseRejectionException: not a function\n at searchBase (unknown.js:263)",
                retryable = true
            )
        )

        assertEquals("当前在线来源脚本与应用版本不兼容，请到设置中刷新来源", result)
        assertFalse(result.orEmpty().contains("unknown.js"))
    }

    @Test
    fun timeoutGetsActionableCopy() {
        val result = pluginSearchErrorMessage(
            PluginException("TIMEOUT", "插件调用超时", retryable = true)
        )

        assertEquals("在线来源响应超时，请稍后重试", result)
    }

    @Test
    fun nestedServiceRejectionExplainsTemporarySourceLimit() {
        val result = pluginSearchErrorMessage(
            PluginException(
                "SEARCH_FAILED",
                "host http error: source service rejected request (code 2001)",
                retryable = true
            )
        )

        assertEquals("当前在线来源暂时限制搜索，请稍后再试或更换来源", result)
    }
}

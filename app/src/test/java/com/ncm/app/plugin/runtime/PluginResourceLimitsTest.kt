package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.PluginException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginResourceLimitsTest {

    @Test
    fun callTimeoutConvertsToRetryablePluginError() = runTest {
        val result = try {
            applyCallTimeout(timeoutMs = 50) {
                delay(5_000)
                "late"
            }
            null
        } catch (e: PluginException) {
            e
        }
        assertEquals(true, result?.retryable)
    }

    @Test
    fun withinLimitReturnsValue() = runTest {
        assertEquals("ok", applyCallTimeout(timeoutMs = 1_000) { "ok" })
    }
}

class PluginCircuitBreakerTest {

    @Test
    fun opensAfterThresholdFailures() {
        val breaker = PluginCircuitBreaker(failureThreshold = 2, nowMs = { 0L })
        assertTrue(breaker.allowCall())
        breaker.recordFailure()
        assertTrue(breaker.allowCall())
        breaker.recordFailure()
        assertFalse(breaker.allowCall())
    }

    @Test
    fun halfOpenProbeReopensOnFailure() {
        var now = 0L
        val breaker = PluginCircuitBreaker(failureThreshold = 2, openDurationMs = 60_000L, nowMs = { now })
        breaker.recordFailure()
        breaker.recordFailure()
        assertFalse(breaker.allowCall())

        now = 61_000L   // 冷却结束 → 半开探测放行一次
        assertTrue(breaker.allowCall())
        breaker.recordFailure()   // 探测失败 → 立即重开
        assertFalse(breaker.allowCall())
    }

    @Test
    fun successResetsFailures() {
        val breaker = PluginCircuitBreaker(failureThreshold = 2, nowMs = { 0L })
        breaker.recordFailure()
        breaker.recordSuccess()
        breaker.recordFailure()
        assertTrue(breaker.allowCall())  // 成功复位后未达阈值
    }
}

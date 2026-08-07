package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.provider.PluginException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

data class PluginResourceLimits(
    val executionTimeoutMs: Long = 10_000L,
    val maxResponseBytes: Int = 5 * 1024 * 1024,
    val maxRedirects: Int = 5
)

/** 调用超时 → 可重试的宿主错误（spec §7.3）。 */
suspend fun <T> applyCallTimeout(timeoutMs: Long, block: suspend () -> T): T = try {
    withTimeout(timeoutMs) { block() }
} catch (e: TimeoutCancellationException) {
    throw PluginException(code = "TIMEOUT", message = "插件调用超时", retryable = true)
}

/**
 * 插件连续崩溃熔断（spec §7.3）：连续失败达到阈值进入 OPEN，冷却后半开探测，
 * 探测失败立即重开；成功则复位。独立实现，不复用将被删除的 ProviderCircuitBreaker 代码（GC #2）。
 */
class PluginCircuitBreaker(
    private val failureThreshold: Int = 2,
    private val openDurationMs: Long = 60_000L,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private var consecutiveFailures = 0
    private var openedAtMs: Long? = null

    fun allowCall(): Boolean {
        val opened = openedAtMs
        if (opened == null) return true
        if (nowMs() - opened >= openDurationMs) {
            openedAtMs = null   // 半开探测：放行一次
            return true
        }
        return false
    }

    fun recordSuccess() {
        consecutiveFailures = 0
        openedAtMs = null
    }

    fun recordFailure() {
        consecutiveFailures++
        if (consecutiveFailures >= failureThreshold) {
            openedAtMs = nowMs()
        }
    }

    fun reset() {
        consecutiveFailures = 0
        openedAtMs = null
    }
}

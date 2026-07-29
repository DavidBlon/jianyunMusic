package com.ncm.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal sealed interface ProviderAttempt<out T> {
    data class Success<T>(val value: T) : ProviderAttempt<T>
    data object Miss : ProviderAttempt<Nothing>
    data object Failure : ProviderAttempt<Nothing>
}

/**
 * Prevents an unhealthy backup provider from being called for every playback
 * request. A provider is opened after repeated failures, then receives one
 * half-open probe after the cooldown expires.
 *
 * Only one request may execute at a time. Concurrent callers fail fast so
 * queue prefetching cannot multiply requests to the same third-party service.
 * Business misses keep the circuit closed; only transport, timeout, HTTP, or
 * parsing failures count toward opening it. Fail-fast skips are not recorded.
 */
internal class ProviderCircuitBreaker(
    private val failureThreshold: Int = 2,
    private val openDurationMs: Long = 60_000L,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {

    init {
        require(failureThreshold > 0) { "failureThreshold must be positive" }
        require(openDurationMs > 0) { "openDurationMs must be positive" }
    }

    private val executionGate = Mutex()
    private var consecutiveFailures = 0
    private var openUntilMs: Long? = null

    suspend fun <T> execute(provider: suspend () -> T?): T? = executeAttempt {
        provider()?.let { ProviderAttempt.Success(it) } ?: ProviderAttempt.Failure
    }

    suspend fun <T> executeAttempt(
        provider: suspend () -> ProviderAttempt<T>
    ): T? {
        if (!executionGate.tryLock()) return null

        try {
            val openUntil = openUntilMs
            if (openUntil != null && nowMs() < openUntil) return null

            val isHalfOpenProbe = openUntil != null
            val attempt = try {
                provider()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ProviderAttempt.Failure
            }

            return when (attempt) {
                is ProviderAttempt.Success -> {
                    recordHealthyResponse()
                    attempt.value
                }

                ProviderAttempt.Miss -> {
                    recordHealthyResponse()
                    null
                }

                ProviderAttempt.Failure -> {
                    recordFailure(isHalfOpenProbe)
                    null
                }
            }
        } finally {
            executionGate.unlock()
        }
    }

    private fun recordHealthyResponse() {
        consecutiveFailures = 0
        openUntilMs = null
    }

    private fun recordFailure(isHalfOpenProbe: Boolean) {
        consecutiveFailures += 1
        if (isHalfOpenProbe || consecutiveFailures >= failureThreshold) {
            openUntilMs = nowMs() + openDurationMs
            consecutiveFailures = 0
        }
    }
}

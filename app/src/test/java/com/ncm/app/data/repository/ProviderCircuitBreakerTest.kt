package com.ncm.app.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ProviderCircuitBreakerTest {

    @Test
    fun execute_opensAfterConsecutiveFailuresAndRetriesAfterCooldown() = runBlocking {
        var nowMs = 0L
        var calls = 0
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 2,
            openDurationMs = 1_000,
            nowMs = { nowMs }
        )

        assertNull(breaker.execute<String> { calls++; null })
        assertNull(breaker.execute<String> { calls++; null })
        assertNull(breaker.execute { calls++; "must-not-run" })
        assertEquals(2, calls)

        nowMs = 1_000
        assertEquals("recovered", breaker.execute { calls++; "recovered" })
        assertEquals(3, calls)
    }

    @Test
    fun execute_successResetsConsecutiveFailureCount() = runBlocking {
        var calls = 0
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 2,
            openDurationMs = 1_000,
            nowMs = { 0L }
        )

        assertNull(breaker.execute<String> { calls++; null })
        assertEquals("ok", breaker.execute { calls++; "ok" })
        assertNull(breaker.execute<String> { calls++; null })
        assertEquals("still-closed", breaker.execute { calls++; "still-closed" })
        assertEquals(4, calls)
    }

    @Test
    fun executeAttempt_businessMissKeepsCircuitClosed() = runBlocking {
        var calls = 0
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 1,
            openDurationMs = 1_000,
            nowMs = { 0L }
        )

        assertNull(
            breaker.executeAttempt<String> {
                calls++
                ProviderAttempt.Miss
            }
        )
        assertEquals(
            "healthy",
            breaker.executeAttempt {
                calls++
                ProviderAttempt.Success("healthy")
            }
        )
        assertEquals(2, calls)
    }

    @Test
    fun execute_failedHalfOpenProbeStartsANewCooldown() = runBlocking {
        var nowMs = 0L
        var calls = 0
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 1,
            openDurationMs = 1_000,
            nowMs = { nowMs }
        )

        assertNull(breaker.execute<String> { calls++; null })
        nowMs = 1_000
        assertNull(breaker.execute<String> { calls++; null })
        nowMs = 1_500
        assertNull(breaker.execute { calls++; "must-not-run" })
        assertEquals(2, calls)

        nowMs = 2_000
        assertEquals("recovered", breaker.execute { calls++; "recovered" })
    }

    @Test
    fun execute_allowsOnlyOneInFlightProbe() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 2,
            openDurationMs = 1_000
        )

        val first = async {
            breaker.execute {
                calls++
                entered.complete(Unit)
                release.await()
                "first"
            }
        }
        entered.await()

        assertNull(breaker.execute { calls++; "duplicate" })
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals(1, calls)
    }

    @Test
    fun execute_countsProviderExceptionsAsFailures() = runBlocking {
        var calls = 0
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 1,
            openDurationMs = 1_000,
            nowMs = { 0L }
        )

        assertNull(breaker.execute<String> {
            calls++
            error("provider unavailable")
        })
        assertNull(breaker.execute { calls++; "must-not-run" })
        assertEquals(1, calls)
    }

    @Test
    fun execute_propagatesCancellationWithoutOpeningTheCircuit() = runBlocking {
        val breaker = ProviderCircuitBreaker(
            failureThreshold = 1,
            openDurationMs = 1_000,
            nowMs = { 0L }
        )

        try {
            breaker.execute<String> { throw CancellationException("cancelled") }
            fail("CancellationException should be propagated")
        } catch (_: CancellationException) {
            // Expected: structured cancellation is not a provider failure.
        }

        assertEquals("healthy", breaker.execute { "healthy" })
    }
}

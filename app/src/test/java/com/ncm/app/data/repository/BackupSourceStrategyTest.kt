package com.ncm.app.data.repository

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class BackupSourceStrategyTest {

    @Test
    fun resolve_returnsFastestSuccessfulExactProvider() = runBlocking {
        val result = BackupSourceStrategy.resolve(
            exactProviders = listOf(
                {
                    delay(200)
                    "slow"
                },
                {
                    delay(20)
                    "fast"
                }
            ),
            searchProviders = emptyList()
        )

        assertEquals("fast", result)
    }

    @Test
    fun resolve_doesNotStartSearchWhenExactProviderSucceeds() = runBlocking {
        var searchCalls = 0
        val result = BackupSourceStrategy.resolve(
            exactProviders = listOf(
                {
                    delay(40)
                    "exact"
                }
            ),
            searchProviders = listOf(
                {
                    searchCalls += 1
                    "search"
                }
            )
        )

        assertEquals("exact", result)
        assertEquals(0, searchCalls)
    }

    @Test
    fun resolve_usesSearchAfterEveryExactProviderFails() = runBlocking {
        val result = BackupSourceStrategy.resolve(
            exactProviders = listOf(
                { null },
                {
                    delay(10)
                    null
                }
            ),
            searchProviders = listOf(
                { "search" }
            )
        )

        assertEquals("search", result)
    }

    @Test
    fun resolve_returnsNullWhenEveryProviderFails() = runBlocking {
        val result = BackupSourceStrategy.resolve<String>(
            exactProviders = listOf({ null }),
            searchProviders = listOf({ null })
        )

        assertNull(result)
    }

    @Test
    fun resolve_ignoresOneProviderFailureAndKeepsResolving() = runBlocking {
        val result = BackupSourceStrategy.resolve(
            exactProviders = listOf(
                { error("provider unavailable") },
                { "healthy" }
            ),
            searchProviders = emptyList()
        )

        assertEquals("healthy", result)
    }

    @Test
    fun resolve_returnsNullForEmptyProviderLists() = runBlocking {
        val result = BackupSourceStrategy.resolve<String>(
            exactProviders = emptyList(),
            searchProviders = emptyList()
        )

        assertNull(result)
    }

    @Test
    fun resolve_propagatesCallerCancellation() = runBlocking {
        try {
            withTimeout(50) {
                BackupSourceStrategy.resolve<String>(
                    exactProviders = listOf({ awaitCancellation() }),
                    searchProviders = listOf({ awaitCancellation() })
                )
            }
            fail("TimeoutCancellationException should be propagated")
        } catch (_: TimeoutCancellationException) {
            // Expected: caller cancellation must not be converted into a failed source.
        }
    }
}

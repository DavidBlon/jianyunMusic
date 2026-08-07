package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.security.SsrfGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlledHttpBridgeTest {

    private val guard = SsrfGuard()

    @Test
    fun followsRedirectRevalidatingEachHop() = runTest {
        val responses = mutableMapOf<String, HttpResult>()
        responses["https://a.example/song"] =
            HttpResult(302, mapOf("location" to "https://b.example/song"), byteArrayOf())
        responses["https://b.example/song"] =
            HttpResult(200, emptyMap(), "{\"ok\":true}".toByteArray())
        val bridge = ControlledHttpBridge(
            ssrfGuard = guard,
            executor = { spec -> responses[spec.url] ?: error("unexpected ${spec.url}") }
        )
        val result = bridge.execute(HttpRequestSpec("https://a.example/song", "GET", emptyMap()))
        assertEquals(200, result.status)
        assertEquals("{\"ok\":true}", String(result.data, Charsets.UTF_8))
    }

    @Test
    fun redirectIntoSiteLocalIsBlocked() = runTest {
        val responses = mutableMapOf<String, HttpResult>()
        responses["https://a.example/song"] =
            HttpResult(302, mapOf("location" to "http://127.0.0.1/admin"), byteArrayOf())
        val bridge = ControlledHttpBridge(
            ssrfGuard = guard,
            executor = { spec -> responses[spec.url] ?: error("unexpected ${spec.url}") }
        )
        val outcome = runCatching { bridge.execute(HttpRequestSpec("https://a.example/song", "GET", emptyMap())) }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun responseLargerThanLimitIsRejected() = runTest {
        val responses = mutableMapOf<String, HttpResult>()
        responses["https://a.example/big"] =
            HttpResult(200, emptyMap(), ByteArray(6 * 1024 * 1024))
        val bridge = ControlledHttpBridge(
            ssrfGuard = guard,
            maxResponseBytes = 1024,
            executor = { spec -> responses[spec.url] ?: error("unexpected ${spec.url}") }
        )
        val outcome = runCatching { bridge.execute(HttpRequestSpec("https://a.example/big", "GET", emptyMap())) }
        assertTrue(outcome.isFailure)
    }

    @Test
    fun relativeRedirectIsResolvedAgainstBase() = runTest {
        val responses = mutableMapOf<String, HttpResult>()
        responses["https://a.example/song"] =
            HttpResult(301, mapOf("location" to "/moved"), byteArrayOf())
        responses["https://a.example/moved"] =
            HttpResult(200, emptyMap(), "ok".toByteArray())
        val bridge = ControlledHttpBridge(
            ssrfGuard = guard,
            executor = { spec -> responses[spec.url] ?: error("unexpected ${spec.url}") }
        )
        val result = bridge.execute(HttpRequestSpec("https://a.example/song", "GET", emptyMap()))
        assertEquals(200, result.status)
    }
}

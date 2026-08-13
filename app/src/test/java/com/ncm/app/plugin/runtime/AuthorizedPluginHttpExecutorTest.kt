package com.ncm.app.plugin.runtime

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizedPluginHttpExecutorTest {

    @Test
    fun trustedPlaybackEndpointReceivesHostCredentialInsteadOfScriptValue() = runTest {
        val captured = mutableListOf<HttpRequestSpec>()
        val executor = AuthorizedPluginHttpExecutor(
            authorizedApiBaseUrl = "https://music.example/api/music",
            credentialProvider = { "test-card-key" },
            delegate = { spec ->
                captured += spec
                HttpResult(200, emptyMap(), byteArrayOf())
            }
        )

        executor.execute(
            HttpRequestSpec(
                url = "https://music.example/api/music/url?source=kw&songId=1",
                method = "GET",
                headers = mapOf("x-api-key" to "script-placeholder", "Accept" to "application/json")
            )
        )

        val forwarded = captured.single()
        val keyHeaders = forwarded.headers.filterKeys { it.equals("X-API-Key", ignoreCase = true) }
        assertEquals(mapOf("X-API-Key" to "test-card-key"), keyHeaders)
        assertEquals("application/json", forwarded.headers["Accept"])
    }

    @Test
    fun nonPlaybackOrThirdPartyRequestsNeverReceiveCredential() = runTest {
        val captured = mutableListOf<HttpRequestSpec>()
        val executor = AuthorizedPluginHttpExecutor(
            authorizedApiBaseUrl = "https://music.example/api/music",
            credentialProvider = { "test-card-key" },
            delegate = { spec ->
                captured += spec
                HttpResult(200, emptyMap(), byteArrayOf())
            }
        )

        executor.execute(HttpRequestSpec("https://music.example/api/music/lyric?id=1", "GET", emptyMap()))
        executor.execute(HttpRequestSpec("https://cdn.example/media/song.mp3", "GET", emptyMap()))

        assertEquals(2, captured.size)
        captured.forEach { request ->
            assertFalse(request.headers.keys.any { it.equals("X-API-Key", ignoreCase = true) })
        }
        assertTrue(captured.all { it.headers.isEmpty() })
    }

    @Test
    fun unsafeStoredCredentialIsRejectedBeforeBuildingAnHttpHeader() = runTest {
        var delegated = false
        val executor = AuthorizedPluginHttpExecutor(
            authorizedApiBaseUrl = "https://music.example/api/music",
            credentialProvider = { "这不是密钥，而是一段错误保存的中文说明" },
            delegate = {
                delegated = true
                HttpResult(200, emptyMap(), byteArrayOf())
            }
        )

        val result = runCatching {
            executor.execute(
                HttpRequestSpec(
                    url = "https://music.example/api/music/url?source=kw&songId=1",
                    method = "GET",
                    headers = emptyMap()
                )
            )
        }

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("重新输入") == true)
        assertFalse(delegated)
    }
}

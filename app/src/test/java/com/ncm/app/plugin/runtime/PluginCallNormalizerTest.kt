package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCallNormalizerTest {

    @Test
    fun searchResultMissingRequiredFieldsIsRejected() {
        val raw = listOf(
            mapOf("id" to "1", "name" to "ok"),            // 合法
            mapOf("id" to "2"),                             // 缺 name
            mapOf("name" to "x")                            // 缺 id
        )
        val normalized = normalizeSearchResult(
            raw,
            keyFor = { _, id -> ProviderTrackKey("fake", id.toString()) }
        )
        assertEquals(1, normalized.size)
        assertEquals("ok", normalized.first().title)
    }

    @Test
    fun emptyListIsTreatedAsEndOfResults() {
        assertTrue(normalizeSearchResult(emptyList<Any>(), keyFor = { _, id -> ProviderTrackKey("fake", id.toString()) }).isEmpty())
    }

    @Test
    fun resultListIsBoundedToPageLimit() {
        val raw = (1..500).map { mapOf("id" to it, "name" to "n$it") }
        val normalized = normalizeSearchResult(raw, keyFor = { _, id -> ProviderTrackKey("fake", id.toString()) })
        assertEquals(MAX_RESULTS_PER_PAGE, normalized.size)
    }

    @Test
    fun resolvedMediaRejectsMissingUrlAndBadProtocol() {
        val missing = runCatching { normalizeResolvedMedia(mapOf("url" to "")) }
        assertTrue(missing.isFailure)

        val badProtocol = runCatching { normalizeResolvedMedia(mapOf("url" to "file:///etc/passwd")) }
        assertTrue(badProtocol.isFailure)
    }

    @Test
    fun resolvedMediaPreservesHeadersAndExpiry() {
        val media = normalizeResolvedMedia(
            mapOf(
                "url" to "https://cdn.example/a.mp3",
                "headers" to mapOf("Referer" to "https://example.com"),
                "quality" to "320k",
                "expiresAt" to 1_800_000_000_000L
            )
        )
        assertEquals("https://cdn.example/a.mp3", media.url)
        assertEquals("https://example.com", media.headers["Referer"])
        assertEquals(1_800_000_000_000L, media.expiresAtEpochMs)
    }

    @Test
    fun contractProbeAcceptsValidAndRejectsMissingOrThrowing() {
        val valid = runContractProbe("fake") { mapOf("data" to listOf<Any>(), "isEnd" to true) }
        assertEquals(true, valid.healthy)

        val missing = runContractProbe("fake") { mapOf("data" to null) }
        assertEquals(false, missing.healthy)

        val throwing = runContractProbe("fake") { throw IllegalStateException("touched real network") }
        assertEquals(false, throwing.healthy)
    }
}

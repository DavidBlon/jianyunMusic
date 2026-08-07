package com.ncm.app.data.repository

import com.ncm.app.plugin.PluginSearchService
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.PluginException
import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSearchRoutingTest {

    @Test
    fun searchRoutesToCurrentSelectedSource() = runTest {
        val service = PluginSearchService(
            runtime = InMemoryPluginRuntime(mapOf("linglan.kw" to FakeProvider(onSearch = { _, _, _ -> hitOutcome() }))),
            currentSource = { "linglan.kw" }
        )
        val result = service.search("周杰伦", page = 1, type = "music")
        assertTrue(result.isSuccess)
        assertEquals("命中", result.getOrThrow().items.single().title)
    }

    @Test
    fun searchFailureDoesNotFallBack() = runTest {
        val throwing = FakeProvider(onSearch = { _, _, _ ->
            throw PluginException("REMOTE_ERROR", "upstream 500", retryable = true)
        })
        val service = PluginSearchService(
            runtime = InMemoryPluginRuntime(mapOf("linglan.kw" to throwing)),
            currentSource = { "linglan.kw" }
        )
        val result = service.search("周杰伦", page = 1, type = "music")
        assertTrue(result.isFailure)  // 失败即失败，绝不静默切换来源（GC #6）
    }

    @Test
    fun noSourceSelectedIsFailureNotFallback() = runTest {
        val service = PluginSearchService(
            runtime = InMemoryPluginRuntime(emptyMap()),
            currentSource = { null }
        )
        val result = service.search("周杰伦", page = 1, type = "music")
        assertTrue(result.isFailure)
    }

    private class FakeProvider(
        private val onSearch: suspend (String, Int, String) -> SearchOutcome
    ) : MusicProvider {
        override val pluginId: String get() = "linglan.kw"
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome = onSearch(query, page, type)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia = error("not used")
        override suspend fun lyric(track: OnlineTrack): LyricOutcome = LyricOutcome(null, null, null, null)
    }

    private fun hitOutcome(): SearchOutcome = SearchOutcome(
        items = listOf(
            OnlineTrack(
                key = ProviderTrackKey("linglan.kw", "1"),
                producedByPluginVersion = "1.0.0",
                payloadSchemaVersion = 1,
                title = "命中",
                artists = listOf(OnlineArtist(remoteId = "a1", name = "歌手")),
                album = null,
                durationMs = null,
                artworkUrl = null,
                pluginPayload = BoundedJsonObject.fromMap(emptyMap())
            )
        ),
        isEnd = true
    )
}

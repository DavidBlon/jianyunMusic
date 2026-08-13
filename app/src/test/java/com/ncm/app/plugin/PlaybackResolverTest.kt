package com.ncm.app.plugin

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import com.ncm.app.plugin.security.SsrfGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackResolverTest {

    @Test
    fun resolvesMediaThroughProviderForTrack() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("https://ok.example/a.mp3")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isSuccess)
        assertEquals("https://ok.example/a.mp3", result.getOrThrow().url)
    }

    @Test
    fun failedPlaylistTrackResolvesFromStrictlyMatchingAlternativeSource() = runTest {
        val requested = sampleTrack("source-a").copy(
            title = "同一首歌",
            artists = listOf(OnlineArtist("a", "同一歌手")),
            durationMs = 200_000L
        )
        val alternative = requested.copy(
            key = ProviderTrackKey("source-b", "replacement"),
            durationMs = 204_000L
        )
        val runtime = InMemoryPluginRuntime(
            mapOf(
                "source-a" to SearchableProvider("source-a", resolveError = "地区限制"),
                "source-b" to SearchableProvider(
                    pluginId = "source-b",
                    searchItems = listOf(alternative),
                    mediaUrl = "https://ok.example/mixed-source.mp3"
                )
            )
        )

        val result = PlaybackResolver(runtime, SsrfGuard()).resolveTrack(requested, "128k")

        assertTrue(result.isSuccess)
        assertEquals(alternative.key, result.getOrThrow().track.key)
        assertEquals("https://ok.example/mixed-source.mp3", result.getOrThrow().media.url)
        assertEquals(true, result.getOrThrow().usedFallback)
    }

    @Test
    fun fallbackRejectsSameTitleFromDifferentArtist() = runTest {
        val requested = sampleTrack("source-a").copy(
            title = "同一首歌",
            artists = listOf(OnlineArtist("a", "原唱歌手"))
        )
        val wrongArtist = requested.copy(
            key = ProviderTrackKey("source-b", "cover-version"),
            artists = listOf(OnlineArtist("b", "翻唱歌手"))
        )
        val runtime = InMemoryPluginRuntime(
            mapOf(
                "source-a" to SearchableProvider("source-a", resolveError = "无法播放"),
                "source-b" to SearchableProvider(
                    pluginId = "source-b",
                    searchItems = listOf(wrongArtist),
                    mediaUrl = "https://ok.example/wrong-song.mp3"
                )
            )
        )

        val result = PlaybackResolver(runtime, SsrfGuard()).resolveTrack(requested, "128k")

        assertTrue(result.isFailure)
        assertEquals("无法播放", result.exceptionOrNull()?.message)
    }

    @Test
    fun restoresMissingProviderBeforeResolvingPersistedTrack() = runTest {
        val runtime = InMemoryPluginRuntime(emptyMap())
        var restoredPluginId: String? = null
        val resolver = PlaybackResolver(
            runtime = runtime,
            ssrfGuard = SsrfGuard(),
            restoreProvider = { pluginId ->
                restoredPluginId = pluginId
                FakeResolvingProvider("https://ok.example/restored.mp3")
            }
        )

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isSuccess)
        assertEquals("fake", restoredPluginId)
        assertEquals("https://ok.example/restored.mp3", result.getOrThrow().url)
    }

    @Test
    fun installsMissingSourceOnDemandBeforeResolvingMixedPlaylistTrack() = runTest {
        val runtime = InMemoryPluginRuntime(emptyMap())
        var installedPluginId: String? = null
        val resolver = PlaybackResolver(
            runtime = runtime,
            ssrfGuard = SsrfGuard(),
            installProvider = { pluginId ->
                installedPluginId = pluginId
                FakeResolvingProvider("https://ok.example/on-demand.mp3", pluginId = pluginId)
            }
        )

        val result = resolver.resolve(sampleTrack("linglan.tx"), quality = "128k")

        assertTrue(result.isSuccess)
        assertEquals("linglan.tx", installedPluginId)
        assertEquals("https://ok.example/on-demand.mp3", result.getOrThrow().url)
    }

    @Test
    fun rejectsUrlDeniedBySsrfGuard() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("http://127.0.0.1/internal.mp3")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isFailure)
    }

    @Test
    fun upgradesPublicHttpMediaUrlToHttpsBeforePlayback() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("http://cdn.example/song.mp3?q=1")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isSuccess)
        assertEquals("https://cdn.example/song.mp3?q=1", result.getOrThrow().url)
    }

    @Test
    fun neverUpgradesPrivateHttpMediaIntoAllowedPlayback() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("http://192.168.1.5/song.mp3")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        assertTrue(resolver.resolve(sampleTrack("fake"), quality = "128k").isFailure)
    }

    @Test
    fun rejectsExpiredMediaUrl() = runTest {
        val runtime = InMemoryPluginRuntime(
            mapOf(
                "fake" to FakeResolvingProvider(
                    "https://ok.example/a.mp3",
                    expiresAtEpochMs = 1_000L
                )
            )
        )
        val resolver = PlaybackResolver(runtime, SsrfGuard(), now = { 2_000L })

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isFailure)
    }

    @Test
    fun lyricRoutesThroughTrackProvider() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("https://ok.example/a.mp3")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val result = resolver.lyric(sampleTrack("fake"))

        assertTrue(result.isSuccess)
    }

    private fun sampleTrack(pluginId: String): OnlineTrack = OnlineTrack(
        key = ProviderTrackKey(pluginId, "remote-1"),
        producedByPluginVersion = "1.0.0",
        payloadSchemaVersion = 1,
        title = "测试歌曲",
        artists = listOf(OnlineArtist(remoteId = "a1", name = "测试歌手")),
        album = null,
        durationMs = 200_000L,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )

    private class FakeResolvingProvider(
        private val url: String,
        private val expiresAtEpochMs: Long? = null,
        override val pluginId: String = "fake"
    ) : MusicProvider {
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(emptyList(), isEnd = true)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            ResolvedMedia(
                url = url, headers = emptyMap(), userAgent = null,
                quality = quality, expiresAtEpochMs = expiresAtEpochMs
            )
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(null, null, null, null)
    }

    private class SearchableProvider(
        override val pluginId: String,
        private val searchItems: List<OnlineTrack> = emptyList(),
        private val mediaUrl: String? = null,
        private val resolveError: String? = null
    ) : MusicProvider {
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(searchItems, isEnd = true)

        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia {
            resolveError?.let { throw IllegalStateException(it) }
            return ResolvedMedia(
                url = requireNotNull(mediaUrl),
                headers = emptyMap(),
                userAgent = null,
                quality = quality,
                expiresAtEpochMs = null
            )
        }

        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(null, null, null, null)
    }
}

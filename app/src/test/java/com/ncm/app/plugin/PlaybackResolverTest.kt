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
    fun rejectsUrlDeniedBySsrfGuard() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeResolvingProvider("http://127.0.0.1/internal.mp3")))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val result = resolver.resolve(sampleTrack("fake"), quality = "128k")

        assertTrue(result.isFailure)
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
        private val expiresAtEpochMs: Long? = null
    ) : MusicProvider {
        override val pluginId: String get() = "fake"
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
}

package com.ncm.app.plugin.provider

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicProviderContractTest {

    @Test
    fun providerSurfaceMatchesFrozenContract() = runTest {
        val fake = FakeMusicProvider()
        val outcome = fake.search("test", page = 1, type = "music")
        assertTrue(outcome.items.isNotEmpty())
        assertEquals(false, outcome.isEnd)
    }

    @Test
    fun pluginExceptionCarriesRetryFlag() {
        val e = PluginException(code = "SEARCH_FAILED", message = "网络失败", retryable = true)
        assertEquals("SEARCH_FAILED", e.code)
        assertTrue(e.retryable)
    }

    @Test
    fun optionalCapabilitiesDefaultToUnsupported() = kotlinx.coroutines.test.runTest {
        val fake = FakeMusicProvider()
        assertEquals(false, fake.supportsAlbumInfo())
        assertEquals(false, fake.supportsArtistWorks())
        assertEquals(false, fake.supportsMusicSheet())
        assertEquals(false, fake.supportsTopLists())
        assertTrue(fake.albumInfo(OnlineTrack(key = com.ncm.app.plugin.model.ProviderTrackKey("fake", "1"), producedByPluginVersion = "1", payloadSchemaVersion = 1, title = "t", artists = emptyList(), album = null, durationMs = null, artworkUrl = null, pluginPayload = com.ncm.app.plugin.model.BoundedJsonObject.fromMap(emptyMap())), page = 1).isEmpty())
    }

    private class FakeMusicProvider : MusicProvider {
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(
                items = listOf(
                    OnlineTrack(
                        key = com.ncm.app.plugin.model.ProviderTrackKey("fake", "1"),
                        producedByPluginVersion = "1",
                        payloadSchemaVersion = 1,
                        title = "t",
                        artists = emptyList(),
                        album = null,
                        durationMs = null,
                        artworkUrl = null,
                        pluginPayload = com.ncm.app.plugin.model.BoundedJsonObject.fromMap(emptyMap())
                    )
                ),
                isEnd = false
            )
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            error("not implemented")
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(rawLrc = "[00:00.00]x", translation = null, romaLrc = null, wordLrc = null)
        override val pluginId: String get() = "fake"
    }
}

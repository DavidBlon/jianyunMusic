package com.ncm.app.viewmodel

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.SearchOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackDurationEnricherTest {

    @Test
    fun enrichesOnlyTracksWhoseMetadataIsMissing() = runBlocking {
        val provider = DetailProvider()
        val missing = track("missing", durationMs = null)
        val complete = track("complete", durationMs = 180_000L).copy(
            artworkUrl = "https://img.example/existing.jpg"
        )

        val enriched = enrichMissingTrackDurations(
            tracks = listOf(missing, complete),
            providerFor = { provider }
        )

        assertEquals(229_000L, enriched[0].durationMs)
        assertEquals("https://img.example/wy.jpg", enriched[0].artworkUrl)
        assertEquals(180_000L, enriched[1].durationMs)
        assertEquals("https://img.example/existing.jpg", enriched[1].artworkUrl)
        assertEquals(listOf("missing"), provider.requestedIds)
    }

    private fun track(id: String, durationMs: Long?) = OnlineTrack(
        key = ProviderTrackKey("linglan.tx", id),
        producedByPluginVersion = "1",
        payloadSchemaVersion = 1,
        title = id,
        artists = emptyList(),
        album = null,
        durationMs = durationMs,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )

    private class DetailProvider : MusicProvider {
        val requestedIds = mutableListOf<String>()
        override val pluginId: String = "linglan.tx"
        override fun supportsTrackInfo(): Boolean = true
        override suspend fun trackInfo(track: OnlineTrack): OnlineTrack {
            requestedIds += track.key.remoteId
            return track.copy(
                durationMs = 229_000L,
                artworkUrl = "https://img.example/wy.jpg"
            )
        }
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(emptyList(), isEnd = true)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            error("not used")
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(null, null, null, null)
    }
}

package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginCallNormalizerTest {

    @Test
    fun providerDurationInSecondsIsNormalizedToMilliseconds() {
        val normalized = normalizeSearchResult(
            listOf(mapOf("id" to "song-1", "name" to "Actor", "duration" to 261)),
            keyFor = { _, id -> ProviderTrackKey("kg", id.toString()) }
        )

        assertEquals(261_000L, normalized.single().durationMs)
    }

    @Test
    fun providerDurationAcceptsNumericStrings() {
        val normalized = normalizeSearchResult(
            listOf(mapOf("id" to "song-1", "name" to "Song", "duration" to "169")),
            keyFor = { _, id -> ProviderTrackKey("kw", id.toString()) }
        )

        assertEquals(169_000L, normalized.single().durationMs)
    }

    @Test
    fun providerDurationAlreadyInMillisecondsIsPreserved() {
        val normalized = normalizeSearchResult(
            listOf(mapOf("id" to "song-1", "name" to "Song", "duration" to 200_000)),
            keyFor = { _, id -> ProviderTrackKey("fake", id.toString()) }
        )

        assertEquals(200_000L, normalized.single().durationMs)
    }

    @Test
    fun missingProviderDurationIsDerivedFromQualityMetadata() {
        val normalized = normalizeSearchResult(
            listOf(
                mapOf(
                    "id" to "song-1",
                    "name" to "Song",
                    "qualities" to mapOf(
                        "128k" to mapOf("size" to 2_616_620, "bitrate" to 128_000),
                        "320k" to mapOf("size" to 6_541_257, "bitrate" to 320_000)
                    )
                )
            ),
            keyFor = { _, id -> ProviderTrackKey("tx", id.toString()) }
        )

        assertEquals(163_539L, normalized.single().durationMs)
    }

    @Test
    fun trackInfoAddsQqIntervalWithoutChangingTrackIdentity() {
        val original = normalizeSearchResult(
            listOf(
                mapOf(
                    "id" to "201452010",
                    "songmid" to "003RCA7t0y6du5",
                    "name" to "Animal World",
                    "qualities" to emptyMap<String, Any?>()
                )
            ),
            keyFor = { _, id -> ProviderTrackKey("linglan.tx", id.toString()) }
        ).single()

        val enriched = normalizeTrackInfo(
            original,
            mapOf(
                "id" to 201452010,
                "title" to "Animal World",
                "duration" to 229,
                "qualities" to emptyMap<String, Any?>()
            )
        )

        assertEquals(original.key, enriched?.key)
        assertEquals(229_000L, enriched?.durationMs)
    }

    @Test
    fun neteaseLegacyTrackInfoRestoresArtworkAndMillisecondDuration() {
        val original = normalizeSearchResult(
            listOf(mapOf("id" to "468517654", "title" to "Animal World", "artist" to "Singer")),
            keyFor = { _, id -> ProviderTrackKey("linglan.wy", id.toString()) }
        ).single()
        val response = """
            {
              "songs": [{
                "id": 468517654,
                "name": "Animal World",
                "duration": 228760,
                "album": {"id": 35150381, "name": "Album", "picUrl": "https://img.example/wy.jpg"},
                "artists": [{"id": 5781, "name": "Singer"}]
              }]
            }
        """.trimIndent()

        val enriched = normalizeNeteaseTrackInfoResponse(original, response)

        assertEquals(original.key, enriched?.key)
        assertEquals(228_760L, enriched?.durationMs)
        assertEquals("https://img.example/wy.jpg", enriched?.artworkUrl)
    }

    @Test
    fun neteaseBatchDetailsAreMatchedByRemoteIdAndKeepResultOrder() {
        val originals = listOf("22", "11", "missing").map { id ->
            normalizeSearchResult(
                listOf(mapOf("id" to id, "title" to "Song $id", "artist" to "Singer")),
                keyFor = { _, remoteId -> ProviderTrackKey("linglan.wy", remoteId.toString()) }
            ).single()
        }
        val response = """
            {
              "songs": [
                {"id": 11, "name": "Song 11", "dt": 111000,
                 "al": {"id": 1, "name": "A", "picUrl": "https://img.example/11.jpg"}},
                {"id": 22, "name": "Song 22", "dt": 222000,
                 "al": {"id": 2, "name": "B", "picUrl": "https://img.example/22.jpg"}}
              ]
            }
        """.trimIndent()

        val enriched = normalizeNeteaseTrackInfoResponses(originals, response)

        assertEquals(listOf("22", "11", "missing"), enriched.map { it.key.remoteId })
        assertEquals(222_000L, enriched[0].durationMs)
        assertEquals("https://img.example/22.jpg", enriched[0].artworkUrl)
        assertEquals(111_000L, enriched[1].durationMs)
        assertEquals(null, enriched[2].durationMs)
    }

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
    fun musicFreeStandardFieldsAreAccepted() {
        val normalized = normalizeSearchResult(
            listOf(
                mapOf(
                    "id" to "song-1",
                    "title" to "晴天",
                    "artist" to "周杰伦",
                    "album" to "叶惠美",
                    "albumid" to "album-1",
                    "artwork" to "https://img.example/cover.jpg"
                )
            ),
            keyFor = { _, id -> ProviderTrackKey("tx", id.toString()) }
        )

        assertEquals(1, normalized.size)
        assertEquals("晴天", normalized.single().title)
        assertEquals("周杰伦", normalized.single().artists.single().name)
        assertEquals("叶惠美", normalized.single().album?.name)
        assertEquals("https://img.example/cover.jpg", normalized.single().artworkUrl)
    }

    @Test
    fun resultListIsBoundedToPageLimit() {
        val raw = (1..500).map { mapOf("id" to it, "name" to "n$it") }
        val normalized = normalizeSearchResult(raw, keyFor = { _, id -> ProviderTrackKey("fake", id.toString()) })
        assertEquals(MAX_RESULTS_PER_PAGE, normalized.size)
    }

    @Test
    fun recommendedPlaylistFieldsAreNormalizedAndBounded() {
        val playlists = normalizePlaylistResult(
            listOf(
                mapOf(
                    "id" to 123,
                    "title" to "华语流行精选",
                    "artwork" to "https://img.example/list.jpg",
                    "playCount" to 88_000,
                    "artist" to "测试用户"
                ),
                mapOf("id" to 456)
            ),
            pluginId = "linglan.tx"
        )

        assertEquals(1, playlists.size)
        assertEquals("123", playlists.single().remoteId)
        assertEquals("华语流行精选", playlists.single().title)
        assertEquals(88_000L, playlists.single().playCount)
    }

    @Test
    fun providerListsAcceptDirectAndNestedShapes() {
        val direct = pluginResultItems(
            listOf(mapOf("id" to "1", "title" to "direct")),
            "data",
            "musicList"
        )
        val nested = pluginResultItems(
            mapOf("data" to mapOf("musicList" to listOf(mapOf("id" to "2", "title" to "nested")))),
            "data",
            "musicList"
        )

        assertEquals("1", (direct.single() as Map<*, *>)["id"])
        assertEquals("2", (nested.single() as Map<*, *>)["id"])
    }

    @Test
    fun groupedTopListsBecomePlayablePlaylistFallbacks() {
        val playlists = normalizeTopListResult(
            listOf(
                mapOf(
                    "title" to "热门榜单",
                    "data" to listOf(
                        mapOf(
                            "id" to "rank-1",
                            "title" to "热歌榜",
                            "coverImg" to "https://img.example/rank.jpg"
                        )
                    )
                )
            ),
            pluginId = "linglan.kg"
        )

        assertEquals(1, playlists.size)
        assertEquals("rank-1", playlists.single().remoteId)
        assertEquals("top-list", playlists.single().pluginPayload.toMap()["hostCapability"])
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
    fun resolvedMediaAcceptsAProviderThatReturnsTheUrlDirectly() {
        val media = normalizeResolvedMedia("https://cdn.example/direct.mp3")
        assertEquals("https://cdn.example/direct.mp3", media.url)
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

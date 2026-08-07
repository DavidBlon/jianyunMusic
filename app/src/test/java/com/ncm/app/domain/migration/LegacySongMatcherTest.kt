package com.ncm.app.domain.migration

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacySongMatcherTest {

    private fun track(pluginId: String, remoteId: String, title: String) = OnlineTrack(
        key = ProviderTrackKey(pluginId, remoteId),
        producedByPluginVersion = "1.0.0",
        payloadSchemaVersion = 1,
        title = title,
        artists = emptyList(),
        album = null,
        durationMs = null,
        artworkUrl = null,
        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
    )

    @Test
    fun exactTitleMatchIsSuggestedWithBasis() {
        val legacy = track("legacy-netease", "1", "晴天")
        val plugin = track("linglan.kw", "k1", "晴天")
        val matches = suggestMatches(listOf(legacy), listOf(listOf(plugin)))
        assertEquals(1, matches.size)
        assertEquals("标题完全匹配", matches.first().matchBasis)
    }

    @Test
    fun noMatchIsNotAutoWritten() {
        val legacy = track("legacy-netease", "1", "晴天")
        val plugin = track("linglan.kw", "k1", "稻香")
        val matches = suggestMatches(listOf(legacy), listOf(listOf(plugin)))
        assertTrue(matches.isEmpty())
    }

    @Test
    fun normalizedTitleMatchIsSuggestedSeparately() {
        val legacy = track("legacy-netease", "2", "七里香 ")
        val plugin = track("linglan.tx", "t1", "七里香")
        val matches = suggestMatches(listOf(legacy), listOf(listOf(plugin)))
        assertEquals(1, matches.size)
        assertEquals("标题归一化匹配", matches.first().matchBasis)
    }

    @Test
    fun legacyKeyIsPreservedInMatch() {
        val legacy = track("legacy-netease", "42", "告白气球")
        val plugin = track("linglan.wy", "w1", "告白气球")
        val matches = suggestMatches(listOf(legacy), listOf(listOf(plugin)))
        assertEquals("legacy-netease", matches.first().legacyKey.pluginId)
        assertEquals("42", matches.first().legacyKey.remoteId)
    }
}

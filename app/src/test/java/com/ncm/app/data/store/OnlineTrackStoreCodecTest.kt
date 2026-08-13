package com.ncm.app.data.store

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineTrackStoreCodecTest {

    @Test
    fun oldStoredDurationInSecondsIsRepairedFromPluginPayload() {
        val entity = OnlineSongEntity(
            pluginId = "linglan.kg",
            remoteId = "song-1",
            title = "Actor",
            durationMs = 261,
            pluginPayloadJson = """{"raw":{"id":"song-1","duration":261}}"""
        )

        assertEquals(261_000L, entity.toOnlineTrack(Gson())?.durationMs)
    }

    @Test
    fun missingStoredDurationIsRecoveredFromStringPluginPayload() {
        val entity = OnlineSongEntity(
            pluginId = "linglan.kw",
            remoteId = "song-2",
            title = "Song",
            durationMs = null,
            pluginPayloadJson = """{"raw":{"id":"song-2","duration":"169"}}"""
        )

        assertEquals(169_000L, entity.toOnlineTrack(Gson())?.durationMs)
    }
}

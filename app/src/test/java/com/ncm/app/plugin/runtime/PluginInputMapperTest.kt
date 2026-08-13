package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.compat.MUSICFREE_PROTOCOL_VERSION
import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginInputMapperTest {

    @Test
    fun sourceSpecificPlaybackFieldsAreFlattenedBackIntoPluginInput() {
        val track = OnlineTrack(
            key = ProviderTrackKey("tx", "97773"),
            producedByPluginVersion = "7",
            payloadSchemaVersion = MUSICFREE_PROTOCOL_VERSION,
            title = "晴天",
            artists = listOf(OnlineArtist("4558", "周杰伦")),
            album = null,
            durationMs = null,
            artworkUrl = null,
            pluginPayload = BoundedJsonObject.fromMap(
                mapOf(
                    "raw" to mapOf(
                        "id" to 97773L,
                        "songmid" to "0039MnYb0qxYhV",
                        "qualities" to mapOf("128k" to mapOf("size" to 4317292L))
                    )
                )
            )
        )

        val input = pluginInputFor(track)

        assertEquals("97773", input["id"])
        assertEquals("0039MnYb0qxYhV", input["songmid"])
        assertEquals(true, (input["qualities"] as Map<*, *>).containsKey("128k"))
        assertEquals("晴天", input["title"])
    }
}

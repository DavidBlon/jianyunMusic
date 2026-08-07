package com.ncm.app.plugin.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginModelsTest {

    @Test
    fun compositeKeyRoundTrips() {
        val key = ProviderTrackKey(pluginId = "linglan.kw", remoteId = "a1b2c3")
        assertEquals("linglan.kw#a1b2c3", key.asComposite())
        assertEquals(key, ProviderTrackKey.fromComposite("linglan.kw#a1b2c3"))
    }

    @Test
    fun compositeKeyRejectsMalformedInput() {
        assertNull(ProviderTrackKey.fromComposite("no-separator"))
        assertNull(ProviderTrackKey.fromComposite("only#"))
        assertNull(ProviderTrackKey.fromComposite("#only"))
        assertNull(ProviderTrackKey.fromComposite(""))
    }

    @Test
    fun boundedJsonObjectRejectsDeepAndLargePayloads() {
        val deep = mutableMapOf<String, Any?>()
        var cursor: MutableMap<String, Any?> = deep
        repeat(12) {
            val next = mutableMapOf<String, Any?>()
            cursor["x"] = next
            cursor = next
        }
        val bounded = BoundedJsonObject.fromMap(deep)
        assertEquals(true, bounded.toMap().isEmpty()) // 超层级上限被安全整体拒绝

        val wide = (1..100).associate { it.toString() to it }
        assertEquals(true, BoundedJsonObject.fromMap(wide).toMap().isEmpty()) // 超条目上限同样拒绝
    }

    @Test
    fun boundedJsonObjectRoundTripsNormalPayload() {
        val payload = mapOf(
            "albumId" to 12345L,
            "quality" to "128k",
            "tags" to listOf("a", "b")
        )
        val bounded = BoundedJsonObject.fromMap(payload)
        assertEquals(payload["albumId"], bounded.toMap()["albumId"])
        assertEquals(listOf("a", "b"), bounded.toMap()["tags"])
        assertEquals(true, bounded.sizeBytes() > 0)
    }
}

package com.ncm.app.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickJsAsyncResultDecoderTest {

    @Test
    fun decodesNestedPluginResultWithoutChangingIntegralIds() {
        val decoded = decodeSettledJsonEnvelope(
            """{"value":{"data":[{"id":123,"title":"晴天"}],"isEnd":false}}"""
        ) as Map<*, *>

        val first = (decoded["data"] as List<*>).single() as Map<*, *>
        assertEquals(123L, first["id"])
        assertEquals("晴天", first["title"])
        assertEquals(false, decoded["isEnd"])
    }
}

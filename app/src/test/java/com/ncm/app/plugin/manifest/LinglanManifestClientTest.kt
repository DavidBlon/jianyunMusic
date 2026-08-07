package com.ncm.app.plugin.manifest

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class LinglanManifestClientTest {

    private val sampleJson = """
        {
          "plugins": [
            {"id": "linglan.kw", "name": "酷我", "version": "1.2.3",
             "url": "https://provider.example/kw.js", "category": "music",
             "protocolVersion": 1, "minHostVersion": "1.0.0", "status": "active", "sha256": "abc"},
            {"id": "linglan.wy", "name": "网易云", "version": "1.0.0",
             "url": "https://provider.example/wy.js", "category": "music",
             "protocolVersion": 1, "status": "active"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesManifestIntoDescriptors() = runTest {
        val client = LinglanManifestClient(http = { sampleJson })
        val items = client.fetch()
        assertEquals(2, items.size)
        assertEquals("linglan.kw", items[0].id)
        assertEquals("https://provider.example/kw.js", items[0].url)
        assertEquals("active", items[0].status.name.lowercase())
    }

    @Test
    fun malformedJsonYieldsEmptyListNotCrash() = runTest {
        val client = LinglanManifestClient(http = { "not-json" })
        assertEquals(emptyList<ManifestItem>(), client.fetch())
    }

    @Test
    fun missingStableIdDropsItem() = runTest {
        val client = LinglanManifestClient(http = {
            """{"plugins":[{"name":"无ID","version":"1.0","url":"https://provider.example/kw.js","category":"music"}]}"""
        })
        assertEquals(emptyList<ManifestItem>(), client.fetch())
    }
}

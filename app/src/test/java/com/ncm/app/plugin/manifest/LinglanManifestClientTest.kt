package com.ncm.app.plugin.manifest

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        val client = LinglanManifestClient(http = { _, _ -> sampleJson })
        val items = client.fetch("test-key")
        assertEquals(2, items.size)
        assertEquals("linglan.kw", items[0].id)
        assertEquals("https://provider.example/kw.js", items[0].url)
        assertEquals("active", items[0].status.name.lowercase())
    }

    @Test
    fun malformedJsonYieldsEmptyListNotCrash() = runTest {
        val client = LinglanManifestClient(http = { _, _ -> "not-json" })
        assertEquals(emptyList<ManifestItem>(), client.fetch("test-key"))
    }

    @Test
    fun missingStableIdDropsItem() = runTest {
        val client = LinglanManifestClient(http = { _, _ ->
            """{"plugins":[{"name":"无ID","version":"1.0","url":"https://provider.example/other.js","category":"music"}]}"""
        })
        assertEquals(emptyList<ManifestItem>(), client.fetch("test-key"))
    }

    @Test
    fun realLinglanManifestStructureParsesWithUrlInferredIds() = runTest {
        // 聆澜 mf.json 真实结构（已探测）：无 id/category 字段，脚本 URL 带密钥查询参数
        val realJson = """
            {"plugins":[
              {"name":"酷狗音乐","url":"https://source.shiqianjiang.cn/script/mf/kg.js?key=SECRET.json","version":"7"},
              {"name":"酷我音乐","url":"https://source.shiqianjiang.cn/script/mf/kw.js?key=SECRET.json","version":"7"},
              {"name":"QQ音乐","url":"https://source.shiqianjiang.cn/script/mf/tx.js?key=SECRET.json","version":"7"},
              {"name":"网易云音乐","url":"https://source.shiqianjiang.cn/script/mf/wy.js?key=SECRET.json","version":"7"},
              {"name":"bilibili","url":"https://source.shiqianjiang.cn/script/mf/bilibili.js?key=SECRET.json","version":"4.0.0"},
              {"name":"GitCode","url":"https://source.shiqianjiang.cn/script/mf/git.js?key=SECRET.json","version":"4.0.0"}
            ]}
        """.trimIndent()
        val client = LinglanManifestClient(http = { _, _ -> realJson })
        val items = client.fetch("test-key")
        assertEquals(4, items.size)
        assertEquals("linglan.kg", items.first { it.name == "酷狗音乐" }.id)
        assertEquals("linglan.kw", items.first { it.name == "酷我音乐" }.id)
        assertEquals("linglan.tx", items.first { it.name == "QQ音乐" }.id)
        assertEquals("linglan.wy", items.first { it.name == "网易云音乐" }.id)
        assertTrue(items.none { it.name == "bilibili" || it.name == "GitCode" })
    }

    @Test
    fun endpointTemplateNeverContainsSecretInUrl() = runTest {
        var requestedUrl: String? = null
        var requestedSecret: String? = null
        val client = LinglanManifestClient(
            endpointTemplate = "https://example.test/mf.json",
            http = { url, secret -> requestedUrl = url; requestedSecret = secret; "{\"plugins\":[]}" }
        )
        client.fetch("CERU_KEY-abc")
        assertEquals("https://example.test/mf.json", requestedUrl)
        assertEquals("CERU_KEY-abc", requestedSecret)
    }

    @Test
    fun requestUrlHasNoCredentialQueryParameter() {
        val client = LinglanManifestClient(
            endpointTemplate = "https://example.test/mf.json",
            http = { _, _ -> "{\"plugins\":[]}" }
        )
        assertEquals(
            "https://example.test/mf.json",
            client.requestUrl()
        )
    }
}

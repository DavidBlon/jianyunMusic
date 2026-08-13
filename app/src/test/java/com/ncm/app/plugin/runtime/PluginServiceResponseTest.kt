package com.ncm.app.plugin.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginServiceResponseTest {

    @Test
    fun detectsNestedRejectionHiddenBehindHttpSuccess() {
        val body = """{"code":0,"req_1":{"code":2001,"data":{"body":{"song":{"list":[]}}}}}"""

        assertEquals(2001, nestedServiceRejectionCode(body))
    }

    @Test
    fun acceptsSuccessfulOrUnrelatedPayloads() {
        assertNull(nestedServiceRejectionCode("""{"code":0,"req_1":{"code":0}}"""))
        assertNull(nestedServiceRejectionCode("""{"code":200,"url":"https://media.example/a.mp3"}"""))
    }

    @Test
    fun buildsAConstrainedLegacyFallbackForQqSongSearch() {
        val body = """
            {"req_1":{"method":"DoSearchForQQMusicDesktop","module":"music.search.SearchCgiService","param":{"num_per_page":20,"page_num":2,"query":"周杰伦","search_type":0}}}
        """.trimIndent()

        val request = qqLegacySearchFallbackRequest(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body,
            mapOf("Cookie" to "uin=")
        )

        assertEquals("GET", request?.method)
        assertTrue(request?.url.orEmpty().startsWith("https://c.y.qq.com/soso/fcgi-bin/client_search_cp?"))
        assertTrue(request?.url.orEmpty().contains("p=2"))
        assertEquals("uin=0", request?.headers?.get("Cookie"))
    }

    @Test
    fun convertsLegacySongsToTheShapeExpectedByThePlugin() {
        val legacy = """{"code":0,"data":{"song":{"totalnum":10,"list":[{"songid":97773,"songmid":"0039MnYb0qxYhV","songname":"晴天"}]}}}"""

        val converted = com.google.gson.JsonParser.parseString(
            qqLegacyResponseAsMusicu(legacy)
        ).asJsonObject
        val data = converted["req_1"].asJsonObject["data"].asJsonObject

        assertEquals(10, data["meta"].asJsonObject["sum"].asInt)
        assertEquals(
            "晴天",
            data["body"].asJsonObject["song"].asJsonObject["list"].asJsonArray[0]
                .asJsonObject["songname"].asString
        )
    }

    @Test
    fun upgradesQqLegacyPlaylistDetailRequestToCurrentApi() {
        val request = qqPlaylistDetailFallbackRequest(
            "http://i.y.qq.com/qzone/fcg-bin/fcg_ucc_getcdinfo_byids_cp.fcg?type=1&disstid=7707261125&loginUin=0",
            mapOf("Cookie" to "uin=")
        )

        assertEquals("POST", request?.method)
        assertEquals("https://u.y.qq.com/cgi-bin/musicu.fcg", request?.url)
        val body = request?.body?.toString(Charsets.UTF_8).orEmpty()
        assertTrue(body.contains("uniform_get_Dissinfo"))
        assertTrue(body.contains("7707261125"))
    }

    @Test
    fun convertsCurrentQqPlaylistDetailToLegacyPluginShape() {
        val current = """{"code":0,"req":{"code":0,"data":{"code":0,"songlist":[{"id":1,"mid":"song-mid","name":"示例歌曲"}]}}}"""

        val legacy = com.google.gson.JsonParser.parseString(
            qqPlaylistDetailResponseAsLegacy(current)
        ).asJsonObject

        assertEquals("示例歌曲", legacy["cdlist"].asJsonArray[0].asJsonObject
            ["songlist"].asJsonArray[0].asJsonObject["name"].asString)
    }
}

package com.ncm.app.plugin.manifest

import com.ncm.app.plugin.model.PluginCategory
import com.ncm.app.plugin.model.PluginReleaseStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSourceFilterTest {

    private fun item(id: String, url: String, name: String = id) = ManifestItem(
        id = id, name = name, version = "1.0.0", url = url,
        category = PluginCategory.MUSIC, protocolVersion = 1,
        minHostVersion = null, status = PluginReleaseStatus.ACTIVE, sha256 = null
    )

    @Test
    fun bilibiliAndGitCodeAreAlwaysExcluded() {
        val items = listOf(
            item("bili", "https://provider.example/bili.js", name = "哔哩哔哩"),
            item("gitcode", "https://provider.example/git.js", name = "GitCode")
        )
        // 即使清单给了 category==music，也绝不能因显示名放行；规则不含这两个来源
        val allowed = allowedManifestItems(items, DEFAULT_SOURCE_ALLOW_RULES)
        assertTrue(allowed.none { it.id == "bili" || it.id == "gitcode" })
    }

    @Test
    fun stableIdCannotBypassDownloadUrlAllowlist() {
        val items = listOf(
            item("linglan.kw", "https://evil.example/kw.js", name = "酷我")
        )
        assertTrue(allowedManifestItems(items, DEFAULT_SOURCE_ALLOW_RULES).isEmpty())
    }

    @Test
    fun allowRuleMatchesHostPathAndType() {
        val rule = SourceAllowRule(hostPrefix = "provider.example", pathPrefix = "/kw", sourceType = "kw")
        assertTrue(rule.matches("https://provider.example/kw/script.js?token=secret"))
    }

    @Test
    fun realManifestUrlsWithApiPrefixAndKeyQueryAreAllowed() {
        // 线上 mf.json 实际返回：/api/script/mf/{kg,kw,tx,wy}.js?key=...&revision=...
        val items = listOf(
            item("", "https://source.shiqianjiang.cn/api/script/mf/kg.js?key=SECRET&revision=abc", name = "酷狗音乐"),
            item("", "https://source.shiqianjiang.cn/api/script/mf/kw.js?key=SECRET&revision=def", name = "酷我音乐"),
            item("", "https://source.shiqianjiang.cn/api/script/mf/tx.js?key=SECRET&revision=ghi", name = "QQ音乐"),
            item("", "https://source.shiqianjiang.cn/api/script/mf/wy.js?key=SECRET&revision=jkl", name = "网易云音乐")
        )
        val allowed = allowedManifestItems(items, DEFAULT_SOURCE_ALLOW_RULES)
        assertEquals(4, allowed.size)
        assertEquals(
            listOf("kg", "kw", "tx", "wy"),
            allowed.map { sourceTypeOf(it, DEFAULT_SOURCE_ALLOW_RULES) }
        )
    }

    @Test
    fun inferStableIdMapsKnownProviders() {
        val kw = item("", "https://provider.example/kw/v1.js", name = "酷我")
        assertEquals("linglan.kw", inferStablePluginId(kw))
    }

    @Test
    fun unknownSourceHasNoStableId() {
        val weird = item("", "https://provider.example/other/script.js", name = "未知来源")
        assertNull(inferStablePluginId(weird))
    }

    @Test
    fun revokedOrNonMusicItemsAreNeverAllowed() {
        val revoked = item("linglan.kw", "https://provider.example/kw/v1.js").copy(status = PluginReleaseStatus.REVOKED)
        val nonMusic = item("linglan.wy", "https://provider.example/wy/v1.js", name = "视频").copy(category = PluginCategory.OTHER)
        assertTrue(allowedManifestItems(listOf(revoked, nonMusic), DEFAULT_SOURCE_ALLOW_RULES).isEmpty())
    }
}

package com.ncm.app.plugin

import com.ncm.app.plugin.model.BoundedJsonObject
import com.ncm.app.plugin.model.OnlineArtist
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ProviderTrackKey
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.SearchOutcome
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import com.ncm.app.plugin.security.SsrfGuard
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 首条完整链路：搜索 → 播放地址解析 → SSRF 校验 → 歌词。
 * JVM 版用内存假提供者验证宿主链路（引擎无关）；QuickJS 端到端版见 [e2eWithQuickJsFakePlugin]，
 * 需真机执行（QuickJS 原生库仅 Android 可用）。
 */
class E2ePluginChainTest {

    @Test
    fun searchResolveLyricChainWorksEndToEnd() = runTest {
        val provider = FakeHelloProvider()
        val runtime = InMemoryPluginRuntime(mapOf("fake-hello" to provider))
        val resolver = PlaybackResolver(runtime, SsrfGuard())

        val outcome = provider.search("测试", page = 1, type = "music")
        val track = outcome.items.first()
        assertEquals("测试 示例", track.title)

        val media = resolver.resolve(track, quality = "128k").getOrThrow()
        assertTrue(media.url.startsWith("https://"))
        assertEquals(emptyMap<String, String>(), media.headers)

        val lrc = resolver.lyric(track).getOrThrow()
        assertEquals("[00:00.00]测试 示例", lrc.rawLrc)
    }

    @org.junit.Ignore("QuickJS 原生库仅 Android 可用：需真机 QA（spec P0T2 决策记录）")
    @Test
    fun e2eWithQuickJsFakePlugin() = runTest {
        val script = File("src/test/resources/fakeplugins/hello.cjs").readText()
        val runtime = com.ncm.app.plugin.runtime.QuickJsPluginRuntime("fake-hello", script)
        val resolver = PlaybackResolver(runtime, SsrfGuard())
        val provider = runtime.providerFor("fake-hello")!!

        val outcome = provider.search("测试", page = 1, type = "music")
        val track = outcome.items.first()
        assertEquals("测试 示例", track.title)

        val media = resolver.resolve(track, quality = "standard").getOrThrow()
        assertTrue(media.url.startsWith("https://"))

        val lrc = resolver.lyric(track).getOrThrow()
        assertEquals("[00:00.00]测试 示例", lrc.rawLrc)
        runtime.destroy()
    }

    private class FakeHelloProvider : MusicProvider {
        override val pluginId: String get() = "fake-hello"
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(
                items = listOf(
                    OnlineTrack(
                        key = ProviderTrackKey("fake-hello", "hello-1"),
                        producedByPluginVersion = "1.0.0",
                        payloadSchemaVersion = 1,
                        title = "测试 示例",
                        artists = listOf(OnlineArtist("a1", "测试歌手")),
                        album = null,
                        durationMs = 200_000L,
                        artworkUrl = null,
                        pluginPayload = BoundedJsonObject.fromMap(emptyMap())
                    )
                ),
                isEnd = true
            )
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            ResolvedMedia("https://media.example/${track.key.remoteId}.mp3", emptyMap(), null, quality, null)
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(rawLrc = "[00:00.00]测试 示例", translation = null, romaLrc = null, wordLrc = null)
    }
}

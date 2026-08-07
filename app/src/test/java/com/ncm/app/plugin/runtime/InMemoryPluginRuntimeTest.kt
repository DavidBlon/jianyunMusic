package com.ncm.app.plugin.runtime

import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.plugin.model.ResolvedMedia
import com.ncm.app.plugin.provider.LyricOutcome
import com.ncm.app.plugin.provider.MusicProvider
import com.ncm.app.plugin.provider.SearchOutcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InMemoryPluginRuntimeTest {

    @Test
    fun returnsProviderByPluginId() = runTest {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeProvider()))
        assertEquals("fake", runtime.providerFor("fake")?.pluginId)
        assertNull(runtime.providerFor("missing"))
    }

    @Test
    fun destroyClearsProviders() {
        val runtime = InMemoryPluginRuntime(mapOf("fake" to FakeProvider()))
        runtime.destroy()
        assertNull(runtime.providerFor("fake"))
        assertEquals(false, runtime.isHealthy()) // 销毁后运行环境不可用
    }

    private class FakeProvider : MusicProvider {
        override val pluginId: String get() = "fake"
        override suspend fun search(query: String, page: Int, type: String): SearchOutcome =
            SearchOutcome(emptyList(), isEnd = true)
        override suspend fun resolveMedia(track: OnlineTrack, quality: String?): ResolvedMedia =
            error("not implemented")
        override suspend fun lyric(track: OnlineTrack): LyricOutcome =
            LyricOutcome(null, null, null, null)
    }
}

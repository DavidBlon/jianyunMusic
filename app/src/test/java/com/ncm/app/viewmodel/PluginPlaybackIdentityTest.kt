package com.ncm.app.viewmodel

import com.ncm.app.plugin.model.ProviderTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPlaybackIdentityTest {

    @Test
    fun pluginSongIdIsStableNegativeAndTrackSpecific() {
        val first = ProviderTrackKey("linglan.tx", "song-1")
        val second = ProviderTrackKey("linglan.tx", "song-2")

        assertEquals(pluginShellSongId(first), pluginShellSongId(first))
        assertTrue(pluginShellSongId(first) < 0L)
        assertNotEquals(pluginShellSongId(first), pluginShellSongId(second))
    }

    @Test
    fun pluginPlaybackSourceIsExplicit() {
        assertTrue(isPluginPlaybackSource("plugin:linglan.tx"))
        assertTrue(!isPluginPlaybackSource("netease"))
    }
}

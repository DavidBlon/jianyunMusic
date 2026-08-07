package com.ncm.app.data.store

import com.ncm.app.plugin.auth.LinglanAuthState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MusicSourceSettingsTest {

    private lateinit var settings: MusicSourceSettings

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("test_music_source", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        settings = MusicSourceSettings(context, prefsName = "test_music_source")
    }

    @Test
    fun writeThenReadRoundTrips() {
        settings.write(
            OnlineSourcePrefs(
                authState = LinglanAuthState.ACTIVE.name,
                selectedPluginId = "linglan.kw",
                lastManifestVersion = 3,
                lastVerifiedAtEpochMs = 1_000_000L
            )
        )
        val restored = settings.read()
        assertEquals(LinglanAuthState.ACTIVE.name, restored.authState)
        assertEquals("linglan.kw", restored.selectedPluginId)
        assertEquals(3, restored.lastManifestVersion)
        assertEquals(1_000_000L, restored.lastVerifiedAtEpochMs)
        assertEquals("linglan.kw", settings.currentPluginId)
    }

    @Test
    fun clearResetsToDefaults() {
        settings.write(OnlineSourcePrefs("ACTIVE", "linglan.kw", 3, 1_000_000L))
        settings.clear()
        val restored = settings.read()
        assertEquals(LinglanAuthState.DISCONNECTED.name, restored.authState)
        assertNull(restored.selectedPluginId)
        assertEquals(0, restored.lastManifestVersion)
        assertNull(restored.lastVerifiedAtEpochMs)
        assertNull(settings.currentPluginId)
    }
}

package com.ncm.app.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlinePlaylistIdentityTest {
    @Test
    fun playlistUiIdIsStableNegativeAndSourceAware() {
        val id = onlinePlaylistUiId("linglan.tx", "123")
        assertEquals(id, onlinePlaylistUiId("linglan.tx", "123"))
        assertTrue(id < 0L)
        assertNotEquals(id, onlinePlaylistUiId("linglan.kg", "123"))
    }
}

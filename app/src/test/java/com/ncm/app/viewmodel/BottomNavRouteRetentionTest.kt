package com.ncm.app

import com.ncm.app.ui.navigation.Routes
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomNavRouteRetentionTest {

    @Test
    fun `player route keeps underlying tab selected`() {
        assertEquals(
            Routes.SEARCH,
            retainBottomNavRoute(Routes.SEARCH, Routes.PLAYER)
        )
    }

    @Test
    fun `secondary page keeps underlying tab selected`() {
        assertEquals(
            Routes.MY,
            retainBottomNavRoute(Routes.MY, Routes.PLAYLIST_DETAIL)
        )
    }

    @Test
    fun `top level route updates selected tab`() {
        assertEquals(
            Routes.MY,
            retainBottomNavRoute(Routes.DISCOVER, Routes.MY)
        )
    }
}

package com.ncm.app.ui.navigation

import androidx.compose.runtime.Composable

sealed class Screen(val route: String) {
    data object Discover : Screen("discover")
    data object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun createRoute(playlistId: Long) = "playlist/$playlistId"
    }
    data object Player : Screen("player/{songId}") {
        fun createRoute(songId: Long) = "player/$songId"
    }
    data object Search : Screen("search")
    data object My : Screen("my")
    data object Login : Screen("login")
}

enum class BottomNavItem(
    val route: String,
    val label: String,
    val icon: @Composable () -> Unit
) {
    DISCOVER(
        route = Screen.Discover.route,
        label = "发现",
        icon = { /* icon from resource */ }
    ),
    SEARCH(
        route = Screen.Search.route,
        label = "搜索",
        icon = {}
    ),
    MY(
        route = Screen.My.route,
        label = "我的",
        icon = {}
    )
}

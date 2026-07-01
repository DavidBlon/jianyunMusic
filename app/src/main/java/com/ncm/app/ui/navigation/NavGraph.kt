package com.ncm.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ncm.app.ui.screens.discover.DiscoverScreen
import com.ncm.app.ui.screens.login.LoginScreen
import com.ncm.app.ui.screens.my.MyScreen
import com.ncm.app.ui.screens.player.PlayerScreen
import com.ncm.app.ui.screens.playlist.PlaylistDetailScreen
import com.ncm.app.ui.screens.quick.QuickListScreen
import com.ncm.app.ui.screens.search.SearchScreen
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayerViewModel

object Routes {
    const val DISCOVER = "discover"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val PLAYER = "player/{songId}"
    const val SEARCH = "search"
    const val MY = "my"
    const val LOGIN = "login"
    const val QUICK_LIST = "quick/{type}"

    fun playlistDetail(id: Long) = "playlist/$id"
    fun player(songId: Long) = "player/$songId"
    fun quick(type: String) = "quick/$type"
}

@Composable
fun NavGraph(
    navController: NavHostController,
    isLoggedIn: Boolean,
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val startDestination = if (isLoggedIn) Routes.DISCOVER else Routes.LOGIN

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { fadeIn(animationSpec = tween(250)) },
        exitTransition = { fadeOut(animationSpec = tween(250)) },
        popEnterTransition = { fadeIn(animationSpec = tween(250)) },
        popExitTransition = { fadeOut(animationSpec = tween(250)) }
    ) {
        composable(Routes.DISCOVER) {
            DiscoverScreen(
                onPlaylistClick = { id -> navController.navigate(Routes.playlistDetail(id)) },
                onSongClick = { id -> navController.navigate(Routes.player(id)) },
                onSearchClick = { navController.navigate(Routes.SEARCH) },
                onQuickClick = { type -> navController.navigate(Routes.quick(type)) },
                viewModel = mainViewModel
            )
        }

        composable(
            route = Routes.QUICK_LIST,
            arguments = listOf(navArgument("type") { type = NavType.StringType })
        ) { backStackEntry ->
            val type = backStackEntry.arguments?.getString("type") ?: "playlist"
            QuickListScreen(
                type = type,
                onBack = { navController.popBackStack() },
                onPlaylistClick = { id -> navController.navigate(Routes.playlistDetail(id)) },
                onSongClick = { id -> navController.navigate(Routes.player(id)) },
                viewModel = mainViewModel
            )
        }

        composable(
            route = Routes.PLAYLIST_DETAIL,
            arguments = listOf(navArgument("playlistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
            PlaylistDetailScreen(
                playlistId = playlistId,
                onSongClick = { id ->
                    mainViewModel.playlistState.value.songs.let { songs ->
                        val startIndex = songs.indexOfFirst { it.id == id }.coerceAtLeast(0)
                        playerViewModel.setQueue(songs, startIndex)
                    }
                    navController.navigate(Routes.player(id))
                },
                onBack = { navController.popBackStack() },
                viewModel = mainViewModel
            )
        }

        composable(
            route = Routes.PLAYER,
            arguments = listOf(navArgument("songId") { type = NavType.LongType }),
            enterTransition = { fadeIn(animationSpec = tween(250)) },
            exitTransition = { fadeOut(animationSpec = tween(250)) },
            popEnterTransition = { null },
            popExitTransition = { fadeOut(animationSpec = tween(250)) }
        ) { backStackEntry ->
            val songId = backStackEntry.arguments?.getLong("songId") ?: return@composable
            PlayerScreen(
                songId = songId,
                onBack = { navController.popBackStack() },
                mainViewModel = mainViewModel,
                viewModel = playerViewModel
            )
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onSongClick = { id ->
                    mainViewModel.searchState.value.results.let { songs ->
                        val startIndex = songs.indexOfFirst { it.id == id }.coerceAtLeast(0)
                        playerViewModel.setQueue(songs, startIndex)
                    }
                    navController.navigate(Routes.player(id))
                },
                viewModel = mainViewModel
            )
        }

        composable(Routes.MY) {
            MyScreen(
                onPlaylistClick = { id -> navController.navigate(Routes.playlistDetail(id)) },
                onSongClick = { id -> navController.navigate(Routes.player(id)) },
                onLogout = {
                    mainViewModel.logout()
                },
                viewModel = mainViewModel
            )
        }

        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.DISCOVER) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                viewModel = mainViewModel
            )
        }
    }
}

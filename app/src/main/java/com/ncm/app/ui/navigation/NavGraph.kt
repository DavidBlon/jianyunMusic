package com.ncm.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.ncm.app.BuildConfig
import com.ncm.app.data.store.MusicSourceSettings
import com.ncm.app.plugin.credential.KeystoreSecretVault
import com.ncm.app.plugin.credential.LinglanCredentialStore
import com.ncm.app.plugin.manifest.LinglanAuthClient
import com.ncm.app.plugin.runtime.InMemoryPluginRuntime
import com.ncm.app.ui.screens.discover.DiscoverScreen
import com.ncm.app.ui.screens.artist.ArtistDetailScreen
import com.ncm.app.ui.screens.login.LoginScreen
import com.ncm.app.ui.screens.legal.DisclaimerScreen
import com.ncm.app.ui.screens.my.MyScreen
import com.ncm.app.ui.screens.playlist.PlaylistDetailScreen
import com.ncm.app.ui.screens.quick.QuickListScreen
import com.ncm.app.ui.screens.search.SearchScreen
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.OnlineMusicSourceViewModel
import com.ncm.app.viewmodel.PlayerViewModel
import com.ncm.app.viewmodel.sampleManifest

object Routes {
    const val DISCOVER = "discover"
    const val PLAYLIST_DETAIL = "playlist/{playlistId}"
    const val PLAYER = "player/{songId}"
    const val SEARCH = "search"
    const val MY = "my"
    const val LOGIN = "login"
    const val QUICK_LIST = "quick/{type}"
    const val ARTIST_DETAIL = "artist/{artistId}"
    const val DISCLAIMER = "disclaimer"

    fun playlistDetail(id: Long) = "playlist/$id"
    fun player(songId: Long) = "player/$songId"
    fun quick(type: String) = "quick/$type"
    fun artistDetail(id: Long) = "artist/$id"
}

/** 聆澜授权密钥的 Keystore 别名；对应文件 vault_linglan_auth.dat（已在备份排除规则中）。 */
private const val LINGLAN_AUTH_ALIAS = "linglan_auth"

@Composable
fun NavGraph(
    navController: NavHostController,
    isLoggedIn: Boolean,
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    onOpenPlayer: (Long) -> Unit,
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
                onSongClick = onOpenPlayer,
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
                onSongClick = onOpenPlayer,
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
                    onOpenPlayer(id)
                },
                onBack = { navController.popBackStack() },
                viewModel = mainViewModel
            )
        }

        composable(
            route = Routes.ARTIST_DETAIL,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val artistId = backStackEntry.arguments?.getLong("artistId") ?: return@composable
            ArtistDetailScreen(
                artistId = artistId,
                onBack = { navController.popBackStack() },
                onSongClick = { songId ->
                    mainViewModel.artistDetailState.value.artist?.hotSongs.orEmpty().let { songs ->
                        val startIndex = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
                        playerViewModel.setQueue(songs, startIndex)
                    }
                    onOpenPlayer(songId)
                },
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
            // The player is rendered as a top-level overlay by MainApp so the
            // Scaffold and bottom navigation remain mounted and unchanged.
            backStackEntry.arguments?.getLong("songId") ?: return@composable
        }

        composable(Routes.SEARCH) {
            SearchScreen(
                onSongClick = { id ->
                    mainViewModel.searchState.value.results.let { songs ->
                        val startIndex = songs.indexOfFirst { it.id == id }.coerceAtLeast(0)
                        playerViewModel.setQueue(songs, startIndex)
                    }
                    onOpenPlayer(id)
                },
                onArtistClick = { artistId ->
                    navController.navigate(Routes.artistDetail(artistId))
                },
                onPlayNext = { song -> playerViewModel.enqueueNext(song) },
                viewModel = mainViewModel
            )
        }

        composable(Routes.MY) {
            val context = LocalContext.current
            val okHttpClient = remember { okhttp3.OkHttpClient() }
            val onlineSourceViewModel: OnlineMusicSourceViewModel = viewModel {
                OnlineMusicSourceViewModel(
                    // 阶段 2 用假清单；阶段 3 由 LinglanManifestClient 替换
                    manifestProvider = { sampleManifest() },
                    runtime = InMemoryPluginRuntime(emptyMap()),
                    authClient = LinglanAuthClient(
                        endpoint = BuildConfig.PAID_MUSIC_API_URL
                            .removeSuffix("/music")
                            .takeIf { it.startsWith("https://") }
                            ?.let { "$it/script?checkUpdate=jiany-music-android" }
                            ?: LinglanAuthClient.DEFAULT_ENDPOINT,
                        http = { url, secret ->
                            // 密钥经请求头传递，不进查询参数（GC #4 / spec §8.3）
                            val request = okhttp3.Request.Builder()
                                .url(url)
                                .header("X-API-Key", secret)
                                .header("User-Agent", "JianYunMusic/${BuildConfig.VERSION_NAME}")
                                .build()
                            okHttpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                        }
                    ),
                    credentialStore = LinglanCredentialStore(
                        KeystoreSecretVault(context.applicationContext, LINGLAN_AUTH_ALIAS)
                    ),
                    settings = MusicSourceSettings(context.applicationContext)
                )
            }
            MyScreen(
                onPlaylistClick = { id -> navController.navigate(Routes.playlistDetail(id)) },
                onSongClick = onOpenPlayer,
                onLogout = {
                    mainViewModel.logout()
                },
                onDisclaimerClick = {
                    navController.navigate(Routes.DISCLAIMER)
                },
                playerViewModel = playerViewModel,
                viewModel = mainViewModel,
                onlineSourceViewModel = onlineSourceViewModel
            )
        }

        composable(Routes.DISCLAIMER) {
            DisclaimerScreen(onBack = { navController.popBackStack() })
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

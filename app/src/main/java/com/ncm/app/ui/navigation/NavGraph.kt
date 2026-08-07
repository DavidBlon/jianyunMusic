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
import com.ncm.app.NeteaseApp
import com.ncm.app.data.store.MusicSourceSettings
import com.ncm.app.plugin.credential.KeystoreSecretVault
import com.ncm.app.plugin.credential.LinglanCredentialStore
import com.ncm.app.plugin.manifest.LinglanAuthClient
import com.ncm.app.plugin.manifest.LinglanManifestClient
import com.ncm.app.ui.screens.discover.DiscoverScreen
import com.ncm.app.ui.screens.artist.ArtistDetailScreen
import com.ncm.app.ui.screens.legal.DisclaimerScreen
import com.ncm.app.ui.screens.my.MyScreen
import com.ncm.app.ui.screens.playlist.PlaylistDetailScreen
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
    const val ARTIST_DETAIL = "artist/{artistId}"
    const val DISCLAIMER = "disclaimer"

    fun playlistDetail(id: Long) = "playlist/$id"
    fun player(songId: Long) = "player/$songId"
    fun artistDetail(id: Long) = "artist/$id"
}

/** 聆澜授权密钥的 Keystore 别名；对应文件 vault_linglan_auth.dat（已在备份排除规则中）。 */
private const val LINGLAN_AUTH_ALIAS = "linglan_auth"

@Composable
fun NavGraph(
    navController: NavHostController,
    mainViewModel: MainViewModel,
    playerViewModel: PlayerViewModel,
    onOpenPlayer: (Long) -> Unit,
    onOpenPluginTrack: (com.ncm.app.plugin.model.OnlineTrack) -> Unit,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.DISCOVER,
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
                onPluginSongClick = { track ->
                    onOpenPluginTrack(track)
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
                    // 阶段 2 占位清单提供者；manifestClient 注入后 connect 成功即走真实清单
                    manifestProvider = { sampleManifest() },
                    runtime = NeteaseApp.instance.pluginRuntime,
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
                    settings = MusicSourceSettings(context.applicationContext),
                    registry = NeteaseApp.instance.pluginRegistry,
                    manifestClient = LinglanManifestClient(
                        endpointTemplate = BuildConfig.PAID_MUSIC_API_URL
                            .removeSuffix("/music")
                            .takeIf { it.startsWith("https://") }
                            ?.let { "$it/script/mf.json?key=" }
                            ?: LinglanManifestClient.DEFAULT_ENDPOINT_TEMPLATE,
                        http = { url ->
                            // 清单 URL 含密钥查询参数（聆澜现状，spec §8.3 过渡）：仅内存使用，
                            // 不持久化；响应体不落日志
                            val request = okhttp3.Request.Builder()
                                .url(url)
                                .header("User-Agent", "JianYunMusic/${BuildConfig.VERSION_NAME}")
                                .build()
                            okHttpClient.newCall(request).execute().use { it.body?.string() ?: "" }
                        }
                    )
                )
            }
            MyScreen(
                onPlaylistClick = { id -> navController.navigate(Routes.playlistDetail(id)) },
                onSongClick = onOpenPlayer,
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
    }
}

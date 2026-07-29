package com.ncm.app

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.content.ContextCompat
import com.ncm.app.data.update.ApkUpdateInstaller
import com.ncm.app.data.update.GitHubUpdateChecker
import com.ncm.app.data.update.UpdateInfo
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.ncm.app.ui.navigation.NavGraph
import com.ncm.app.ui.navigation.Routes
import com.ncm.app.ui.components.FirstUseMusicSourcePrompt
import com.ncm.app.ui.screens.player.PlayerScreen
import com.ncm.app.ui.theme.*
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private const val MINI_PLAYER_FADE_MILLIS = 250
private const val PLAYER_OVERLAY_EXIT_MILLIS = 220
private const val SPLASH_HOLD_MILLIS = 1_600L
private const val SPLASH_FADE_MILLIS = 500

internal fun retainBottomNavRoute(
    selectedRoute: String,
    currentRoute: String?
): String = when (currentRoute) {
    Routes.DISCOVER, Routes.SEARCH, Routes.MY -> currentRoute
    else -> selectedRoute
}

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        requestNotificationPermissionIfNeeded()
        setContent {
            val accentTheme by NeteaseApp.instance.accentThemeSettings.theme.collectAsState()
            NeteaseMusicTheme(
                accent = accentTheme.color,
                secondaryAccent = accentTheme.secondary,
                highlightAccent = accentTheme.highlight
            ) {
                MainApp()
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
fun MainApp() {
    val navController = rememberNavController()
    val mainViewModel: MainViewModel = viewModel()
    val playerViewModel: PlayerViewModel = viewModel()
    val playerState by playerViewModel.state.collectAsState()
    val appState by mainViewModel.appState.collectAsState()
    val appearanceSettings = NeteaseApp.instance.playerAppearanceSettings
    val musicSourceSettings = NeteaseApp.instance.musicSourceSettings
    val customBackground by appearanceSettings.customBackground.collectAsState()
    val applyCustomBackgroundGlobally by appearanceSettings.applyCustomBackgroundGlobally.collectAsState()
    val musicSourceKey by musicSourceSettings.cardKey.collectAsState()
    val firstUsePromptCompleted by musicSourceSettings.firstUsePromptCompleted.collectAsState()
    val useGlobalCustomBackground = applyCustomBackgroundGlobally && customBackground != null

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val routedPlayerSongId = navBackStackEntry
        ?.takeIf { currentRoute == Routes.PLAYER }
        ?.arguments
        ?.getLong("songId")
    var previousRoute by remember { mutableStateOf<String?>(null) }
    var miniPlayerBlocked by remember { mutableStateOf(false) }
    var selectedBottomRoute by rememberSaveable { mutableStateOf(Routes.DISCOVER) }
    var playerOverlaySongId by rememberSaveable { mutableStateOf<Long?>(null) }
    var firstUsePromptDismissed by rememberSaveable { mutableStateOf(false) }
    var retainedPlayerSongId by remember { mutableStateOf<Long?>(null) }

    val activePlayerSongId = playerOverlaySongId ?: routedPlayerSongId
    val showBottomBar = appState.isLoggedIn && currentRoute != Routes.LOGIN
    val showMiniPlayer = currentRoute != Routes.LOGIN &&
        activePlayerSongId == null &&
        playerState.currentSong != null &&
        !miniPlayerBlocked

    LaunchedEffect(currentRoute) {
        selectedBottomRoute = retainBottomNavRoute(selectedBottomRoute, currentRoute)
        if (previousRoute == Routes.SEARCH && currentRoute != Routes.SEARCH) {
            mainViewModel.clearSearch()
        }
        previousRoute = currentRoute
    }

    LaunchedEffect(activePlayerSongId) {
        if (activePlayerSongId != null) {
            retainedPlayerSongId = activePlayerSongId
        } else if (miniPlayerBlocked) {
            delay(PLAYER_OVERLAY_EXIT_MILLIS.toLong())
            miniPlayerBlocked = false
        }
    }

    LaunchedEffect(appState.isLoggedIn, currentRoute) {
        if (!appState.isLoggedIn && currentRoute != null && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    val onBottomNavigate: (String) -> Unit = remember(navController) {
        { route ->
            selectedBottomRoute = route
            if (navController.currentDestination?.route != route) {
                navController.navigate(route) {
                    popUpTo(Routes.DISCOVER)
                    launchSingleTop = true
                }
            }
        }
    }
    val onOpenPlayer: (Long) -> Unit = remember {
        { songId -> playerOverlaySongId = songId }
    }
    val onClosePlayer: () -> Unit = remember(navController) {
        {
            miniPlayerBlocked = true
            playerOverlaySongId = null
            if (navController.currentDestination?.route == Routes.PLAYER) {
                navController.popBackStack()
            }
        }
    }
    val onOpenArtist: (Long) -> Unit = remember(navController) {
        { artistId ->
            if (artistId > 0) {
                miniPlayerBlocked = true
                playerOverlaySongId = null
                navController.navigate(Routes.artistDetail(artistId))
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .appBackground(Green500, AccentSecondary, AccentHighlight)
    ) {
        if (useGlobalCustomBackground) {
            customBackground?.let { media ->
                CustomMediaBackground(
                    media = media,
                    initialVideoPositionMs = appearanceSettings.videoResumePosition(media.uri),
                    onVideoPositionSaved = appearanceSettings::saveVideoResumePosition
                )
                CustomBackgroundScrim(global = true)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    alpha = if (useGlobalCustomBackground && activePlayerSongId != null) 0f else 1f
                }
        ) {
            Scaffold(
                containerColor = Color.Transparent,
                contentWindowInsets = WindowInsets(0, 0, 0, 0)
            ) { contentPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                ) {
                    NavGraph(
                        navController = navController,
                        isLoggedIn = appState.isLoggedIn,
                        mainViewModel = mainViewModel,
                        playerViewModel = playerViewModel,
                        onOpenPlayer = onOpenPlayer,
                        modifier = Modifier.fillMaxSize()
                    )

                    AnimatedVisibility(
                        visible = showMiniPlayer,
                        enter = fadeIn(animationSpec = tween(MINI_PLAYER_FADE_MILLIS)),
                        exit = fadeOut(animationSpec = tween(MINI_PLAYER_FADE_MILLIS)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 68.dp)
                    ) {
                        MiniPlayer(
                            songName = playerState.currentSong?.name.orEmpty(),
                            artist = playerState.currentSong?.artistText.orEmpty(),
                            coverUrl = playerState.currentSong?.album?.picUrl,
                            isPlaying = playerState.isPlaying,
                            progress = playerState.progress,
                            onPlayPause = { playerViewModel.togglePlay() },
                            onPrevious = { playerViewModel.playPrev() },
                            onNext = { playerViewModel.playNext() },
                            onClick = { playerState.currentSong?.id?.let(onOpenPlayer) }
                        )
                    }
                }
            }

            if (showBottomBar) {
                BottomNavigationBar(
                    currentRoute = selectedBottomRoute,
                    onNavigate = onBottomNavigate,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        val displayedPlayerSongId = activePlayerSongId ?: retainedPlayerSongId
        AnimatedVisibility(
            visible = activePlayerSongId != null && displayedPlayerSongId != null,
            enter = EnterTransition.None,
            exit = fadeOut(animationSpec = tween(PLAYER_OVERLAY_EXIT_MILLIS)),
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    // Keep empty areas of the player from controlling the
                    // still-mounted screen underneath. Child controls get
                    // first chance to consume their own gestures.
                    detectTapGestures(onTap = {})
                }
        ) {
            displayedPlayerSongId?.let { songId ->
                PlayerScreen(
                    songId = songId,
                    onBack = onClosePlayer,
                    onArtistClick = onOpenArtist,
                    mainViewModel = mainViewModel,
                    viewModel = playerViewModel
                )
                PredictiveBackHandler(enabled = activePlayerSongId != null) { backProgress ->
                    backProgress.collect { }
                    onClosePlayer()
                }
            }
        }

        if (!firstUsePromptCompleted && !firstUsePromptDismissed) {
            FirstUseMusicSourcePrompt(
                currentMaskedKey = musicSourceKey
                    .takeIf { it.isNotBlank() }
                    ?.let { "••••${it.takeLast(4)}" },
                onClose = { doNotShowAgain ->
                    if (doNotShowAgain) {
                        mainViewModel.skipFirstUseMusicSourcePrompt()
                    }
                    firstUsePromptDismissed = true
                },
                onValidateAndSave = mainViewModel::validateAndSaveMusicSourceKey
            )
        }
        if (firstUsePromptCompleted || firstUsePromptDismissed) {
            AppUpdatePrompt()
        }
        OpeningSplash()
    }
}

@Composable
private fun AppUpdatePrompt() {
    val context = LocalContext.current
    val installer = remember(context) { ApkUpdateInstaller(context) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        val installedVersion = context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?: BuildConfig.VERSION_NAME
        update = GitHubUpdateChecker.check(installedVersion)
    }

    DisposableEffect(installer) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
                installer.installIfCurrentDownload(
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                )
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { context.unregisterReceiver(receiver) }
    }

    update?.let { info ->
        AlertDialog(
            onDismissRequest = { update = null },
            containerColor = GlassSurfaceStrong,
            tonalElevation = 0.dp,
            title = { Text("发现新版本 ${info.versionName}", color = TextPrimary) },
            text = {
                Text(
                    "新版本已发布。下载完成后将自动打开系统安装页面。",
                    color = TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (installer.canInstallPackages()) {
                            installer.download(info)
                            update = null
                        } else {
                            installer.requestInstallPermission()
                        }
                    }
                ) {
                    Text(
                        if (installer.canInstallPackages()) "立即更新" else "授权后更新",
                        color = Green500
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { update = null }) {
                    Text("暂不", color = TextSecondary)
                }
            }
        )
    }
}

@Composable
private fun OpeningSplash() {
    var visible by remember { mutableStateOf(true) }
    var started by remember { mutableStateOf(false) }
    val logoAlpha by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 700),
        label = "splashLogoAlpha"
    )
    val logoScale by animateFloatAsState(
        targetValue = if (started) 1f else 0.86f,
        animationSpec = tween(durationMillis = 700),
        label = "splashLogoScale"
    )

    LaunchedEffect(Unit) {
        started = true
        delay(SPLASH_HOLD_MILLIS)
        visible = false
    }

    AnimatedVisibility(
        visible = visible,
        exit = fadeOut(animationSpec = tween(SPLASH_FADE_MILLIS))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .appBackground(Green500, AccentSecondary, AccentHighlight),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                }
            ) {
                Box(
                    modifier = Modifier
                        .size(112.dp)
                        .shadow(
                            elevation = 24.dp,
                            shape = RoundedCornerShape(30.dp),
                            ambientColor = Green500.copy(alpha = 0.22f),
                            spotColor = Green500.copy(alpha = 0.18f)
                        )
                        .clip(RoundedCornerShape(30.dp))
                        .background(DarkBg2),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_full),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = 1.08f
                                scaleY = 1.08f
                            },
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.height(30.dp))
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(1.dp)
                        .background(GreenAccent.copy(alpha = 0.72f))
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "让音乐\n回归音乐本身",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontSize = 22.sp,
                        lineHeight = 34.sp
                    ),
                    color = TextPrimary.copy(alpha = 0.94f),
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .width(76.dp)
                        .height(1.dp)
                        .background(Color.White.copy(alpha = 0.18f))
                )
            }
        }
    }
}

@Composable
private fun BottomNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .glassSurface(RoundedCornerShape(20.dp), elevation = 16.dp)
                    .padding(3.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomNavItem(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(androidx.compose.material.icons.Icons.Filled.Home, null, tint = if (currentRoute == Routes.DISCOVER) Green500 else TextTertiary, modifier = Modifier.size(22.dp))
                    },
                    label = "发现",
                    isActive = currentRoute == Routes.DISCOVER,
                    onClick = { onNavigate(Routes.DISCOVER) }
                )
                BottomNavItem(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Search, null, tint = if (currentRoute == Routes.SEARCH) Green500 else TextTertiary, modifier = Modifier.size(22.dp))
                    },
                    label = "搜索",
                    isActive = currentRoute == Routes.SEARCH,
                    onClick = { onNavigate(Routes.SEARCH) }
                )
                BottomNavItem(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Person, null, tint = if (currentRoute == Routes.MY) Green500 else TextTertiary, modifier = Modifier.size(22.dp))
                    },
                    label = "我的",
                    isActive = currentRoute == Routes.MY,
                    onClick = { onNavigate(Routes.MY) }
                )
            }
        }
    }
}

@Composable
private fun BottomNavItem(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val iconScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = tween(durationMillis = 110),
        label = "bottomTabIconScale"
    )
    val iconOffsetY by animateFloatAsState(
        targetValue = if (isPressed) 2f else 0f,
        animationSpec = tween(durationMillis = 110),
        label = "bottomTabIconOffset"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .then(
                    if (isActive) {
                        Modifier.background(
                            Brush.radialGradient(
                                listOf(
                                    Green500.copy(alpha = 0.24f),
                                    AccentSecondary.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            ),
                            CircleShape
                        )
                    } else {
                        Modifier
                    }
                )
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                    translationY = iconOffsetY
                },
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(1.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) TextPrimary else TextTertiary,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
fun MiniPlayer(
    songName: String,
    artist: String,
    coverUrl: String?,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val visibleProgress = progress.coerceIn(0f, 1f)
    val previousInteractionSource = remember { MutableInteractionSource() }
    val playPauseInteractionSource = remember { MutableInteractionSource() }
    val nextInteractionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp)
            .height(56.dp)
            .glassSurface(
                RoundedCornerShape(14.dp),
                elevation = 14.dp,
                strong = true
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(DarkBg2),
                contentAlignment = Alignment.Center
            ) {
                if (!coverUrl.isNullOrBlank()) {
                    AsyncImage(sizedImageUrl(coverUrl, 120), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(songName, style = MaterialTheme.typography.titleSmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Outlined.SkipPrevious,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(
                            interactionSource = previousInteractionSource,
                            indication = null,
                            onClick = onPrevious
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .accentSurface(CircleShape)
                        .clickable(
                            interactionSource = playPauseInteractionSource,
                            indication = null,
                            onClick = onPlayPause
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isPlaying) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Outlined.SkipNext,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier
                        .size(22.dp)
                        .clickable(
                            interactionSource = nextInteractionSource,
                            indication = null,
                            onClick = onNext
                        )
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(visibleProgress)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(accentBrush())
            )
        }
    }
}

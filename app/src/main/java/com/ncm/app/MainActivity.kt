package com.ncm.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.ncm.app.ui.navigation.NavGraph
import com.ncm.app.ui.navigation.Routes
import com.ncm.app.ui.theme.*
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay

private const val MINI_PLAYER_FADE_MILLIS = 250
private const val SPLASH_HOLD_MILLIS = 1_600L
private const val SPLASH_FADE_MILLIS = 500

class MainActivity : ComponentActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        setContent {
            NeteaseMusicTheme {
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

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var previousRoute by remember { mutableStateOf<String?>(null) }
    var miniPlayerBlocked by remember { mutableStateOf(false) }

    val showBottomBar = appState.isLoggedIn && currentRoute != Routes.LOGIN
    val isLeavingPlayer = previousRoute == Routes.PLAYER && currentRoute != Routes.PLAYER
    val showMiniPlayer = currentRoute != Routes.LOGIN &&
        currentRoute != Routes.PLAYER &&
        playerState.currentSong != null &&
        !isLeavingPlayer &&
        !miniPlayerBlocked

    LaunchedEffect(currentRoute) {
        if (isLeavingPlayer) {
            miniPlayerBlocked = true
            delay(250)
            miniPlayerBlocked = false
        }
        previousRoute = currentRoute
    }

    LaunchedEffect(appState.isLoggedIn, currentRoute) {
        if (!appState.isLoggedIn && currentRoute != null && currentRoute != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = DarkBg,
            bottomBar = {
                if (showBottomBar) {
                    BottomNavigationBar(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            if (route != currentRoute) {
                                navController.navigate(route) {
                                    popUpTo(Routes.DISCOVER)
                                    launchSingleTop = true
                                }
                            }
                        }
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                NavGraph(
                    navController = navController,
                    isLoggedIn = appState.isLoggedIn,
                    mainViewModel = mainViewModel,
                    playerViewModel = playerViewModel,
                    modifier = Modifier.fillMaxSize()
                )

                AnimatedVisibility(
                    visible = showMiniPlayer,
                    enter = fadeIn(animationSpec = tween(MINI_PLAYER_FADE_MILLIS)),
                    exit = fadeOut(animationSpec = tween(MINI_PLAYER_FADE_MILLIS)),
                    modifier = Modifier.align(Alignment.BottomCenter)
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
                        onClick = {
                            playerState.currentSong?.let {
                                if (currentRoute != Routes.player(it.id)) {
                                    navController.navigate(Routes.player(it.id)) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }

        OpeningSplash()
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
                .background(DarkBg),
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
    onNavigate: (String) -> Unit
) {
    Surface(color = Color.Transparent, tonalElevation = 0.dp, shadowElevation = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .background(Color(0xD60A0A0C))
                .drawBehind {
                    drawLine(
                        color = Color.White.copy(alpha = 0.10f),
                        start = androidx.compose.ui.geometry.Offset(0f, 0f),
                        end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                        strokeWidth = 1f
                    )
                }
                .padding(top = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomNavItem(
                icon = {
                    Icon(androidx.compose.material.icons.Icons.Filled.Home, null, tint = if (currentRoute == Routes.DISCOVER) Green500 else TextTertiary, modifier = Modifier.size(22.dp))
                },
                label = "发现",
                isActive = currentRoute == Routes.DISCOVER,
                onClick = { onNavigate(Routes.DISCOVER) }
            )
            BottomNavItem(
                icon = {
                    Icon(androidx.compose.material.icons.Icons.Outlined.Search, null, tint = if (currentRoute == Routes.SEARCH) Green500 else TextTertiary, modifier = Modifier.size(22.dp))
                },
                label = "搜索",
                isActive = currentRoute == Routes.SEARCH,
                onClick = { onNavigate(Routes.SEARCH) }
            )
            BottomNavItem(
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

@Composable
private fun BottomNavItem(
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
        modifier = Modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier.graphicsLayer {
                scaleX = iconScale
                scaleY = iconScale
                translationY = iconOffsetY
            }
        ) {
            icon()
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (isActive) Green500 else TextTertiary)
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurface)
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
                    modifier = Modifier.size(22.dp).clickable(onClick = onPrevious)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Green500)
                        .clickable(onClick = onPlayPause),
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
                    modifier = Modifier.size(22.dp).clickable(onClick = onNext)
                )
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.85f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(visibleProgress)
                    .height(2.dp)
                    .background(Green500)
            )
        }
    }
}

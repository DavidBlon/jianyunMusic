package com.ncm.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ncm.app.ui.theme.*
import com.ncm.app.NeteaseApp
import com.ncm.app.playback.AppPlayer
import com.ncm.app.data.model.PlaybackSource
import com.ncm.app.data.model.ArtistBrief
import com.ncm.app.ui.components.ArtistLinks
import com.ncm.app.ui.components.LinglanSourceBadge
import com.ncm.app.util.albumArtworkUrl
import com.ncm.app.util.albumArtworkDisplayScale
import com.ncm.app.util.albumArtworkThumbnailCacheKey
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayMode
import com.ncm.app.viewmodel.PlaybackQuality
import com.ncm.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

@Composable
fun PlayerScreen(
    songId: Long,
    onBack: () -> Unit,
    onArtistClick: (Long) -> Unit,
    mainViewModel: MainViewModel,
    viewModel: PlayerViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var bottomPanel by remember { mutableStateOf<PlayerBottomPanel?>(null) }
    val appearanceSettings = NeteaseApp.instance.playerAppearanceSettings
    val playerLayout by appearanceSettings.layout.collectAsState()
    val playerBackground by appearanceSettings.background.collectAsState()
    val customBackground by appearanceSettings.customBackground.collectAsState()
    val applyCustomBackgroundGlobally by appearanceSettings.applyCustomBackgroundGlobally.collectAsState()
    val componentVisibility by appearanceSettings.componentVisibility.collectAsState()
    val showLyrics by appearanceSettings.showLyrics.collectAsState()
    val rhythmArtworkEnabled by appearanceSettings.rhythmArtworkEnabled.collectAsState()
    val useGlobalCustomBackground = applyCustomBackgroundGlobally && customBackground != null

    LaunchedEffect(songId) {
        viewModel.open(songId)
    }

    var rotation by remember { mutableFloatStateOf(0f) }

    val shouldRotateDisc = state.isPlaying &&
        componentVisibility.artwork &&
        !showLyrics &&
        playerLayout == PlayerLayout.DISC

    LaunchedEffect(shouldRotateDisc) {
        if (!shouldRotateDisc) return@LaunchedEffect
        var lastFrame = withFrameMillis { it }
        while (true) {
            val frame = withFrameMillis { it }
            val delta = (frame - lastFrame).coerceAtLeast(0L)
            lastFrame = frame
            rotation = (rotation + delta * 360f / 20_000f) % 360f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (useGlobalCustomBackground) {
                    Modifier
                } else {
                    Modifier.background(Brush.verticalGradient(listOf(Color(0xFF111821), DarkBg)))
                }
            )
    ) {
        if (!useGlobalCustomBackground) {
            customBackground?.let { media ->
                CustomMediaBackground(
                    media = media,
                    initialVideoPositionMs = appearanceSettings.videoResumePosition(media.uri),
                    onVideoPositionSaved = appearanceSettings::saveVideoResumePosition
                )
                CustomBackgroundScrim()
            }
        }
        if (customBackground?.type != PlayerCustomMediaType.VIDEO) {
            PlayerAtmosphere(background = playerBackground)
        }
        PlayerTopBar(
            title = state.currentSong?.name ?: "未知歌曲",
            artists = state.currentSong?.artists,
            audioSource = state.audioSource,
            onBack = onBack,
            onArtistClick = onArtistClick,
            isLiked = state.isLiked,
            isLikeUpdating = state.isLikeUpdating,
            onLikeClick = {
                viewModel.toggleLike { song, liked ->
                    mainViewModel.onLikedSongChanged(song, liked)
                }
            },
            onAppearanceClick = { bottomPanel = PlayerBottomPanel.APPEARANCE },
            showSongInfo = componentVisibility.songInfo,
            showFavorite = componentVisibility.favorite,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
        )

        val unavailableMessage = state.error?.takeIf {
            state.songUrl.isNullOrBlank() && !state.isLoading && !state.isPlaying
        }
        val transientError = state.error?.takeUnless { it == unavailableMessage }
        LaunchedEffect(transientError) {
            val message = transientError ?: return@LaunchedEffect
            delay(1_000)
            viewModel.dismissError(message)
        }
        if (unavailableMessage != null || componentVisibility.artwork) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 92.dp, bottom = 196.dp, start = 24.dp, end = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (unavailableMessage != null) {
                    UnavailablePanel(message = unavailableMessage)
                } else if (showLyrics) {
                    LyricsPanel(
                        lyric = state.lyric,
                        tlyric = state.tlyric,
                        currentPosition = state.currentPosition,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) { appearanceSettings.setShowLyrics(false) }
                    )
                } else {
                    val coverModifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { appearanceSettings.setShowLyrics(true) }
                    RhythmArtwork(
                        coverUrl = state.currentSong?.album?.picUrl,
                        layout = playerLayout,
                        rotation = rotation,
                        isPlaying = state.isPlaying,
                        isBuffering = state.isLoading,
                        enabled = rhythmArtworkEnabled,
                        modifier = coverModifier
                    )
                }
            }
        }

        transientError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = RedAccent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 24.dp, end = 24.dp, bottom = 174.dp)
            )
        }

        if (componentVisibility.progress || componentVisibility.transport || componentVisibility.extras) {
            PlayerControls(
                progress = state.progress,
                currentPosition = state.currentPosition,
                duration = state.duration,
                playMode = state.playMode,
                isPlaying = state.isPlaying,
                quality = state.quality,
                qualityMenuExpanded = qualityMenuExpanded,
                showProgress = componentVisibility.progress,
                showTransport = componentVisibility.transport,
                showExtras = componentVisibility.extras,
                onQualityMenuExpandedChange = { qualityMenuExpanded = it },
                onPlayModeClick = { viewModel.togglePlayMode() },
                onPrevClick = { viewModel.playPrev() },
                onPlayPauseClick = { viewModel.togglePlay() },
                onNextClick = { viewModel.playNext() },
                onSeek = { viewModel.setProgress(it) },
                onQualityClick = { viewModel.setQuality(it) },
                onQueueClick = { bottomPanel = PlayerBottomPanel.QUEUE },
                onSleepClick = { bottomPanel = PlayerBottomPanel.SLEEP },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }

    when (bottomPanel) {
        PlayerBottomPanel.QUEUE -> QueueSheet(
            queue = state.queue,
            history = state.history,
            currentSongId = state.currentSong?.id,
            onDismiss = { bottomPanel = null },
            onQueueSongClick = { viewModel.playFromQueue(it.id) },
            onHistorySongClick = viewModel::playFromHistory,
            onRemove = viewModel::removeFromQueue,
            onClear = viewModel::clearQueue
        )
        PlayerBottomPanel.SLEEP -> SleepTimerSheet(
            remainingSeconds = state.sleepRemainingSeconds,
            onDismiss = { bottomPanel = null },
            onSet = viewModel::setSleepTimer
        )
        PlayerBottomPanel.APPEARANCE -> AppearanceSheet(
            layout = playerLayout,
            background = playerBackground,
            componentVisibility = componentVisibility,
            rhythmArtworkEnabled = rhythmArtworkEnabled,
            onDismiss = { bottomPanel = null },
            onLayoutSelected = appearanceSettings::setLayout,
            onBackgroundSelected = appearanceSettings::setBackground,
            onComponentVisibilityChanged = appearanceSettings::setComponentVisible,
            onRhythmArtworkEnabledChange = appearanceSettings::setRhythmArtworkEnabled,
            onApplyImmersivePreset = appearanceSettings::applyImmersivePreset,
            onResetComponents = appearanceSettings::resetComponentVisibility
        )
        null -> Unit
    }
}

private enum class PlayerBottomPanel { QUEUE, SLEEP, APPEARANCE }

@Composable
private fun UnavailablePanel(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "无法播放",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerTopBar(
    title: String,
    artists: List<ArtistBrief>?,
    audioSource: String,
    onBack: () -> Unit,
    onArtistClick: (Long) -> Unit,
    isLiked: Boolean,
    isLikeUpdating: Boolean,
    onLikeClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    showSongInfo: Boolean,
    showFavorite: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = "返回",
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        if (showSongInfo) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 112.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    AudioSourceTag(source = audioSource)
                }
                ArtistLinks(
                    artists = artists,
                    onArtistClick = onArtistClick,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onAppearanceClick) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Outlined.Tune,
                    contentDescription = "播放页个性化",
                    tint = TextPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            if (showFavorite) {
                IconButton(onClick = onLikeClick, enabled = !isLikeUpdating) {
                    Icon(
                        imageVector = if (isLiked) androidx.compose.material.icons.Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Outlined.FavoriteBorder,
                        contentDescription = if (isLiked) "取消收藏" else "收藏",
                        tint = if (isLiked) RedAccent else TextPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AudioSourceTag(source: String) {
    if (PlaybackSource.isLinglan(source)) {
        LinglanSourceBadge(modifier = Modifier.padding(start = 8.dp))
        return
    }

    val label = when (source) {
        PlaybackSource.KUGOU -> "酷狗"
        PlaybackSource.JIANYUN_OFFICIAL -> "简云官方"
        else -> null
    } ?: return

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = Green500,
        maxLines = 1,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Green500.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun RhythmArtwork(
    coverUrl: String?,
    layout: PlayerLayout,
    rotation: Float,
    isPlaying: Boolean,
    isBuffering: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var artworkPulse by remember { mutableFloatStateOf(0f) }
    var glowPulse by remember { mutableFloatStateOf(0f) }
    var flowPhase by remember { mutableFloatStateOf(0f) }
    var burstProgress by remember { mutableFloatStateOf(1f) }
    var burstStrength by remember { mutableFloatStateOf(0f) }
    val defaultAccent = Green500
    var coverAccent by remember(coverUrl) { mutableStateOf(defaultAccent) }

    LaunchedEffect(enabled, isPlaying, isBuffering) {
        var lastFrameNanos = withFrameNanos { it }
        var previousRawEnergy = 0f
        while (enabled && (isPlaying || isBuffering) || artworkPulse > 0.001f || glowPulse > 0.001f) {
            val frameNanos = withFrameNanos { it }
            val deltaSeconds = ((frameNanos - lastFrameNanos) / 1_000_000_000f)
                .coerceIn(0f, 0.05f)
            lastFrameNanos = frameNanos
            val target = when {
                !enabled -> 0f
                isPlaying -> AppPlayer.rhythmEnergy()
                isBuffering -> 0.14f
                else -> 0f
            }
            if (enabled && isPlaying && target > 0.42f && target - previousRawEnergy > 0.10f) {
                burstProgress = 0f
                burstStrength = target
            }
            previousRawEnergy = target
            if (enabled && (isPlaying || isBuffering)) {
                flowPhase = (flowPhase + deltaSeconds * (0.16f + target * 0.16f)) % 1f
            }
            if (burstProgress < 1f) {
                burstProgress = (burstProgress + deltaSeconds / 0.92f).coerceAtMost(1f)
            }
            val artworkResponse = if (target > artworkPulse) 18f else 7f
            val glowResponse = if (target > glowPulse) 9f else 3.8f
            artworkPulse += (target - artworkPulse) *
                (1f - kotlin.math.exp(-artworkResponse * deltaSeconds))
            glowPulse += (target - glowPulse) *
                (1f - kotlin.math.exp(-glowResponse * deltaSeconds))
        }
    }

    val artworkScale = 1f + artworkPulse * 0.038f
    val containerSize = if (layout == PlayerLayout.DISC) 328.dp else 344.dp

    Box(
        modifier = Modifier.size(containerSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            if (!enabled) return@Canvas

            val baseRadius = if (layout == PlayerLayout.DISC) 140.dp.toPx() else 148.dp.toPx()
            val outerRadius = size.minDimension / 2f - 1.dp.toPx()
            val expansion = (outerRadius - baseRadius).coerceAtLeast(1.dp.toPx())

            fun fluidRingPath(radius: Float, wobble: Float, phase: Float): Path {
                val path = Path()
                val pointCount = 72
                val shapeExponent = if (layout == PlayerLayout.DISC) 1f else 0.44f
                repeat(pointCount + 1) { index ->
                    val angle = index.toFloat() / pointCount * (PI * 2.0).toFloat()
                    val ripple = sin(angle * 3f + phase * (PI * 2.0).toFloat()) * wobble +
                        sin(angle * 5f - phase * PI.toFloat()) * wobble * 0.34f
                    val horizontal = cos(angle)
                    val vertical = sin(angle)
                    val x = center.x +
                        horizontal.sign * abs(horizontal).pow(shapeExponent) * (radius + ripple)
                    val y = center.y +
                        vertical.sign * abs(vertical).pow(shapeExponent) * (radius + ripple)
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                return path
            }

            fun drawSoftRing(progress: Float, alpha: Float, wobble: Float) {
                if (alpha <= 0.002f) return
                val radius = baseRadius + expansion * progress
                val path = fluidRingPath(radius, wobble, progress + flowPhase)
                val roundedStroke = { width: Float ->
                    Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)
                }
                drawPath(
                    path = path,
                    color = coverAccent.copy(alpha = alpha * 0.16f),
                    style = roundedStroke(12.dp.toPx())
                )
                drawPath(
                    path = path,
                    color = coverAccent.copy(alpha = alpha * 0.38f),
                    style = roundedStroke(5.dp.toPx())
                )
                drawPath(
                    path = path,
                    color = coverAccent.copy(alpha = alpha),
                    style = roundedStroke(1.15.dp.toPx())
                )
            }

            drawSoftRing(
                progress = 0.08f,
                alpha = 0.10f + glowPulse * 0.18f,
                wobble = 0.45.dp.toPx() + glowPulse * 0.8.dp.toPx()
            )

            repeat(3) { index ->
                val progress = (flowPhase + index / 3f) % 1f
                val fade = sin(progress * PI.toFloat()).coerceAtLeast(0f).pow(1.25f)
                drawSoftRing(
                    progress = progress,
                    alpha = fade * (0.10f + glowPulse * 0.30f),
                    wobble = (0.7.dp.toPx() + glowPulse * 1.7.dp.toPx()) * fade
                )
            }

            if (burstProgress < 1f) {
                val easedProgress = 1f - (1f - burstProgress).pow(2.2f)
                val fade = (1f - burstProgress).pow(1.65f)
                drawSoftRing(
                    progress = easedProgress,
                    alpha = fade * (0.30f + burstStrength * 0.46f),
                    wobble = (1.2.dp.toPx() + burstStrength * 2.2.dp.toPx()) * fade
                )
                drawSoftRing(
                    progress = (easedProgress * 0.78f).coerceIn(0f, 1f),
                    alpha = fade * burstStrength * 0.24f,
                    wobble = 0.8.dp.toPx() * fade
                )
            }
        }

        val pulsingModifier = modifier.graphicsLayer {
            scaleX = artworkScale
            scaleY = artworkScale
            shadowElevation = 18.dp.toPx() + artworkPulse * 18.dp.toPx()
            shape = if (layout == PlayerLayout.DISC) CircleShape else RoundedCornerShape(28.dp)
        }
        if (layout == PlayerLayout.DISC) {
            Disc(
                coverUrl = coverUrl,
                rotation = rotation,
                onAccentColor = { coverAccent = it },
                modifier = pulsingModifier
            )
        } else {
            AlbumCover(
                coverUrl = coverUrl,
                onAccentColor = { coverAccent = it },
                modifier = pulsingModifier
            )
        }
    }
}

@Composable
private fun Disc(
    coverUrl: String?,
    rotation: Float,
    onAccentColor: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val artworkRequest = rememberPlayerArtworkRequest(coverUrl)
    Box(
        modifier = modifier
            .size(280.dp)
            .clip(CircleShape)
            .rotate(rotation)
            .background(Brush.linearGradient(listOf(RedAccent, Color(0xFFC0392B)))),
        contentAlignment = Alignment.Center
    ) {
        if (artworkRequest != null) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = albumArtworkDisplayScale(coverUrl)
                        scaleX = scale
                        scaleY = scale
                    },
                contentScale = ContentScale.Crop,
                onSuccess = { onAccentColor(extractArtworkAccent(it.result.drawable)) }
            )
        }
    }
}

@Composable
private fun AlbumCover(
    coverUrl: String?,
    onAccentColor: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val artworkRequest = rememberPlayerArtworkRequest(coverUrl)
    Box(
        modifier = modifier
            .size(296.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(DarkSurface2),
        contentAlignment = Alignment.Center
    ) {
        if (artworkRequest != null) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = albumArtworkDisplayScale(coverUrl)
                        scaleX = scale
                        scaleY = scale
                    },
                contentScale = ContentScale.Crop,
                onSuccess = { onAccentColor(extractArtworkAccent(it.result.drawable)) }
            )
        }
    }
}

@Composable
private fun rememberPlayerArtworkRequest(coverUrl: String?): ImageRequest? {
    val context = LocalContext.current
    return remember(context, coverUrl) {
        val fullArtworkUrl = albumArtworkUrl(coverUrl) ?: return@remember null
        ImageRequest.Builder(context)
            .data(fullArtworkUrl)
            .size(700)
            .placeholderMemoryCacheKey(albumArtworkThumbnailCacheKey(coverUrl))
            .crossfade(160)
            .build()
    }
}

private fun extractArtworkAccent(drawable: android.graphics.drawable.Drawable): Color {
    return runCatching {
        val bitmap = drawable.toBitmap(
            width = 24,
            height = 24,
            config = android.graphics.Bitmap.Config.ARGB_8888
        )
        var red = 0.0
        var green = 0.0
        var blue = 0.0
        var totalWeight = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                android.graphics.Color.colorToHSV(pixel, hsv)
                val saturation = hsv[1]
                val value = hsv[2]
                if (value < 0.12f || value > 0.94f) continue
                val weight = (0.25f + saturation * 1.5f).toDouble()
                red += android.graphics.Color.red(pixel) * weight
                green += android.graphics.Color.green(pixel) * weight
                blue += android.graphics.Color.blue(pixel) * weight
                totalWeight += weight
            }
        }
        if (totalWeight == 0.0) {
            DefaultGreen500
        } else {
            Color(
                red = (red / totalWeight / 255.0).toFloat(),
                green = (green / totalWeight / 255.0).toFloat(),
                blue = (blue / totalWeight / 255.0).toFloat()
            )
        }
    }.getOrDefault(DefaultGreen500)
}

@Composable
private fun PlayerAtmosphere(background: PlayerBackground) {
    if (background == PlayerBackground.NONE) return

    val accent = Green500
    val transition = rememberInfiniteTransition(label = "playerAtmosphere")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(12_000, easing = LinearEasing)),
        label = "atmosphereProgress"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        when (background) {
            PlayerBackground.SNOW -> {
                repeat(64) { index ->
                    val depth = index % 3
                    val x = ((index * 47 % 127) / 127f) * size.width
                    val baseY = (index * 31 % 131) / 131f
                    val speed = when (depth) { 0 -> 0.14f; 1 -> 0.23f; else -> 0.34f }
                    val y = ((baseY + progress * speed) % 1f) * size.height
                    val drift = sin(progress * 6.28f * (1.1f + depth * 0.22f) + index * 1.7f) * (8f + depth * 7f)
                    val radius = when (depth) { 0 -> 1.3f + index % 3 * 0.35f; 1 -> 1.9f + index % 3 * 0.5f; else -> 2.6f + index % 3 * 0.6f }
                    val alpha = when (depth) { 0 -> 0.12f; 1 -> 0.22f; else -> 0.34f }
                    drawCircle(Color.White.copy(alpha = alpha), radius, androidx.compose.ui.geometry.Offset(x + drift, y))
                }
            }

            PlayerBackground.STARDUST -> {
                repeat(48) { index ->
                    val baseX = (index * 37 % 109) / 109f
                    val baseY = (index * 23 % 127) / 127f
                    val x = ((baseX + sin(progress * 6.28f + index) * 0.012f + 1f) % 1f) * size.width
                    val y = ((baseY - progress * (0.018f + index % 3 * 0.008f) + 1f) % 1f) * size.height
                    val pulse = 0.5f + 0.5f * sin(progress * 12.56f + index * 0.7f)
                    drawCircle(accent.copy(alpha = 0.10f + pulse * 0.22f), 1.1f + pulse * 1.8f, androidx.compose.ui.geometry.Offset(x, y))
                }
            }

            PlayerBackground.RAIN -> {
                repeat(70) { index ->
                    val depth = index % 3
                    val x = ((index * 29 % 103) / 103f) * size.width
                    val baseY = (index * 17 % 107) / 107f
                    val speed = when (depth) { 0 -> 0.26f; 1 -> 0.40f; else -> 0.56f }
                    val y = ((baseY + progress * speed) % 1f) * size.height
                    val length = when (depth) { 0 -> 13f + index % 4 * 4f; 1 -> 20f + index % 4 * 6f; else -> 30f + index % 4 * 8f }
                    val slant = when (depth) { 0 -> 3f; 1 -> 6f; else -> 9f }
                    val alpha = when (depth) { 0 -> 0.12f; 1 -> 0.20f; else -> 0.30f }
                    drawLine(
                        color = Color(0xFFB8D8FF).copy(alpha = alpha),
                        start = androidx.compose.ui.geometry.Offset(x, y),
                        end = androidx.compose.ui.geometry.Offset(x - slant, y + length),
                        strokeWidth = if (depth == 2) 1.6f else 1.15f
                    )
                }
            }

            PlayerBackground.NONE -> Unit
        }
    }
}

private data class LyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null
)

private const val LYRIC_VISUAL_OFFSET_MS = 150L

internal enum class LyricScrollMotion {
    NONE,
    SNAP,
    ANIMATE
}

internal data class LyricScrollPlan(
    val targetIndex: Int,
    val motion: LyricScrollMotion
)

internal fun planLyricScroll(
    previousActiveIndex: Int?,
    activeIndex: Int
): LyricScrollPlan {
    val safeActiveIndex = activeIndex.coerceAtLeast(0)
    val targetIndex = (safeActiveIndex - 3).coerceAtLeast(0)
    if (previousActiveIndex == null) {
        return LyricScrollPlan(targetIndex, LyricScrollMotion.SNAP)
    }

    val safePreviousIndex = previousActiveIndex.coerceAtLeast(0)
    val previousTargetIndex = (safePreviousIndex - 3).coerceAtLeast(0)
    val motion = when {
        previousTargetIndex == targetIndex -> LyricScrollMotion.NONE
        abs(safeActiveIndex - safePreviousIndex) <= 2 -> LyricScrollMotion.ANIMATE
        else -> LyricScrollMotion.SNAP
    }
    return LyricScrollPlan(targetIndex, motion)
}

@Composable
private fun LyricsPanel(
    lyric: String?,
    tlyric: String?,
    currentPosition: Long,
    modifier: Modifier = Modifier
) {
    val lines = remember(lyric, tlyric) { mergeLyrics(lyric, tlyric) }
    val lyricPosition = (currentPosition + LYRIC_VISUAL_OFFSET_MS).coerceAtLeast(0)
    val activeIndex = remember(lines, lyricPosition) {
        lines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
    }
    val initialScrollPlan = remember(lyric, tlyric) {
        planLyricScroll(previousActiveIndex = null, activeIndex = activeIndex)
    }
    val listState = remember(lyric, tlyric) {
        LazyListState(firstVisibleItemIndex = initialScrollPlan.targetIndex)
    }
    var lastActiveIndex by remember(lyric, tlyric) { mutableIntStateOf(activeIndex) }

    LaunchedEffect(activeIndex, lines.size) {
        if (lines.isNotEmpty()) {
            val plan = planLyricScroll(lastActiveIndex, activeIndex)
            lastActiveIndex = activeIndex
            when (plan.motion) {
                LyricScrollMotion.NONE -> Unit
                LyricScrollMotion.SNAP -> listState.scrollToItem(plan.targetIndex)
                LyricScrollMotion.ANIMATE -> listState.animateScrollToItem(plan.targetIndex)
            }
        }
    }

    if (lines.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = "暂无歌词",
                style = MaterialTheme.typography.titleMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 96.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            LyricRow(line = line, active = index == activeIndex)
        }
    }
}

@Composable
private fun LyricRow(line: LyricLine, active: Boolean) {
    val rowPadding by animateDpAsState(
        targetValue = if (active) 8.dp else 6.dp,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "lyricRowPadding"
    )
    val mainColor by animateColorAsState(
        targetValue = if (active) TextPrimary else TextSecondary,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "lyricMainColor"
    )
    val translationColor by animateColorAsState(
        targetValue = if (active) TextSecondary else TextTertiary,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "lyricTranslationColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = rowPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = line.text,
            style = MaterialTheme.typography.bodyMedium,
            color = mainColor,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (!line.translation.isNullOrBlank()) {
            Text(
                text = line.translation,
                style = MaterialTheme.typography.bodySmall,
                color = translationColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private fun mergeLyrics(lyric: String?, tlyric: String?): List<LyricLine> {
    val main = parseLyricLines(lyric)
    if (main.isEmpty()) return emptyList()
    val translations = parseLyricLines(tlyric).associate { it.timeMs to it.text }
    return main.map { line ->
        line.copy(translation = translations[line.timeMs]?.takeIf { it != line.text })
    }
}

private fun parseLyricLines(raw: String?): List<LyricLine> {
    if (raw.isNullOrBlank()) return emptyList()
    val timePattern = Regex("""\[(\d{1,2}):(\d{1,2})(?:\.(\d{1,3}))?]""")
    return raw.lineSequence()
        .flatMap { row ->
            val matches = timePattern.findAll(row).toList()
            if (matches.isEmpty()) return@flatMap emptySequence()
            val text = row.replace(timePattern, "").trim()
            if (text.isBlank()) return@flatMap emptySequence()
            matches.asSequence().mapNotNull { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                LyricLine((minutes * 60 + seconds) * 1000 + millis, text)
            }
        }
        .sortedBy { it.timeMs }
        .toList()
}

@Composable
private fun PlayerControls(
    progress: Float,
    currentPosition: Long,
    duration: Long,
    playMode: PlayMode,
    isPlaying: Boolean,
    quality: PlaybackQuality,
    qualityMenuExpanded: Boolean,
    showProgress: Boolean,
    showTransport: Boolean,
    showExtras: Boolean,
    onQualityMenuExpandedChange: (Boolean) -> Unit,
    onPlayModeClick: () -> Unit,
    onPrevClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    onNextClick: () -> Unit,
    onSeek: (Float) -> Unit,
    onQualityClick: (PlaybackQuality) -> Unit,
    onQueueClick: () -> Unit,
    onSleepClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentHeight()
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        if (showExtras) {
            Box(modifier = Modifier.fillMaxWidth().height(32.dp)) {
                TextButton(
                    onClick = onQueueClick,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.align(Alignment.CenterStart).widthIn(min = 64.dp)
                ) {
                    Text("列表", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
                TextButton(
                    onClick = onSleepClick,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.align(Alignment.CenterEnd).widthIn(min = 64.dp)
                ) {
                    Text("定时", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                }
            }
        }
        if (showProgress) {
            ProgressBar(
                progress = progress,
                currentPosition = currentPosition,
                duration = duration,
                onSeek = onSeek
            )
        }

        if (showTransport || showExtras) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showExtras) {
                    IconButton(onClick = onPlayModeClick) {
                        Icon(
                            imageVector = when (playMode) {
                                PlayMode.SEQUENCE -> androidx.compose.material.icons.Icons.Outlined.Repeat
                                PlayMode.SHUFFLE -> androidx.compose.material.icons.Icons.Outlined.Shuffle
                                PlayMode.REPEAT_ONE -> androidx.compose.material.icons.Icons.Outlined.RepeatOne
                            },
                            contentDescription = "切换播放模式",
                            tint = TextSecondary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                if (showTransport) {
                    IconButton(onClick = onPrevClick) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.SkipPrevious,
                            "上一首",
                            tint = TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    IconButton(
                        onClick = onPlayPauseClick,
                        modifier = Modifier
                            .size(64.dp)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    IconButton(onClick = onNextClick) {
                        Icon(
                            androidx.compose.material.icons.Icons.Filled.SkipNext,
                            "下一首",
                            tint = TextPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                if (showExtras) {
                    Box {
                        IconButton(onClick = { onQualityMenuExpandedChange(true) }) {
                            Icon(
                                imageVector = androidx.compose.material.icons.Icons.Outlined.HighQuality,
                                contentDescription = "选择音质",
                                tint = TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = qualityMenuExpanded,
                            onDismissRequest = { onQualityMenuExpandedChange(false) },
                            containerColor = GlassSurfaceStrong
                        ) {
                            PlaybackQuality.entries.forEach { item ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                item.label,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (item == quality) Green500 else TextPrimary
                                            )
                                            if (item == quality) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text("当前", style = MaterialTheme.typography.labelSmall, color = Green500)
                                            }
                                        }
                                    },
                                    onClick = {
                                        onQualityMenuExpandedChange(false)
                                        onQualityClick(item)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun QueueSheet(
    queue: List<com.ncm.app.data.model.Song>,
    history: List<com.ncm.app.data.model.Song>,
    currentSongId: Long?,
    onDismiss: () -> Unit,
    onQueueSongClick: (com.ncm.app.data.model.Song) -> Unit,
    onHistorySongClick: (com.ncm.app.data.model.Song) -> Unit,
    onRemove: (Long) -> Unit,
    onClear: () -> Unit
) {
    var showHistory by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GlassSurfaceStrong) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { showHistory = false }) { Text("播放列表 ${queue.size}", color = if (!showHistory) Green500 else TextSecondary) }
                TextButton(onClick = { showHistory = true }) { Text("播放历史", color = if (showHistory) Green500 else TextSecondary) }
                Spacer(Modifier.weight(1f))
                if (!showHistory && queue.size > 1) TextButton(onClick = onClear) { Text("清空", color = TextSecondary) }
            }
            val songs = if (showHistory) history else queue
            if (songs.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) { Text("暂无记录", color = TextTertiary) }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
                    items(songs, key = { "${if (showHistory) "h" else "q"}:${it.id}" }) { song ->
                        val isCurrent = song.id == currentSongId
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (showHistory) onHistorySongClick(song) else onQueueSongClick(song)
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(song.name, color = if (isCurrent) Green500 else TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artistText, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (!showHistory && !isCurrent) TextButton(onClick = { onRemove(song.id) }, contentPadding = PaddingValues(4.dp)) { Text("移除", color = TextTertiary, style = MaterialTheme.typography.labelSmall) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SleepTimerSheet(
    remainingSeconds: Int?,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GlassSurfaceStrong) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(
                "睡眠定时",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 14.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(15, 30, 60).forEach { minutes ->
                    OutlinedButton(onClick = { onSet(minutes); onDismiss() }, modifier = Modifier.weight(1f)) { Text("${minutes}分") }
                }
            }
            if (remainingSeconds != null) TextButton(onClick = { onSet(0); onDismiss() }, modifier = Modifier.align(Alignment.End)) { Text("关闭定时", color = RedAccent) }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AppearanceSheet(
    layout: PlayerLayout,
    background: PlayerBackground,
    componentVisibility: PlayerComponentVisibility,
    rhythmArtworkEnabled: Boolean,
    onDismiss: () -> Unit,
    onLayoutSelected: (PlayerLayout) -> Unit,
    onBackgroundSelected: (PlayerBackground) -> Unit,
    onComponentVisibilityChanged: (PlayerComponent, Boolean) -> Unit,
    onRhythmArtworkEnabledChange: (Boolean) -> Unit,
    onApplyImmersivePreset: () -> Unit,
    onResetComponents: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = GlassSurfaceStrong) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("播放页个性化", style = MaterialTheme.typography.titleMedium, color = TextPrimary)

            AppearanceSectionTitle("界面组件", modifier = Modifier.padding(top = 24.dp))
            PlayerComponent.entries.forEach { component ->
                ComponentVisibilityRow(
                    component = component,
                    checked = componentVisibility.isVisible(component),
                    onCheckedChange = { onComponentVisibilityChanged(component, it) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onApplyImmersivePreset,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .accentSurface(RoundedCornerShape(14.dp)),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
                ) {
                    Text("一键沉浸")
                }
                OutlinedButton(
                    onClick = onResetComponents,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) {
                    Text("恢复默认")
                }
            }

            AppearanceSectionTitle("封面版式", modifier = Modifier.padding(top = 24.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PlayerLayout.entries.forEach { option ->
                    AppearanceChoice(
                        label = option.label,
                        selected = option == layout,
                        onClick = { onLayoutSelected(option) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            RhythmArtworkToggleRow(
                checked = rhythmArtworkEnabled,
                onCheckedChange = onRhythmArtworkEnabledChange,
                modifier = Modifier.padding(top = 12.dp)
            )
            AppearanceSectionTitle("氛围动效", modifier = Modifier.padding(top = 24.dp))
            PlayerBackground.entries.chunked(2).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { option ->
                        AppearanceChoice(
                            label = option.label,
                            selected = option == background,
                            onClick = { onBackgroundSelected(option) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RhythmArtworkToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface2)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                "节奏律动",
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
            Text(
                "大封面和取色光晕跟随音乐低频",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Green500
            )
        )
    }
}

@Composable
private fun AppearanceSectionTitle(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = TextSecondary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
    )
}

@Composable
private fun ComponentVisibilityRow(
    component: PlayerComponent,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            component.label,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Green500
            )
        )
    }
}

@Composable
private fun AppearanceChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (selected) Green500.copy(alpha = 0.14f) else DarkSurface2
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Green500 else TextPrimary
        )
    }
}

@Composable
private fun ProgressBar(
    progress: Float,
    currentPosition: Long,
    duration: Long,
    onSeek: (Float) -> Unit
) {
    var draggingProgress by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    var isDragging by remember { mutableStateOf(false) }
    val visibleProgress = if (isDragging) draggingProgress else progress.coerceIn(0f, 1f)
    val visiblePosition = if (isDragging && duration > 0) {
        (duration * visibleProgress).toLong()
    } else {
        currentPosition
    }
    var trackWidthPx by remember { mutableFloatStateOf(1f) }
    val enabled = duration > 0
    val activeColor = if (enabled) Color.White else TextTertiary

    fun progressFromX(x: Float): Float {
        return (x / trackWidthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    }

    LaunchedEffect(progress, isDragging) {
        if (!isDragging) {
            draggingProgress = progress.coerceIn(0f, 1f)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat().coerceAtLeast(1f) }
                .pointerInput(enabled, trackWidthPx) {
                    if (enabled) {
                        detectTapGestures { offset ->
                            val nextProgress = progressFromX(offset.x)
                            draggingProgress = nextProgress
                            onSeek(nextProgress)
                        }
                    }
                }
                .pointerInput(enabled, trackWidthPx) {
                    if (enabled) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                draggingProgress = progressFromX(offset.x)
                            },
                            onDragEnd = {
                                isDragging = false
                                onSeek(draggingProgress)
                            },
                            onDragCancel = {
                                isDragging = false
                            }
                        ) { change, _ ->
                            draggingProgress = progressFromX(change.position.x)
                        }
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(DarkSurface)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(visibleProgress)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(activeColor)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (maxWidth - 10.dp) * visibleProgress)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(activeColor)
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 0.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatTime(visiblePosition), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).toInt().coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

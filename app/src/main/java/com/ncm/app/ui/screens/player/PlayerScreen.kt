package com.ncm.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.ui.theme.*
import com.ncm.app.NeteaseApp
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayMode
import com.ncm.app.viewmodel.PlaybackQuality
import com.ncm.app.viewmodel.PlayerViewModel
import kotlin.math.sin

@Composable
fun PlayerScreen(
    songId: Long,
    onBack: () -> Unit,
    mainViewModel: MainViewModel,
    viewModel: PlayerViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    var qualityMenuExpanded by remember { mutableStateOf(false) }
    var showLyrics by remember(songId) { mutableStateOf(false) }
    var bottomPanel by remember { mutableStateOf<PlayerBottomPanel?>(null) }
    val playerLayout by NeteaseApp.instance.playerAppearanceSettings.layout.collectAsState()
    val playerBackground by NeteaseApp.instance.playerAppearanceSettings.background.collectAsState()

    LaunchedEffect(songId) {
        viewModel.open(songId)
    }

    var rotation by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
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
            .background(Brush.verticalGradient(listOf(Color(0xFF1A0A0A), DarkBg)))
    ) {
        PlayerAtmosphere(background = playerBackground)
        PlayerTopBar(
            title = state.currentSong?.name ?: "未知歌曲",
            subtitle = state.currentSong?.artistText ?: "未知歌手",
            audioSource = state.audioSource,
            onBack = onBack,
            isLiked = state.isLiked,
            isLikeUpdating = state.isLikeUpdating,
            onLikeClick = {
                viewModel.toggleLike { song, liked ->
                    mainViewModel.onLikedSongChanged(song, liked)
                }
            },
            onAppearanceClick = { bottomPanel = PlayerBottomPanel.APPEARANCE },
            modifier = Modifier.align(Alignment.TopCenter)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 92.dp, bottom = 196.dp, start = 24.dp, end = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            val unavailableMessage = state.error?.takeIf {
                state.songUrl.isNullOrBlank() && !state.isLoading && !state.isPlaying
            }
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
                        ) { showLyrics = false }
                )
            } else {
                val coverModifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { showLyrics = true }
                if (playerLayout == PlayerLayout.DISC) {
                    Disc(
                        coverUrl = state.currentSong?.album?.picUrl,
                        rotation = rotation,
                        modifier = coverModifier
                    )
                } else {
                    AlbumCover(
                        coverUrl = state.currentSong?.album?.picUrl,
                        modifier = coverModifier
                    )
                }
            }
        }

        state.error?.takeUnless { state.songUrl.isNullOrBlank() && !state.isLoading && !state.isPlaying }?.let {
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

        PlayerControls(
            progress = state.progress,
            currentPosition = state.currentPosition,
            duration = state.duration,
            playMode = state.playMode,
            isPlaying = state.isPlaying,
            quality = state.quality,
            qualityMenuExpanded = qualityMenuExpanded,
            onQualityMenuExpandedChange = { qualityMenuExpanded = it },
            onPlayModeClick = { viewModel.togglePlayMode() },
            onPrevClick = { viewModel.playPrev() },
            onPlayPauseClick = { viewModel.togglePlay() },
            onNextClick = { viewModel.playNext() },
            onSeek = { viewModel.setProgress(it) },
            onQualityClick = { viewModel.setQuality(it) },
            onQueueClick = { bottomPanel = PlayerBottomPanel.QUEUE },
            onSleepClick = { bottomPanel = PlayerBottomPanel.SLEEP },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
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
            onDismiss = { bottomPanel = null },
            onLayoutSelected = NeteaseApp.instance.playerAppearanceSettings::setLayout,
            onBackgroundSelected = NeteaseApp.instance.playerAppearanceSettings::setBackground
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
    subtitle: String,
    audioSource: String,
    onBack: () -> Unit,
    isLiked: Boolean,
    isLikeUpdating: Boolean,
    onLikeClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 72.dp),
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
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelMedium,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
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
            // Keeps the title anchored to the screen center while retaining the right action.
            IconButton(onClick = onLikeClick, enabled = !isLikeUpdating) {
                Icon(
                    imageVector = if (isLiked) androidx.compose.material.icons.Icons.Filled.Favorite else androidx.compose.material.icons.Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    tint = if (isLiked) RedAccent else TextPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun AudioSourceTag(source: String) {
    val label = when (source) {
        "huibq-wy" -> "Huibq"
        "ikun-wy" -> "ikun"
        "kugou" -> "酷狗"
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
private fun Disc(
    coverUrl: String?,
    rotation: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(280.dp)
            .clip(CircleShape)
            .rotate(rotation)
            .background(Brush.linearGradient(listOf(RedAccent, Color(0xFFC0392B)))),
        contentAlignment = Alignment.Center
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = sizedImageUrl(coverUrl, 700),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun AlbumCover(
    coverUrl: String?,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(296.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(DarkSurface2),
        contentAlignment = Alignment.Center
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = sizedImageUrl(coverUrl, 700),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
    }
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

@Composable
private fun LyricsPanel(
    lyric: String?,
    tlyric: String?,
    currentPosition: Long,
    modifier: Modifier = Modifier
) {
    val lines = remember(lyric, tlyric) { mergeLyrics(lyric, tlyric) }
    val listState = rememberLazyListState()
    val lyricPosition = (currentPosition + LYRIC_VISUAL_OFFSET_MS).coerceAtLeast(0)
    val activeIndex = remember(lines, lyricPosition) {
        lines.indexOfLast { it.timeMs <= lyricPosition }.coerceAtLeast(0)
    }

    LaunchedEffect(activeIndex, lines.size) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem(
                index = (activeIndex - 3).coerceAtLeast(0),
                scrollOffset = 0
            )
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
            .height(174.dp)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(26.dp)) {
            TextButton(
                onClick = onQueueClick,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.align(Alignment.CenterStart).width(64.dp)
            ) {
                Text("列表", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
            TextButton(
                onClick = onSleepClick,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.align(Alignment.CenterEnd).width(64.dp)
            ) {
                Text("定时", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
        ProgressBar(progress = progress, currentPosition = currentPosition, duration = duration, onSeek = onSeek)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp, bottom = 30.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPlayModeClick) {
                Icon(
                    imageVector = when (playMode) {
                        PlayMode.SEQUENCE -> androidx.compose.material.icons.Icons.Outlined.Repeat
                        PlayMode.SHUFFLE -> androidx.compose.material.icons.Icons.Outlined.Shuffle
                        PlayMode.REPEAT_ONE -> androidx.compose.material.icons.Icons.Outlined.RepeatOne
                    },
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }

            IconButton(onClick = onPrevClick) {
                Icon(androidx.compose.material.icons.Icons.Filled.SkipPrevious, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
            }

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Green500),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onPlayPauseClick) {
                    Icon(
                        imageVector = if (isPlaying) androidx.compose.material.icons.Icons.Filled.Pause else androidx.compose.material.icons.Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            IconButton(onClick = onNextClick) {
                Icon(androidx.compose.material.icons.Icons.Filled.SkipNext, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
            }

            Box {
                IconButton(onClick = { onQualityMenuExpandedChange(true) }) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.HighQuality,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DropdownMenu(
                    expanded = qualityMenuExpanded,
                    onDismissRequest = { onQualityMenuExpandedChange(false) },
                    containerColor = DarkSurface
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DarkSurface) {
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
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DarkSurface) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("睡眠定时", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(remainingSeconds?.let { "将在 ${it / 60}:${"%02d".format(it % 60)} 后暂停播放" } ?: "到时间后自动暂停播放", color = TextTertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
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
    onDismiss: () -> Unit,
    onLayoutSelected: (PlayerLayout) -> Unit,
    onBackgroundSelected: (PlayerBackground) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DarkSurface) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .padding(bottom = 20.dp)
        ) {
            Text("播放页个性化", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "动效只在背景层显示，不影响歌词阅读。",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
            )
            Text("布局", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
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
            Text(
                "动态背景",
                style = MaterialTheme.typography.labelLarge,
                color = TextSecondary,
                modifier = Modifier.padding(top = 22.dp)
            )
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
    val activeColor = if (enabled) Green500 else TextTertiary

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

package com.ncm.app.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.material3.*
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
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayMode
import com.ncm.app.viewmodel.PlaybackQuality
import com.ncm.app.viewmodel.PlayerViewModel

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
            modifier = Modifier.align(Alignment.TopCenter)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 92.dp, bottom = 170.dp, start = 24.dp, end = 24.dp),
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
                Disc(
                    coverUrl = state.currentSong?.album?.picUrl,
                    isPlaying = state.isPlaying,
                    rotation = rotation,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showLyrics = true }
                )
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
                    .padding(start = 24.dp, end = 24.dp, bottom = 148.dp)
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
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 20.dp, top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = TextPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
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
    isPlaying: Boolean,
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(148.dp)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
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

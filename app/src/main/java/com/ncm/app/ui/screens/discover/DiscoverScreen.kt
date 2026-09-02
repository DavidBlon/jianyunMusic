package com.ncm.app.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.domain.weekly.WEEKLY_PLAYLIST_ID
import com.ncm.app.domain.weekly.WeeklyRecUiState
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.ui.theme.*
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.RecommendedPlaylistUi

/**
 * 发现页沿用旧版网易云式的推荐卡片层级，内容统一来自用户当前选择的在线音源。
 */
@Composable
fun DiscoverScreen(
    onPlaylistClick: (Long) -> Unit,
    onPluginSongClick: (OnlineTrack) -> Unit,
    onSearchClick: () -> Unit = {},
    onRecentSongClick: (OnlineTrack) -> Unit = {},
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.discoverState.collectAsState()
    val weeklyState by viewModel.weeklyRecState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadDiscover()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
    ) {
        item { Header() }
        item { SearchBar(onSearchClick = onSearchClick) }
        val weeklySongs = (weeklyState as? WeeklyRecUiState.Success)?.songs.orEmpty()
        if (weeklySongs.isNotEmpty()) {
            item {
                WeeklyRecommendationCard(
                    songCount = weeklySongs.size,
                    cover = weeklySongs.firstOrNull()?.cover,
                    onClick = { onPlaylistClick(WEEKLY_PLAYLIST_ID) }
                )
            }
        }
        item {
            TopLists(
                lists = state.topLists,
                sourceLabel = state.recommendationSourceLabel,
                onClick = onPlaylistClick
            )
        }
        item {
            RecommendedPlaylists(
                playlists = state.recommendedPlaylists,
                sourceLabel = state.recommendationSourceLabel,
                isLoading = state.isLoading,
                error = state.recommendationError,
                onClick = onPlaylistClick
            )
        }
        item {
            RecommendedSongs(
                songs = state.recommendedSongs,
                sourceLabel = state.recommendationSourceLabel,
                isLoading = state.isLoading,
                error = state.songRecommendationError,
                onClick = onPluginSongClick
            )
        }
        if (state.recentTracks.isNotEmpty()) {
            item {
                RecentTracks(
                    tracks = state.recentTracks,
                    onClick = onRecentSongClick
                )
            }
        }
    }
}

@Composable
private fun Header() {
    Text(
        text = "发现",
        style = MaterialTheme.typography.headlineLarge,
        color = TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun SearchBar(onSearchClick: () -> Unit) {
    Surface(
        onClick = onSearchClick,
        shape = RoundedCornerShape(22.dp),
        color = Color(0x14FFFFFF),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp)
            .height(44.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "搜索歌曲、歌单",
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary
            )
        }
    }
}

@Composable
private fun WeeklyRecommendationCard(songCount: Int, cover: String?, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1DB954), Color(0xFF0E6B3A))
                )
            )
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(
                    model = sizedImageUrl(cover, 160),
                    contentDescription = "每周推荐",
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "每周推荐",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Text(
                    text = "基于上周播放记录 · $songCount 首",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun TopLists(
    lists: List<RecommendedPlaylistUi>,
    sourceLabel: String?,
    onClick: (Long) -> Unit
) {
    if (lists.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("排行榜", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        sourceLabel?.let {
            Spacer(Modifier.width(8.dp))
            Text("来自$it", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(lists.take(10), key = { it.id }) { list ->
            RecommendedPlaylistCard(list) { onClick(list.id) }
        }
    }
}

@Composable
private fun RecommendedPlaylists(
    playlists: List<RecommendedPlaylistUi>,
    sourceLabel: String?,
    isLoading: Boolean,
    error: String?,
    onClick: (Long) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("推荐歌单", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        sourceLabel?.let {
            Spacer(Modifier.width(8.dp))
            Text("来自$it", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
    when {
        isLoading && playlists.isEmpty() -> Box(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Green500, modifier = Modifier.size(28.dp))
        }
        playlists.isEmpty() -> Text(
            text = error ?: "暂无推荐歌单",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
        )
        else -> LazyRow(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(playlists.take(10), key = { it.id }) { playlist ->
                RecommendedPlaylistCard(playlist) { onClick(playlist.id) }
            }
        }
    }
}

@Composable
private fun RecommendedPlaylistCard(playlist: RecommendedPlaylistUi, onClick: () -> Unit) {
    Column(modifier = Modifier.width(118.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(118.dp)
                .clip(RoundedCornerShape(12.dp))
                .glassSurface(RoundedCornerShape(12.dp), elevation = 8.dp)
        ) {
            if (!playlist.artworkUrl.isNullOrBlank()) {
                AsyncImage(
                    model = sizedImageUrl(playlist.artworkUrl, 260),
                    contentDescription = playlist.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000))))
            )
            if (playlist.playCount > 0L) {
                Text(
                    text = formatPlayCount(playlist.playCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .background(Color(0x78090C10), RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = playlist.title,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun formatPlayCount(count: Long): String = when {
    count >= 100_000_000L -> "${count / 100_000_000L}亿"
    count >= 10_000L -> "${count / 10_000L}万"
    else -> count.toString()
}

@Composable
private fun RecommendedSongs(
    songs: List<OnlineTrack>,
    sourceLabel: String?,
    isLoading: Boolean,
    error: String?,
    onClick: (OnlineTrack) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("推荐歌曲", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        sourceLabel?.let {
            Spacer(Modifier.width(8.dp))
            Text("来自$it", style = MaterialTheme.typography.labelSmall, color = TextTertiary)
        }
    }
    when {
        isLoading && songs.isEmpty() -> Box(
            modifier = Modifier.fillMaxWidth().height(150.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = Green500, modifier = Modifier.size(28.dp))
        }
        songs.isEmpty() -> Text(
            text = error ?: "暂无推荐歌曲",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
        )
        else -> Column(
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)
        ) {
            songs.forEach { song ->
                SongListItem(song, onClick = { onClick(song) })
            }
        }
    }
}

@Composable
private fun SongListItem(song: OnlineTrack, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val albumUrl = song.artworkUrl ?: song.album?.artworkUrl
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .glassSurface(RoundedCornerShape(8.dp), elevation = 4.dp)
        ) {
            if (!albumUrl.isNullOrBlank()) {
                AsyncImage(
                    sizedImageUrl(albumUrl, 160),
                    song.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                song.artists.joinToString(" / ") { it.name }.ifBlank { "未知歌手" },
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "播放",
            tint = TextSecondary
        )
    }
}

@Composable
private fun RecentTracks(tracks: List<OnlineTrack>, onClick: (OnlineTrack) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("最近播放", style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
    }
    Column(modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)) {
        tracks.forEach { track ->
            SongListItem(track, onClick = { onClick(track) })
        }
    }
}
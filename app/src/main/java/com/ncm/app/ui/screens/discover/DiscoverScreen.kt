package com.ncm.app.ui.screens.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Hearing
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.PinnedPlaylist
import com.ncm.app.data.model.Song
import com.ncm.app.data.model.UserProfile
import com.ncm.app.ui.theme.*
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel

@Composable
fun DiscoverScreen(
    onPlaylistClick: (Long) -> Unit,
    onSongClick: (Long) -> Unit,
    onSearchClick: () -> Unit,
    onQuickClick: (String) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.discoverState.collectAsState()
    val profile = viewModel.currentProfile()

    LaunchedEffect(Unit) {
        viewModel.loadDiscover()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
    ) {
        item { Header(profile = profile) }
        item { SearchBar(onClick = onSearchClick) }
        item { QuickActions(onQuickClick) }
        item { RecommendedPlaylists(state.playlists, onPlaylistClick) }
        item { RecommendedSongs(state.dailySongs, onSongClick) }
    }
}

@Composable
private fun Header(profile: UserProfile?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .glassSurface(CircleShape, elevation = 8.dp)
                .padding(2.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (!profile?.avatar.isNullOrBlank()) {
                AsyncImage(
                    sizedImageUrl(profile?.avatar, 120),
                    contentDescription = "用户头像",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.Person,
                    contentDescription = "用户",
                    tint = TextPrimary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = "发现",
            style = MaterialTheme.typography.headlineLarge,
            color = TextPrimary
        )
    }
}

@Composable
private fun SearchBar(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 12.dp)
            .glassSurface(RoundedCornerShape(12.dp), elevation = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Outlined.Search,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("搜索歌曲、歌手、专辑", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
    }
}

@Composable
private fun QuickActions(onClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        QuickActionItem(IconsType.Fm, "私人FM", Green500) { onClick("fm") }
        QuickActionItem(IconsType.Podcast, "播客", AccentSecondary) { onClick("podcast") }
        QuickActionItem(IconsType.Rank, "排行榜", AccentHighlight) { onClick("rank") }
        QuickActionItem(
            IconsType.Playlist,
            "歌单",
            lerp(Green500, AccentHighlight, 0.48f)
        ) { onClick("playlist") }
    }
}

@Composable
private fun QuickActionItem(
    type: IconsType,
    label: String,
    tint: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .glassSurface(CircleShape, tint = tint, elevation = 8.dp)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(tint.copy(alpha = 0.32f), Color.Transparent)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                ActionIcon(type, tint)
            }
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private enum class IconsType { Fm, Podcast, Rank, Playlist }

@Composable
private fun ActionIcon(type: IconsType, tint: Color) {
    val icon = when (type) {
        IconsType.Fm -> androidx.compose.material.icons.Icons.Outlined.Hearing
        IconsType.Podcast -> androidx.compose.material.icons.Icons.Outlined.Mic
        IconsType.Rank -> androidx.compose.material.icons.Icons.Outlined.BarChart
        IconsType.Playlist -> androidx.compose.material.icons.Icons.AutoMirrored.Outlined.QueueMusic
    }
    Icon(
        icon,
        contentDescription = null,
        tint = lerp(tint, Color.White, 0.18f),
        modifier = Modifier.size(22.dp)
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.headlineSmall,
        color = TextPrimary,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp)
    )
}

@Composable
private fun RecommendedPlaylists(playlists: List<PinnedPlaylist>, onClick: (Long) -> Unit) {
    SectionHeader("推荐歌单")
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(playlists.take(8), key = { it.id }) { playlist ->
            PlaylistCard(playlist, onClick = { onClick(playlist.id) })
        }
    }
}

@Composable
private fun PlaylistCard(playlist: PinnedPlaylist, onClick: () -> Unit) {
    Column(modifier = Modifier.width(118.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(118.dp)
                .glassSurface(RoundedCornerShape(12.dp), elevation = 8.dp)
        ) {
            if (!playlist.picUrl.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(playlist.picUrl, 260), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.46f))
                        )
                    )
            )
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
        Text(
            playlist.name,
            style = MaterialTheme.typography.bodySmall,
            color = TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun RecommendedSongs(songs: List<Song>, onClick: (Long) -> Unit) {
    SectionHeader("推荐歌曲")
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(songs.take(8), key = { it.id }) { song ->
            SongCard(song, onClick = { onClick(song.id) })
        }
    }
}

@Composable
private fun SongCard(song: Song, onClick: () -> Unit) {
    Column(modifier = Modifier.width(118.dp).clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .size(118.dp)
                .glassSurface(RoundedCornerShape(12.dp), elevation = 8.dp)
        ) {
            val albumUrl = song.album?.picUrl
            if (!albumUrl.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(albumUrl, 260), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Text(song.name, style = MaterialTheme.typography.bodySmall, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(song.artistText, style = MaterialTheme.typography.labelSmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatPlayCount(count: Long): String {
    return when {
        count >= 100_000_000 -> "${count / 100_000_000}亿"
        count >= 10_000 -> "${count / 10_000}万"
        else -> count.toString()
    }
}

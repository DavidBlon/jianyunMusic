package com.ncm.app.ui.screens.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.Song
import com.ncm.app.data.model.UserProfile
import com.ncm.app.ui.theme.*
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel

/**
 * 本地首页（P6T3，spec §18）：不再展示依赖网易云相似歌曲/推荐歌单的在线推荐，
 * 只保留本地最近播放、本地收藏与简云官方内容。
 */
@Composable
fun DiscoverScreen(
    onPlaylistClick: (Long) -> Unit,
    onSongClick: (Long) -> Unit,
    onSearchClick: () -> Unit,
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
        item { LocalStats(likedCount = state.likedCount) }
        item { RecentSongs(state.recentSongs, onSongClick) }
        item { OfficialSongs(state.officialSongs, onSongClick) }
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
        Column {
            Text(
                text = "发现",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary
            )
            Text(
                text = "本地音乐 · 在线来源见设置",
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
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
private fun LocalStats(likedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp)
            .glassSurface(RoundedCornerShape(16.dp), elevation = 8.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Outlined.Favorite,
            contentDescription = null,
            tint = Green500,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("本地收藏 $likedCount 首", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}

@Composable
private fun RecentSongs(songs: List<Song>, onClick: (Long) -> Unit) {
    SectionHeader("最近播放")
    if (songs.isEmpty()) {
        Text(
            "暂无播放记录",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
        )
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(songs.take(12), key = { it.id }) { song ->
            SongCard(song, onClick = { onClick(song.id) })
        }
    }
}

@Composable
private fun OfficialSongs(songs: List<Song>, onClick: (Long) -> Unit) {
    SectionHeader("简云官方歌曲")
    if (songs.isEmpty()) {
        Text(
            "简云官方目录暂不可用",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)
        )
        return
    }
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(songs.take(12), key = { it.id }) { song ->
            SongCard(song, onClick = { onClick(song.id) })
        }
    }
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

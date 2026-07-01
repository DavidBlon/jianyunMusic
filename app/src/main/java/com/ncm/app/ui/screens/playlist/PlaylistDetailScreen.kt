package com.ncm.app.ui.screens.playlist

import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.Song
import com.ncm.app.ui.theme.*
import com.ncm.app.viewmodel.MainViewModel

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onSongClick: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.playlistState.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val visibleSongs = remember(state.songs, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            state.songs
        } else {
            state.songs.filter { song ->
                song.name.contains(query, ignoreCase = true) ||
                    song.artistText.contains(query, ignoreCase = true)
            }
        }
    }

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylistDetail(playlistId)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 92.dp)
        ) {
            item {
                PlaylistHeader(
                    coverUrl = state.playlist?.cover,
                    title = state.playlist?.name ?: "歌单",
                    count = state.playlist?.trackCount ?: state.songs.size,
                    onBack = onBack,
                    isSearching = isSearching,
                    searchQuery = searchQuery,
                    onSearchClick = { isSearching = true },
                    onSearchQueryChange = { searchQuery = it },
                    onCloseSearch = {
                        isSearching = false
                        searchQuery = ""
                    }
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { visibleSongs.firstOrNull()?.let { onSongClick(it.id) } },
                        enabled = visibleSongs.isNotEmpty(),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green500),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("播放全部", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (state.isLoading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Green500)
                    }
                }
            } else {
                itemsIndexed(
                    items = visibleSongs,
                    key = { _, song -> song.id }
                ) { index, song ->
                    SongListItem(
                        index = index + 1,
                        song = song,
                        onClick = { onSongClick(song.id) },
                        modifier = Modifier.padding(horizontal = 20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistHeader(
    coverUrl: String?,
    title: String,
    count: Int,
    onBack: () -> Unit,
    isSearching: Boolean,
    searchQuery: String,
    onSearchClick: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCloseSearch: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(DarkBg2)
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = coverUrl.highQualityCoverUrl(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color(0x33000000), Color(0xCC000000))))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(Color(0x1AFFFFFF), RoundedCornerShape(50))
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (isSearching) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .background(Color(0x33000000), RoundedCornerShape(18.dp))
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.Search,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                        cursorBrush = Brush.verticalGradient(listOf(Green500, Green500)),
                        modifier = Modifier.weight(1f),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (searchQuery.isBlank()) {
                                    Text(
                                        text = "搜索歌单内音乐",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White.copy(alpha = 0.55f)
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                    IconButton(onClick = onCloseSearch, modifier = Modifier.size(30.dp)) {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Outlined.Close,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            } else {
                IconButton(
                    onClick = onSearchClick,
                    modifier = Modifier
                        .background(Color(0x1AFFFFFF), RoundedCornerShape(50))
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Outlined.Search,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Text(
                text = "歌单",
                style = MaterialTheme.typography.labelMedium,
                color = Green500
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "网易云音乐 · $count 首歌",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xB3FFFFFF),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun SongListItem(
    index: Int,
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = TextTertiary,
            modifier = Modifier.width(28.dp)
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.artistText,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Text(song.durationText, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Outlined.MoreVert,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }

    HorizontalDivider(color = DarkBorder, thickness = 0.5.dp, modifier = modifier)
}

private fun String.highQualityCoverUrl(): String {
    val clean = substringBefore("?")
    return "$clean?param=1200y1200"
}

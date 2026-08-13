package com.ncm.app.ui.screens.playlist

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.imageLoader
import coil.compose.AsyncImage
import com.ncm.app.plugin.model.pluginSourceDisplayName
import coil.request.ImageRequest
import com.ncm.app.data.FavoriteOrderStore
import com.ncm.app.data.PlaylistMutationResult
import com.ncm.app.data.isUserPlaylistId
import com.ncm.app.data.model.Song
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.ui.components.AddToPlaylistDialog
import com.ncm.app.ui.theme.*
import com.ncm.app.util.albumArtworkThumbnailCacheKey
import com.ncm.app.util.albumArtworkThumbnailUrl
import com.ncm.app.util.albumArtworkDisplayScale
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val PLAYLIST_ARTWORK_PREFETCH_AHEAD = 6
private const val PLAYLIST_ARTWORK_DECODE_SIZE_PX = 160

private sealed interface PlaylistTrackRow {
    val stableKey: String

    data class Local(val song: Song) : PlaylistTrackRow {
        override val stableKey: String = FavoriteOrderStore.localKey(song.id)
    }

    data class Online(val track: OnlineTrack) : PlaylistTrackRow {
        override val stableKey: String = FavoriteOrderStore.onlineKey(track.key)
    }
}

private fun orderedTrackRows(
    songs: List<Song>,
    onlineTracks: List<OnlineTrack>,
    order: List<String>
): List<PlaylistTrackRow> {
    val rows = songs.map { PlaylistTrackRow.Local(it) } + onlineTracks.map { PlaylistTrackRow.Online(it) }
    if (order.isEmpty()) return rows
    val positions = order.withIndex().associate { it.value to it.index }
    return rows.withIndex()
        .sortedWith(
            compareBy<IndexedValue<PlaylistTrackRow>> { positions[it.value.stableKey] ?: Int.MAX_VALUE }
                .thenBy { it.index }
        )
        .map { it.value }
}

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    onSongClick: (Long) -> Unit,
    onPluginSongClick: (OnlineTrack) -> Unit,
    onBack: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.playlistState.collectAsState()
    val myState by viewModel.myState.collectAsState()
    val userPlaylists = remember(myState.playlists) {
        myState.playlists.filter { isUserPlaylistId(it.id) }
    }
    var isSearching by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var songToAdd by remember { mutableStateOf<Song?>(null) }
    var onlineTrackToAdd by remember { mutableStateOf<OnlineTrack?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
    val visiblePluginTracks = remember(state.pluginTracks, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            state.pluginTracks
        } else {
            state.pluginTracks.filter { track ->
                track.title.contains(query, ignoreCase = true) ||
                    track.artists.any { it.name.contains(query, ignoreCase = true) }
            }
        }
    }
    val visibleTracks = remember(visibleSongs, visiblePluginTracks, state.trackOrder) {
        orderedTrackRows(visibleSongs, visiblePluginTracks, state.trackOrder)
    }
    val hasVisibleTracks = visibleTracks.isNotEmpty()
    val context = LocalContext.current
    val imageLoader = context.imageLoader
    val listState = rememberLazyListState()

    LaunchedEffect(playlistId) {
        viewModel.loadPlaylistDetail(playlistId)
        viewModel.loadMyData()
    }

    LaunchedEffect(playlistId, state.loadedPlaylistId, visibleTracks) {
        if (state.loadedPlaylistId != playlistId) return@LaunchedEffect
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.mapNotNull { item -> item.key as? String }
        }
            .distinctUntilChanged()
            .collect { visibleTrackKeys ->
                val lastVisibleTrackIndex = visibleTracks.indexOfLast { it.stableKey in visibleTrackKeys }
                visibleTracks
                    .drop((lastVisibleTrackIndex + 1).coerceAtLeast(0))
                    .take(PLAYLIST_ARTWORK_PREFETCH_AHEAD)
                    .mapNotNull { row ->
                        (row as? PlaylistTrackRow.Local)?.song?.album?.picUrl
                    }
                    .mapNotNull { coverUrl -> playlistArtworkRequest(context, coverUrl) }
                    .forEach { request -> imageLoader.enqueue(request) }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
        ) {
            item {
                PlaylistHeader(
                    coverUrl = state.playlist?.cover,
                    title = state.playlist?.name ?: "歌单",
                    count = state.playlist?.trackCount ?: (state.songs.size + state.pluginTracks.size),
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
                        onClick = {
                            when (val first = visibleTracks.firstOrNull()) {
                                is PlaylistTrackRow.Local -> onSongClick(first.song.id)
                                is PlaylistTrackRow.Online -> onPluginSongClick(first.track)
                                null -> Unit
                            }
                        },
                        enabled = hasVisibleTracks,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .weight(1f)
                            .accentSurface(RoundedCornerShape(20.dp))
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

            if (state.isLoading && hasVisibleTracks) {
                item {
                    LinearProgressIndicator(
                        color = Green500,
                        trackColor = DarkBorder,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
            }

            if (state.isLoading && !hasVisibleTracks) {
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
            } else if (!hasVisibleTracks) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.error ?: "暂无歌曲",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary
                        )
                    }
                }
            } else {
                items(
                    items = visibleTracks,
                    key = PlaylistTrackRow::stableKey
                ) { row ->
                    when (row) {
                        is PlaylistTrackRow.Local -> {
                            val song = row.song
                            SongListItem(
                                song = song,
                                onClick = { onSongClick(song.id) },
                                onAddToPlaylist = { songToAdd = song },
                                onRemoveFromPlaylist = if (isUserPlaylistId(playlistId)) {
                                    {
                                        if (viewModel.removeSongFromUserPlaylist(playlistId, song.id)) {
                                            scope.launch { snackbarHostState.showSnackbar("已从歌单中移除") }
                                        }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }

                        is PlaylistTrackRow.Online -> {
                            val track = row.track
                            OnlineTrackListItem(
                                track = track,
                                onClick = { onPluginSongClick(track) },
                                onAddToPlaylist = { onlineTrackToAdd = track },
                                onRemoveFromPlaylist = if (isUserPlaylistId(playlistId)) {
                                    {
                                        if (viewModel.removeOnlineTrackFromUserPlaylist(playlistId, track)) {
                                            scope.launch { snackbarHostState.showSnackbar("已从歌单中移除") }
                                        }
                                    }
                                } else {
                                    null
                                },
                                modifier = Modifier.padding(horizontal = 20.dp)
                            )
                        }
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = miniPlayerSafeBottomPadding())
        )
    }

    songToAdd?.let { song ->
        AddToPlaylistDialog(
            playlists = userPlaylists,
            onDismiss = { songToAdd = null },
            onPlaylistSelected = { playlist ->
                val result = viewModel.addSongToUserPlaylist(playlist.id, song)
                scope.launch { snackbarHostState.showSnackbar(result.messageFor(playlist.name)) }
            },
            onCreatePlaylist = { name ->
                val playlistId = viewModel.createUserPlaylist(name)
                val result = playlistId?.let { viewModel.addSongToUserPlaylist(it, song) }
                    ?: PlaylistMutationResult.NOT_FOUND
                scope.launch { snackbarHostState.showSnackbar(result.messageFor(name)) }
            }
        )
    }

    onlineTrackToAdd?.let { track ->
        AddToPlaylistDialog(
            playlists = userPlaylists,
            onDismiss = { onlineTrackToAdd = null },
            onPlaylistSelected = { playlist ->
                val result = viewModel.addOnlineTrackToUserPlaylist(playlist.id, track)
                scope.launch { snackbarHostState.showSnackbar(result.messageFor(playlist.name)) }
            },
            onCreatePlaylist = { name ->
                val playlistId = viewModel.createUserPlaylist(name)
                val result = playlistId?.let { viewModel.addOnlineTrackToUserPlaylist(it, track) }
                    ?: PlaylistMutationResult.NOT_FOUND
                scope.launch { snackbarHostState.showSnackbar(result.messageFor(name)) }
            }
        )
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
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(DarkBg2)
    ) {
        if (!coverUrl.isNullOrBlank()) {
            AsyncImage(
                model = sizedImageUrl(coverUrl, 800),
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
                .statusBarsPadding()
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
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        ),
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
                    IconButton(
                        onClick = {
                            focusManager.clearFocus()
                            keyboardController?.hide()
                            onCloseSearch()
                        },
                        modifier = Modifier.size(30.dp)
                    ) {
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
                text = "$count 首歌",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xB3FFFFFF),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
fun SongListItem(
    song: Song,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val artworkRequest = remember(context, song.album?.picUrl) {
        playlistArtworkRequest(context, song.album?.picUrl)
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentSecondary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(22.dp)
            )
            if (artworkRequest != null) {
                AsyncImage(
                    model = artworkRequest,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = albumArtworkDisplayScale(song.album?.picUrl)
                            scaleX = scale
                            scaleY = scale
                        },
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))

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

        if (song.dt > 0) {
            Text(song.durationText, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        PlaylistTrackMenu(
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist
        )
    }

    HorizontalDivider(color = DarkBorder, thickness = 0.5.dp, modifier = modifier)
}

@Composable
private fun OnlineTrackListItem(
    track: OnlineTrack,
    onClick: () -> Unit,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coverUrl = track.artworkUrl ?: track.album?.artworkUrl
    val artworkRequest = remember(context, coverUrl) { playlistArtworkRequest(context, coverUrl) }
    val artistText = track.artists.joinToString(" / ") { it.name }.ifBlank { "未知歌手" }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(AccentSecondary.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.MusicNote,
                contentDescription = null,
                tint = TextTertiary,
                modifier = Modifier.size(22.dp)
            )
            artworkRequest?.let {
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${pluginSourceDisplayName(track.key.pluginId)} · $artistText",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        track.durationMs?.takeIf { it > 0L }?.let { duration ->
            val totalSeconds = duration / 1_000L
            Text(
                text = "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        PlaylistTrackMenu(
            onAddToPlaylist = onAddToPlaylist,
            onRemoveFromPlaylist = onRemoveFromPlaylist
        )
    }
    HorizontalDivider(color = DarkBorder, thickness = 0.5.dp, modifier = modifier)
}

@Composable
private fun PlaylistTrackMenu(
    onAddToPlaylist: (() -> Unit)?,
    onRemoveFromPlaylist: (() -> Unit)?
) {
    if (onAddToPlaylist == null && onRemoveFromPlaylist == null) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Outlined.MoreVert,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(18.dp)
        )
        return
    }

    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Outlined.MoreVert,
                contentDescription = "歌曲操作",
                tint = TextTertiary,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = GlassSurfaceStrong
        ) {
            onAddToPlaylist?.let { action ->
                DropdownMenuItem(
                    text = { Text("添加到歌单", color = TextPrimary) },
                    leadingIcon = {
                        Icon(
                            androidx.compose.material.icons.Icons.AutoMirrored.Outlined.PlaylistAdd,
                            contentDescription = null,
                            tint = Green500
                        )
                    },
                    onClick = {
                        expanded = false
                        action()
                    }
                )
            }
            onRemoveFromPlaylist?.let { action ->
                DropdownMenuItem(
                    text = { Text("从歌单中移除", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = {
                        Icon(
                            androidx.compose.material.icons.Icons.Outlined.DeleteOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                    },
                    onClick = {
                        expanded = false
                        action()
                    }
                )
            }
        }
    }
}

private fun PlaylistMutationResult.messageFor(playlistName: String): String = when (this) {
    PlaylistMutationResult.ADDED -> "已添加到“$playlistName”"
    PlaylistMutationResult.ALREADY_EXISTS -> "歌曲已在“$playlistName”中"
    PlaylistMutationResult.NOT_FOUND -> "歌单不存在，请重试"
}

private fun playlistArtworkRequest(context: Context, coverUrl: String?): ImageRequest? {
    val thumbnailUrl = albumArtworkThumbnailUrl(coverUrl) ?: return null
    return ImageRequest.Builder(context)
        .data(thumbnailUrl)
        .memoryCacheKey(albumArtworkThumbnailCacheKey(coverUrl))
        .size(PLAYLIST_ARTWORK_DECODE_SIZE_PX)
        .crossfade(120)
        .build()
}

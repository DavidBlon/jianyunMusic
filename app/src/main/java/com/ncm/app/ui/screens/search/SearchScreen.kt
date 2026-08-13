package com.ncm.app.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ncm.app.data.PlaylistMutationResult
import com.ncm.app.data.isUserPlaylistId
import com.ncm.app.data.model.Song
import com.ncm.app.plugin.model.OnlineTrack
import com.ncm.app.ui.components.AddToPlaylistDialog
import com.ncm.app.ui.components.LinglanSourceBadge
import com.ncm.app.ui.components.ArtistLinks
import com.ncm.app.ui.theme.*
import com.ncm.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    onSongClick: (Long) -> Unit,
    onPluginSongClick: (com.ncm.app.plugin.model.OnlineTrack) -> Unit,
    onArtistClick: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.searchState.collectAsState()
    val myState by viewModel.myState.collectAsState()
    val userPlaylists = remember(myState.playlists) {
        myState.playlists.filter { isUserPlaylistId(it.id) }
    }
    var query by rememberSaveable { mutableStateOf(state.query) }
    var songToAdd by remember { mutableStateOf<Song?>(null) }
    var onlineTrackToAdd by remember { mutableStateOf<OnlineTrack?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.loadMyData()
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            viewModel.clearSearch()
        } else {
            delay(400)
            if (trimmed == query.trim()) viewModel.search(trimmed)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        SearchInput(
            query = query,
            onQueryChange = { query = it },
            onSearch = { viewModel.submitSearch(query.trim()) },
            onClear = { query = ""; viewModel.clearSearch() }
        )

        if (query.isBlank()) {
            SearchLanding(
                history = state.history,
                onSearch = { term -> query = term; viewModel.submitSearch(term) },
                onRemoveHistory = viewModel::removeSearchHistory,
                onClearHistory = viewModel::clearSearchHistory
            )
        } else {
            val suggestions = remember(query, state.history) {
                state.history
                    .distinct()
                    .filter { it.contains(query.trim(), ignoreCase = true) && !it.equals(query.trim(), ignoreCase = true) }
                    .take(4)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
            ) {
                if (suggestions.isNotEmpty()) {
                    item { SearchSuggestions(suggestions) { term -> query = term; viewModel.submitSearch(term) } }
                }
                if (state.isSearching) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Green500, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                if (state.results.isNotEmpty()) {
                    item {
                        Text(
                            text = if (state.isCommitted) "搜索结果" else "实时匹配",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (state.isCommitted) Green500 else TextTertiary,
                            modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                }
                items(state.results, key = { it.id }) { song ->
                    SearchSongItem(
                        song = song,
                        showPlayNext = state.isCommitted,
                        showLinglanSource = state.isLinglanConfigured && song.fee != 0,
                        onClick = { onSongClick(song.id) },
                        onArtistClick = onArtistClick,
                        onPlayNext = { onPlayNext(song) },
                        onAddToPlaylist = { songToAdd = song }
                    )
                }
                if (state.isCommitted && state.pluginSourceLabel != null) {
                    item {
                        Text(
                            text = "在线来源：${state.pluginSourceLabel ?: "插件"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = Green500,
                            modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 4.dp)
                        )
                    }
                }
                items(state.pluginResults, key = { it.key.asComposite() }) { track ->
                    PluginSongItem(
                        track = track,
                        onClick = { onPluginSongClick(track) },
                        onAddToPlaylist = { onlineTrackToAdd = track }
                    )
                }
                if (!state.isCommitted && state.pluginSourceLabel != null && !state.isSearching) {
                    item {
                        Text(
                            text = "点击放大镜或按键盘搜索，查询${state.pluginSourceLabel}",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)
                        )
                    }
                }
                if (state.isCommitted && state.pluginSourceLabel != null && state.pluginError != null) {
                    item {
                        Text(
                            text = "在线来源搜索失败：${state.pluginError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)
                        )
                    }
                }
                if (state.isCommitted && state.pluginSourceLabel == null) {
                    item {
                        Text(
                            text = "未连接在线音乐来源：可到「设置 → 在线音乐来源」连接后搜索",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp)
                        )
                    }
                }
                if (!state.isSearching && state.results.isEmpty() && state.pluginResults.isEmpty() &&
                    state.isCommitted && state.pluginError == null
                ) {
                    item {
                        Text(
                            if (state.pluginSourceLabel != null && state.pluginError == null) {
                                "当前在线来源没有返回结果，请稍后重试或更换关键词"
                            } else {
                                "没有找到相关歌曲"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp)
                        )
                    }
                }
            }
        }
    }

    songToAdd?.let { song ->
        AddToPlaylistDialog(
            playlists = userPlaylists,
            onDismiss = { songToAdd = null },
            onPlaylistSelected = { playlist ->
                val result = viewModel.addSongToUserPlaylist(playlist.id, song)
                android.widget.Toast.makeText(context, result.searchMessage(playlist.name), android.widget.Toast.LENGTH_SHORT).show()
            },
            onCreatePlaylist = { name ->
                val playlistId = viewModel.createUserPlaylist(name)
                val result = playlistId?.let { viewModel.addSongToUserPlaylist(it, song) }
                    ?: PlaylistMutationResult.NOT_FOUND
                android.widget.Toast.makeText(context, result.searchMessage(name), android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }

    onlineTrackToAdd?.let { track ->
        AddToPlaylistDialog(
            playlists = userPlaylists,
            onDismiss = { onlineTrackToAdd = null },
            onPlaylistSelected = { playlist ->
                val result = viewModel.addOnlineTrackToUserPlaylist(playlist.id, track)
                android.widget.Toast.makeText(context, result.searchMessage(playlist.name), android.widget.Toast.LENGTH_SHORT).show()
            },
            onCreatePlaylist = { name ->
                val playlistId = viewModel.createUserPlaylist(name)
                val result = playlistId?.let { viewModel.addOnlineTrackToUserPlaylist(it, track) }
                    ?: PlaylistMutationResult.NOT_FOUND
                android.widget.Toast.makeText(context, result.searchMessage(name), android.widget.Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
private fun PluginSongItem(
    track: com.ncm.app.plugin.model.OnlineTrack,
    onClick: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    val coverUrl = track.artworkUrl ?: track.album?.artworkUrl
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .glassSurface(RoundedCornerShape(10.dp), elevation = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!coverUrl.isNullOrBlank()) {
                coil.compose.AsyncImage(
                    model = coverUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                track.artists.joinToString("/") { it.name },
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        track.durationMs?.takeIf { it > 0L }?.let { duration ->
            val totalSeconds = duration / 1_000L
            Text(
                text = "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L),
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiary
            )
        }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = "添加到歌单",
                tint = TextTertiary
            )
        }
    }
}

@Composable
private fun SearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp)
            .glassSurface(RoundedCornerShape(10.dp), elevation = 8.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onSearch,
            enabled = query.isNotBlank(),
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                androidx.compose.material.icons.Icons.Outlined.Search,
                contentDescription = "搜索",
                tint = if (query.isBlank()) TextTertiary else Green500,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(2.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
            cursorBrush = SolidColor(Green500),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    onSearch()
                    focusManager.clearFocus()
                    keyboardController?.hide()
                }
            ),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (query.isEmpty()) {
                        BasicText(
                            text = "搜索歌曲、歌手、专辑",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = TextTertiary,
                                background = Color.Unspecified
                            )
                        )
                    }
                    innerTextField()
                }
            }
        )
        if (query.isNotBlank()) {
            TextButton(onClick = onClear, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp)) {
                Text("清空", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun SearchLanding(
    history: List<String>,
    onSearch: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val uniqueHistory = history.distinctBy { it.lowercase() }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 18.dp,
            end = 20.dp,
            bottom = miniPlayerSafeBottomPadding()
        )
    ) {
        if (uniqueHistory.isNotEmpty()) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("搜索历史", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onClearHistory) { Text("清空", color = TextTertiary, style = MaterialTheme.typography.bodySmall) }
                }
            }
            items(uniqueHistory, key = { "history:$it" }) { term ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(term, color = TextSecondary, modifier = Modifier.weight(1f).clickable { onSearch(term) }.padding(vertical = 8.dp))
                    TextButton(onClick = { onRemoveHistory(term) }, contentPadding = PaddingValues(4.dp)) { Text("×", color = TextTertiary) }
                }
            }
            item { Spacer(Modifier.height(18.dp)) }
        }
    }
}

@Composable
private fun SearchSuggestions(suggestions: List<String>, onClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        suggestions.forEach { term ->
            Text(term, color = TextSecondary, modifier = Modifier.fillMaxWidth().clickable { onClick(term) }.padding(vertical = 8.dp))
        }
    }
}

@Composable
private fun SearchSongItem(
    song: Song,
    showPlayNext: Boolean,
    showLinglanSource: Boolean,
    onClick: () -> Unit,
    onArtistClick: (Long) -> Unit,
    onPlayNext: () -> Unit,
    onAddToPlaylist: () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(song.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(
                modifier = Modifier.padding(top = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showLinglanSource) {
                    LinglanSourceBadge()
                    Spacer(modifier = Modifier.width(6.dp))
                }
                ArtistLinks(
                    artists = song.artists,
                    onArtistClick = onArtistClick,
                    suffix = song.album?.name
                        ?.takeIf { it.isNotBlank() }
                        ?.let { " - $it" }
                        .orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (showPlayNext) IconButton(onClick = onPlayNext) { Text("+", color = Green500, fontSize = 26.sp) }
        IconButton(onClick = onAddToPlaylist) {
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Outlined.PlaylistAdd,
                contentDescription = "添加到歌单",
                tint = TextTertiary,
                modifier = Modifier.size(22.dp)
            )
        }
        Icon(androidx.compose.material.icons.Icons.Outlined.PlayCircle, null, tint = TextTertiary, modifier = Modifier.size(24.dp))
    }
    HorizontalDivider(color = DarkBorder, thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
}

private fun PlaylistMutationResult.searchMessage(playlistName: String): String = when (this) {
    PlaylistMutationResult.ADDED -> "已添加到“$playlistName”"
    PlaylistMutationResult.ALREADY_EXISTS -> "歌曲已在“$playlistName”中"
    PlaylistMutationResult.NOT_FOUND -> "歌单不存在，请重试"
}

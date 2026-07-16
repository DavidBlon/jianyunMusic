package com.ncm.app.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ncm.app.data.model.Song
import com.ncm.app.ui.theme.*
import com.ncm.app.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    onSongClick: (Long) -> Unit,
    onPlayNext: (Song) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.searchState.collectAsState()
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        viewModel.clearSearch()
    }
    DisposableEffect(Unit) { onDispose { viewModel.clearSearch() } }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            viewModel.clearSearch()
        } else {
            delay(400)
            if (trimmed == query.trim()) viewModel.search(trimmed)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(DarkBg)) {
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
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 92.dp)) {
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
                        onClick = { onSongClick(song.id) },
                        onPlayNext = { onPlayNext(song) }
                    )
                }
            }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)
            .background(DarkBg3, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(androidx.compose.material.icons.Icons.Outlined.Search, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        TextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("搜索歌曲、歌手、专辑", style = MaterialTheme.typography.bodyMedium, color = TextTertiary) },
            modifier = Modifier.weight(1f), singleLine = true,
            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, cursorColor = Green500, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() })
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
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp)) {
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
private fun SearchSongItem(song: Song, showPlayNext: Boolean, onClick: () -> Unit, onPlayNext: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(song.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${song.artistText} - ${song.album?.name ?: ""}", style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
        if (showPlayNext) IconButton(onClick = onPlayNext) { Text("+", color = Green500, fontSize = 26.sp) }
        Icon(androidx.compose.material.icons.Icons.Outlined.PlayCircle, null, tint = TextTertiary, modifier = Modifier.size(24.dp))
    }
    HorizontalDivider(color = DarkBorder, thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
}

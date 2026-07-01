package com.ncm.app.ui.screens.quick

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.QuickEntry
import com.ncm.app.ui.theme.*
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel

@Composable
fun QuickListScreen(
    type: String,
    onBack: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onSongClick: (Long) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.quickListState.collectAsState()

    LaunchedEffect(type, state.loadedType) {
        viewModel.loadQuickList(type)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    tint = TextPrimary
                )
            }
            Text(state.title, style = MaterialTheme.typography.headlineSmall, color = TextPrimary)
        }

        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Green500)
            }
        } else if (state.entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(state.error ?: "暂无内容", style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 90.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.entries) { entry ->
                    QuickEntryRow(
                        entry = entry,
                        isSong = type == "fm",
                        onClick = {
                            if (type == "fm") onSongClick(entry.id) else onPlaylistClick(entry.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickEntryRow(
    entry: QuickEntry,
    isSong: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkSurface),
            contentAlignment = Alignment.Center
        ) {
            if (!entry.imageUrl.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(entry.imageUrl, 140), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else if (isSong) {
                Icon(androidx.compose.material.icons.Icons.Outlined.PlayArrow, null, tint = TextTertiary)
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.subtitle.isNotBlank()) {
                Text(entry.subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

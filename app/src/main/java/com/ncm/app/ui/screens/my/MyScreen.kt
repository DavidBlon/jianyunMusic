package com.ncm.app.ui.screens.my

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.Playlist
import com.ncm.app.data.model.UserProfile
import com.ncm.app.ui.theme.*
import com.ncm.app.viewmodel.MainViewModel

@Composable
fun MyScreen(
    onPlaylistClick: (Long) -> Unit,
    onSongClick: (Long) -> Unit,
    onLogout: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.myState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadMyData()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
        contentPadding = PaddingValues(bottom = 92.dp)
    ) {
        item { ProfileHeader(profile = state.profile) }
        item { Spacer(modifier = Modifier.height(8.dp)) }

        when {
            state.isLoading -> {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Green500, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }

            state.playlists.isEmpty() -> {
                item {
                    Text("暂无歌单", style = MaterialTheme.typography.bodyMedium, color = TextTertiary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
                }
            }

            else -> {
                items(state.playlists, key = { it.id }) { playlist ->
                    MyPlaylistItem(playlist = playlist, onClick = { onPlaylistClick(playlist.id) })
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Text("退出登录", color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(56.dp).clip(CircleShape).background(DarkSurface),
            contentAlignment = Alignment.Center
        ) {
            if (!profile?.avatar.isNullOrBlank()) {
                AsyncImage(profile?.avatar, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(androidx.compose.material.icons.Icons.Outlined.Person, null, tint = TextPrimary, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column {
            Text(profile?.nickname ?: "网易云用户", style = MaterialTheme.typography.headlineSmall, color = TextPrimary, fontWeight = FontWeight.SemiBold)
            if ((profile?.vipType ?: 0) > 0) {
                Text("VIP 会员", style = MaterialTheme.typography.bodySmall, color = Green500, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable
private fun MyPlaylistItem(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(DarkSurface), contentAlignment = Alignment.Center) {
            if (!playlist.cover.isNullOrBlank()) {
                AsyncImage(playlist.cover, null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.QueueMusic, null, tint = TextTertiary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text("${playlist.trackCount} 首", style = MaterialTheme.typography.bodySmall, color = TextTertiary, modifier = Modifier.padding(top = 2.dp))
        }
        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
    }
}

package com.ncm.app.ui.screens.weekly

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.Song
import com.ncm.app.domain.weekly.CachedSong
import com.ncm.app.domain.weekly.WeeklyRecUiMapper
import com.ncm.app.domain.weekly.WeeklyRecUiState
import com.ncm.app.ui.theme.GlassSurface
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextTertiary
import com.ncm.app.ui.theme.glassSurface
import com.ncm.app.ui.theme.miniPlayerSafeBottomPadding
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.PlayerViewModel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyRecommendationScreen(
    onBack: () -> Unit,
    onOpenPlayer: (Long) -> Unit,
    playerViewModel: PlayerViewModel,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.weeklyRecState.collectAsState()
    val detailSongs by viewModel.weeklyDetailSongs.collectAsState()
    val detailLoading by viewModel.weeklyDetailLoading.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.cleanupWeeklyCacheOnPageOpen()
        viewModel.loadWeeklyRecommendation()
        // 状态落定为 Success 后再静默水合（供点击直接播放）；非 Success 则不水合。
        snapshotFlow { viewModel.weeklyRecState.value }
            .filterIsInstance<WeeklyRecUiState.Success>()
            .first()
        viewModel.hydrateWeeklyDetailSongsNow()
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("每周推荐", color = TextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        when (val current = state) {
            is WeeklyRecUiState.Loading -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Green500, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                }
            }

            is WeeklyRecUiState.InsufficientData -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        "本周听歌数据不足，多听几首下周再来",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary
                    )
                }
            }

            is WeeklyRecUiState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(current.message, style = MaterialTheme.typography.bodyMedium, color = TextTertiary)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { viewModel.loadWeeklyRecommendation() }) {
                        Text("重试")
                    }
                }
            }

            is WeeklyRecUiState.Success -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
                ) {
                    item { WeeklyHeader(current) }
                    itemsIndexed(current.songs, key = { _, song -> song.songId }) { _, song ->
                        WeeklySongRow(
                            song = song,
                            onClick = {
                                val full = detailSongs
                                when {
                                    full.isNullOrEmpty() && detailLoading ->
                                        Toast.makeText(context, "正在加载歌曲…", Toast.LENGTH_SHORT).show()
                                    full.isNullOrEmpty() ->
                                        scope.launch {
                                            val loaded = viewModel.hydrateWeeklyDetailSongsNow()
                                            if (loaded.isNullOrEmpty()) {
                                                Toast.makeText(context, "歌曲加载失败，请重试", Toast.LENGTH_SHORT).show()
                                            } else {
                                                playWeekly(loaded, song.songId, playerViewModel, onOpenPlayer)
                                            }
                                        }
                                    else -> playWeekly(full, song.songId, playerViewModel, onOpenPlayer)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeeklyHeader(state: WeeklyRecUiState.Success) {
    val cover = state.songs.firstOrNull()?.cover
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(GlassSurface),
            contentAlignment = Alignment.Center
        ) {
            if (!cover.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(cover, 200), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Outlined.AutoAwesome, null, tint = Green500, modifier = Modifier.size(30.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("每周推荐 · ${state.displayWeekLabel}", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text(
                WeeklyRecUiMapper.successSubtitle(state.seedCount, state.songs.size),
                style = MaterialTheme.typography.bodyMedium,
                color = TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun WeeklySongRow(song: CachedSong, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .glassSurface(RoundedCornerShape(8.dp), elevation = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!song.cover.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(song.cover, 140), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Outlined.MusicNote, null, tint = TextTertiary, modifier = Modifier.size(24.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                song.artists.joinToString(" / "),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TextTertiary, modifier = Modifier.size(18.dp))
    }
}

private fun playWeekly(
    songs: List<Song>,
    songId: Long,
    playerViewModel: PlayerViewModel,
    onOpenPlayer: (Long) -> Unit
) {
    val startIndex = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0)
    playerViewModel.setQueue(songs, startIndex)
    onOpenPlayer(songId)
}

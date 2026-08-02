package com.ncm.app.ui.screens.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.ncm.app.data.model.ArtistDetail
import com.ncm.app.data.model.Song
import com.ncm.app.ui.theme.AccentSecondary
import com.ncm.app.ui.theme.DarkBg
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextSecondary
import com.ncm.app.ui.theme.TextTertiary
import com.ncm.app.ui.theme.accentSurface
import com.ncm.app.ui.theme.glassSurface
import com.ncm.app.ui.theme.miniPlayerSafeBottomPadding
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.util.albumArtworkThumbnailCacheKey
import com.ncm.app.util.albumArtworkThumbnailUrl
import com.ncm.app.viewmodel.MainViewModel

@Composable
fun ArtistDetailScreen(
    artistId: Long,
    onBack: () -> Unit,
    onSongClick: (Long) -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.artistDetailState.collectAsState()

    LaunchedEffect(artistId) {
        viewModel.loadArtistDetail(artistId)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg),
        contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
    ) {
        item {
            ArtistHeader(
                artist = state.artist,
                onBack = onBack
            )
        }

        when {
            state.isLoading && state.artist == null -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Green500)
                    }
                }
            }

            state.error != null && state.artist == null -> {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            state.error.orEmpty(),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = { viewModel.loadArtistDetail(artistId, force = true) },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("重新加载", color = Green500)
                        }
                    }
                }
            }

            else -> {
                state.artist?.let { artist ->
                    item {
                        ArtistBiography(artist)
                    }
                    item {
                        ArtistWorksHeader(
                            songCount = artist.hotSongs.size,
                            onPlayAll = {
                                artist.hotSongs.firstOrNull()?.let { onSongClick(it.id) }
                            }
                        )
                    }
                    itemsIndexed(
                        items = artist.hotSongs,
                        key = { _, song -> song.id }
                    ) { index, song ->
                        ArtistSongItem(
                            index = index + 1,
                            song = song,
                            onClick = { onSongClick(song.id) }
                        )
                    }
                    if (artist.hotSongs.isEmpty()) {
                        item {
                            Text(
                                "暂无热门作品",
                                color = TextTertiary,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 28.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(
    artist: ArtistDetail?,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(350.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        AccentSecondary.copy(alpha = 0.55f),
                        DarkBg
                    )
                )
            )
    ) {
        if (!artist?.avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = sizedImageUrl(artist?.avatarUrl, 900),
                contentDescription = artist?.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                alpha = 0.48f
            )
        }
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0x52000000),
                            Color(0xC712161C),
                            DarkBg
                        )
                    )
                )
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 8.dp)
                .statusBarsPadding()
                .background(Color(0x52000000), CircleShape)
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = Color.White
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .glassSurface(CircleShape, elevation = 12.dp, strong = true)
                        .padding(3.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (!artist?.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = sizedImageUrl(artist?.avatarUrl, 260),
                            contentDescription = artist?.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = androidx.compose.material.icons.Icons.Outlined.MusicNote,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp, bottom = 4.dp)
                ) {
                    Text(
                        artist?.name ?: "歌手",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    artist?.aliases
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { aliases ->
                            Text(
                                aliases.joinToString(" / "),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.72f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                }
            }

            artist?.let {
                Row(
                    modifier = Modifier.padding(top = 18.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ArtistStat("${it.musicCount}", "单曲")
                    ArtistStat("${it.albumCount}", "专辑")
                    ArtistStat("${it.mvCount}", "MV")
                }
            }
        }
    }
}

@Composable
private fun ArtistStat(value: String, label: String) {
    Column(
        modifier = Modifier
            .glassSurface(RoundedCornerShape(14.dp), elevation = 4.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            value,
            color = TextPrimary,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            label,
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@Composable
private fun ArtistBiography(artist: ArtistDetail) {
    val description = artist.fullDescription.ifBlank { artist.briefDescription }
    if (description.isBlank()) return
    val descriptionScrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .glassSurface(RoundedCornerShape(20.dp), elevation = 8.dp)
            .padding(16.dp)
    ) {
        Text(
            "歌手简介",
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            description,
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 150.dp)
                .padding(top = 10.dp)
                .verticalScroll(descriptionScrollState)
        )
        if (descriptionScrollState.maxValue > 0) {
            Text(
                "上下滑动阅读完整介绍",
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun ArtistWorksHeader(
    songCount: Int,
    onPlayAll: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "热门作品",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "$songCount 首",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Button(
            onClick = onPlayAll,
            enabled = songCount > 0,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.accentSurface(RoundedCornerShape(18.dp))
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(17.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text("播放")
        }
    }
}

@Composable
private fun ArtistSongItem(
    index: Int,
    song: Song,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val artworkRequest = remember(context, song.album?.picUrl) {
        val thumbnailUrl = albumArtworkThumbnailUrl(song.album?.picUrl) ?: return@remember null
        ImageRequest.Builder(context)
            .data(thumbnailUrl)
            .memoryCacheKey(albumArtworkThumbnailCacheKey(song.album?.picUrl))
            .size(160)
            .crossfade(120)
            .build()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            index.toString().padStart(2, '0'),
            color = TextTertiary,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            textAlign = TextAlign.End,
            modifier = Modifier.width(40.dp)
        )
        if (artworkRequest != null) {
            AsyncImage(
                model = artworkRequest,
                contentDescription = null,
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .background(AccentSecondary.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Outlined.MusicNote,
                    contentDescription = null,
                    tint = TextTertiary
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                song.name,
                color = TextPrimary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOf(song.artistText, song.album?.name.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Text(
            song.durationText,
            color = TextTertiary,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

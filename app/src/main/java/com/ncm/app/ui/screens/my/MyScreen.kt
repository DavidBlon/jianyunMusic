package com.ncm.app.ui.screens.my

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Person
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.ncm.app.data.model.Playlist
import com.ncm.app.data.model.UserProfile
import com.ncm.app.NeteaseApp
import com.ncm.app.data.cache.LINGLAN_AUDIO_CACHE_MAX_MIB
import com.ncm.app.ui.theme.*
import com.ncm.app.ui.components.MusicSourceKeySettingsSheet
import com.ncm.app.ui.screens.settings.OnlineMusicSourceSection
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.domain.weekly.WEEKLY_PLAYLIST_ID
import com.ncm.app.domain.weekly.weeklyRow
import com.ncm.app.viewmodel.LinglanCacheUiState
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.OnlineMusicSourceViewModel
import com.ncm.app.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen(
    onPlaylistClick: (Long) -> Unit,
    onSongClick: (Long) -> Unit,
    onDisclaimerClick: () -> Unit,
    playerViewModel: PlayerViewModel,
    onlineSourceViewModel: OnlineMusicSourceViewModel,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.myState.collectAsState()
    val playerState by playerViewModel.state.collectAsState()
    val accentTheme by NeteaseApp.instance.accentThemeSettings.theme.collectAsState()
    val appearanceSettings = NeteaseApp.instance.playerAppearanceSettings
    val musicSourceSettings = NeteaseApp.instance.musicSourceSettings
    val customBackground by appearanceSettings.customBackground.collectAsState()
    val applyCustomBackgroundGlobally by appearanceSettings.applyCustomBackgroundGlobally.collectAsState()
    val musicSourceKey by musicSourceSettings.cardKey.collectAsState()
    val backgroundStatus = when {
        customBackground == null -> "跟随主题"
        applyCustomBackgroundGlobally -> "已应用全局"
        else -> "仅播放页"
    }
    var showAppearanceSettings by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showBackgroundSettings by remember { mutableStateOf(false) }
    var showMusicSourceSettings by remember { mutableStateOf(false) }
    var showOnlineSourceSettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // The system photo picker may already own the long-lived grant.
        }
        appearanceSettings.setCustomBackground(uri.toString(), context.contentResolver.getType(uri))
    }

    LaunchedEffect(Unit) {
        viewModel.loadMyData()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
    ) {
        item { ProfileHeader(profile = state.profile) }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item {
            SettingsEntry(
                onClick = {
                    playerViewModel.refreshLinglanCacheStats()
                    showAppearanceSettings = true
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // P6T3：在线推荐入口已隐藏；这里只保留本地收藏统计（spec §18）
        item {
            LocalLibraryCard(likedCount = state.likedCount)
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                "在线音乐来源与更多设置见「设置」",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
        }
    }

    if (showAppearanceSettings) {
        SettingsSheet(
            themeName = accentTheme.label,
            backgroundStatus = backgroundStatus,
            musicSourceKeyStatus = musicSourceKey
                .takeIf { it.isNotBlank() }
                ?.let { "••••${it.takeLast(4)}" }
                ?: "未配置",
            cacheState = playerState.linglanCache,
            onBackgroundClick = {
                showAppearanceSettings = false
                showBackgroundSettings = true
            },
            onThemeClick = {
                showAppearanceSettings = false
                showThemePicker = true
            },
            onMusicSourceKeyClick = {
                showAppearanceSettings = false
                showMusicSourceSettings = true
            },
            onOnlineSourceClick = {
                showAppearanceSettings = false
                showOnlineSourceSettings = true
            },
            onDisclaimerClick = {
                showAppearanceSettings = false
                onDisclaimerClick()
            },
            onClearCache = playerViewModel::clearLinglanCache,
            onDismiss = { showAppearanceSettings = false }
        )
    }

    if (showThemePicker) {
        ModalBottomSheet(onDismissRequest = { showThemePicker = false }, containerColor = GlassSurfaceStrong) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text("主题设置", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                AccentTheme.entries.forEach { theme ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            NeteaseApp.instance.accentThemeSettings.setTheme(theme)
                            showThemePicker = false
                        }.padding(vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(theme.highlight, theme.color, theme.secondary)
                                    )
                                )
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(theme.label, modifier = Modifier.weight(1f), color = TextPrimary)
                        if (theme == accentTheme) Text("当前", color = Green500, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }

    if (showBackgroundSettings) {
        BackgroundSettingsSheet(
            media = customBackground,
            applyGlobally = applyCustomBackgroundGlobally,
            onApplyGloballyChange = appearanceSettings::setApplyCustomBackgroundGlobally,
            onPickMedia = {
                mediaPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            },
            onClearMedia = appearanceSettings::clearCustomBackground,
            onDismiss = { showBackgroundSettings = false }
        )
    }

    if (showMusicSourceSettings) {
        MusicSourceKeySettingsSheet(
            currentMaskedKey = musicSourceKey
                .takeIf { it.isNotBlank() }
                ?.let { "••••${it.takeLast(4)}" },
            onValidateAndSave = viewModel::validateAndSaveMusicSourceKey,
            onClearKey = viewModel::clearMusicSourceKey,
            onDismiss = { showMusicSourceSettings = false }
        )
    }

    if (showOnlineSourceSettings) {
        ModalBottomSheet(
            onDismissRequest = { showOnlineSourceSettings = false },
            containerColor = GlassSurfaceStrong
        ) {
            OnlineMusicSourceSection(viewModel = onlineSourceViewModel)
        }
    }
}

@Composable
private fun SettingsEntry(
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .glassSurface(RoundedCornerShape(18.dp), elevation = 8.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "设置",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text("主题、背景、音源与缓存", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        Spacer(Modifier.width(6.dp))
        Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun SettingsSheet(
    themeName: String,
    backgroundStatus: String,
    musicSourceKeyStatus: String,
    cacheState: LinglanCacheUiState,
    onBackgroundClick: () -> Unit,
    onThemeClick: () -> Unit,
    onMusicSourceKeyClick: () -> Unit,
    onOnlineSourceClick: () -> Unit,
    onDisclaimerClick: () -> Unit,
    onClearCache: () -> Unit,
    onDismiss: () -> Unit
) {
    var showClearConfirmation by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GlassSurfaceStrong
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                "设置",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            AppearanceSettingRow(
                label = "背景设置",
                value = backgroundStatus,
                onClick = onBackgroundClick
            )
            AppearanceSettingRow(
                label = "主题设置",
                value = themeName,
                onClick = onThemeClick
            )
            AppearanceSettingRow(
                label = "备用音源卡密",
                value = musicSourceKeyStatus,
                onClick = onMusicSourceKeyClick
            )
            AppearanceSettingRow(
                label = "在线音乐来源",
                value = "连接聆澜 · 单选来源",
                onClick = onOnlineSourceClick
            )
            AppearanceSettingRow(
                label = "免责声明",
                value = "第三方服务与推广说明",
                onClick = onDisclaimerClick
            )
            HorizontalDivider(
                color = TextTertiary.copy(alpha = 0.16f),
                modifier = Modifier.padding(vertical = 10.dp)
            )
            LinglanCacheSetting(
                state = cacheState,
                onClearClick = { showClearConfirmation = true }
            )
        }
    }

    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text("清空聆澜缓存？") },
            text = {
                Text(
                    "将删除已保存的聆澜音频和播放地址。若当前歌曲只能依靠缓存播放，清理后会停止；不会自动消耗一次新的聆澜请求。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirmation = false
                        onClearCache()
                    }
                ) {
                    Text("确认清空", color = RedAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmation = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun LinglanCacheSetting(
    state: LinglanCacheUiState,
    onClearClick: () -> Unit
) {
    Surface(
        color = DarkSurface2,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "聆澜缓存",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (state.isLoading) {
                            "正在统计缓存…"
                        } else {
                            "${state.songCount} 首 · ${formatLinglanCacheSize(state.sizeBytes)}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                OutlinedButton(
                    onClick = onClearClick,
                    enabled = !state.isLoading &&
                        !state.isClearing &&
                        (state.songCount > 0 || state.sizeBytes > 0),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RedAccent),
                    modifier = Modifier.padding(start = 12.dp)
                ) {
                    if (state.isClearing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("清空缓存")
                    }
                }
            }
            Text(
                "仅缓存聆澜音频（上限 $LINGLAN_AUDIO_CACHE_MAX_MIB MB，系统空间不足时可能自动清理）；" +
                    "网易云与酷狗始终直连，不写入缓存。",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 8.dp)
            )
            state.message?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private fun formatLinglanCacheSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val kilobytes = bytes / 1024.0
    if (kilobytes < 1024) return "${kilobytes.toInt()} KB"
    val megabytes = kilobytes / 1024.0
    if (megabytes < 1024) {
        return String.format(java.util.Locale.getDefault(), "%.1f MB", megabytes)
    }
    return String.format(java.util.Locale.getDefault(), "%.2f GB", megabytes / 1024.0)
}

@Composable
private fun AppearanceSettingRow(
    label: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        Spacer(Modifier.width(6.dp))
        Icon(
            androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = TextTertiary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun BackgroundSettingsSheet(
    media: PlayerCustomBackground?,
    applyGlobally: Boolean,
    onApplyGloballyChange: (Boolean) -> Unit,
    onPickMedia: () -> Unit,
    onClearMedia: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = GlassSurfaceStrong
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .padding(bottom = 24.dp)
        ) {
            Text("背景设置", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Text(
                "选择照片或视频作为播放页背景，也可以手动扩展到整个应用。",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
            )
            BackgroundMediaCard(
                media = media,
                onPickMedia = onPickMedia,
                onClearMedia = onClearMedia
            )
            GlobalBackgroundToggle(
                checked = applyGlobally,
                enabled = media != null,
                onCheckedChange = onApplyGloballyChange,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}

@Composable
private fun BackgroundMediaCard(
    media: PlayerCustomBackground?,
    onPickMedia: () -> Unit,
    onClearMedia: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(18.dp), elevation = 8.dp)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                AccentHighlight.copy(alpha = 0.20f),
                                Green500.copy(alpha = 0.18f),
                                AccentSecondary.copy(alpha = 0.18f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (media?.type == PlayerCustomMediaType.IMAGE) {
                    AsyncImage(
                        model = media.uri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = if (media?.type == PlayerCustomMediaType.VIDEO) {
                            androidx.compose.material.icons.Icons.Outlined.Movie
                        } else {
                            androidx.compose.material.icons.Icons.Outlined.Image
                        },
                        contentDescription = null,
                        tint = Green500,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(
                    text = when (media?.type) {
                        PlayerCustomMediaType.IMAGE -> "相册照片"
                        PlayerCustomMediaType.VIDEO -> "静音循环视频"
                        null -> "尚未选择背景"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (media == null) "支持照片与视频" else "已保存，下次启动继续使用",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            if (media != null) {
                IconButton(onClick = onClearMedia) {
                    Icon(
                        androidx.compose.material.icons.Icons.Outlined.DeleteOutline,
                        contentDescription = "移除自定义背景",
                        tint = TextSecondary
                    )
                }
            }
        }
        Button(
            onClick = onPickMedia,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .heightIn(min = 46.dp)
                .accentSurface(RoundedCornerShape(14.dp)),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent)
        ) {
            Text(if (media == null) "从相册选择" else "更换照片或视频")
        }
    }
}

@Composable
private fun GlobalBackgroundToggle(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .glassSurface(RoundedCornerShape(18.dp), elevation = 6.dp)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                "应用到全局背景",
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) TextPrimary else TextTertiary,
                fontWeight = FontWeight.Medium
            )
            Text(
                when {
                    !enabled -> "选择背景后可开启"
                    checked -> "发现、搜索和我的页面都会使用"
                    else -> "关闭时仅应用到播放页"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Green500
            )
        )
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .glassSurface(CircleShape, elevation = 8.dp)
                .padding(2.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (!profile?.avatar.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(profile?.avatar, 140), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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
private fun LocalLibraryCard(likedCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .glassSurface(RoundedCornerShape(18.dp), elevation = 8.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.FavoriteBorder,
            contentDescription = null,
            tint = Green500,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            "本地收藏",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Text("$likedCount 首", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}

@Composable
private fun MyPlaylistItem(playlist: Playlist, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .glassSurface(RoundedCornerShape(8.dp), elevation = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            if (!playlist.cover.isNullOrBlank()) {
                AsyncImage(sizedImageUrl(playlist.cover, 140), null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
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

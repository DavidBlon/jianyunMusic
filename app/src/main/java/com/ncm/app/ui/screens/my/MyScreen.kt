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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Movie
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
import com.ncm.app.NeteaseApp
import com.ncm.app.data.isUserPlaylistId
import com.ncm.app.ui.components.CreatePlaylistDialog
import com.ncm.app.ui.theme.*
import com.ncm.app.ui.screens.settings.OnlineMusicSourceSection
import com.ncm.app.util.sizedImageUrl
import com.ncm.app.viewmodel.MainViewModel
import com.ncm.app.viewmodel.OnlineMusicSourceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScreen(
    onPlaylistClick: (Long) -> Unit,
    onDisclaimerClick: () -> Unit,
    onlineSourceViewModel: OnlineMusicSourceViewModel,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.myState.collectAsState()
    val accentTheme by NeteaseApp.instance.accentThemeSettings.theme.collectAsState()
    val appearanceSettings = NeteaseApp.instance.playerAppearanceSettings
    val customBackground by appearanceSettings.customBackground.collectAsState()
    val applyCustomBackgroundGlobally by appearanceSettings.applyCustomBackgroundGlobally.collectAsState()
    val backgroundStatus = when {
        customBackground == null -> "跟随主题"
        applyCustomBackgroundGlobally -> "已应用全局"
        else -> "仅播放页"
    }
    var showAppearanceSettings by remember { mutableStateOf(false) }
    var showThemePicker by remember { mutableStateOf(false) }
    var showBackgroundSettings by remember { mutableStateOf(false) }
    var showOnlineSourceSettings by remember { mutableStateOf(false) }
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
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
        viewModel.loadMyData(force = true)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(bottom = miniPlayerSafeBottomPadding())
    ) {
        item {
            Text(
                "我的音乐",
                style = MaterialTheme.typography.headlineLarge,
                color = TextPrimary,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 8.dp)
            )
        }
        item {
            SettingsEntry(
                onClick = { showAppearanceSettings = true }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "我的歌单",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showCreatePlaylist = true }) {
                    Icon(
                        androidx.compose.material.icons.Icons.Outlined.Add,
                        contentDescription = "新建歌单",
                        tint = Green500
                    )
                }
            }
        }
        items(state.playlists, key = { it.id }) { playlist ->
            MyPlaylistItem(
                playlist = playlist,
                onClick = { onPlaylistClick(playlist.id) },
                onDelete = if (isUserPlaylistId(playlist.id)) {
                    { playlistToDelete = playlist }
                } else {
                    null
                }
            )
        }
        item { Spacer(modifier = Modifier.height(20.dp)) }
    }

    if (showAppearanceSettings) {
        SettingsSheet(
            themeName = accentTheme.label,
            backgroundStatus = backgroundStatus,
            onBackgroundClick = {
                showAppearanceSettings = false
                showBackgroundSettings = true
            },
            onThemeClick = {
                showAppearanceSettings = false
                showThemePicker = true
            },
            onOnlineSourceClick = {
                showAppearanceSettings = false
                showOnlineSourceSettings = true
            },
            onDisclaimerClick = {
                showAppearanceSettings = false
                onDisclaimerClick()
            },
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


    if (showOnlineSourceSettings) {
        ModalBottomSheet(
            onDismissRequest = { showOnlineSourceSettings = false },
            containerColor = GlassSurfaceStrong
        ) {
            OnlineMusicSourceSection(viewModel = onlineSourceViewModel)
        }
    }

    if (showCreatePlaylist) {
        CreatePlaylistDialog(
            onDismiss = { showCreatePlaylist = false },
            onCreate = viewModel::createUserPlaylist
        )
    }

    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            containerColor = GlassSurfaceStrong,
            title = { Text("删除歌单", color = TextPrimary) },
            text = { Text("确定删除“${playlist.name}”吗？歌单内的歌曲不会从设备或收藏中删除。", color = TextSecondary) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteUserPlaylist(playlist.id)
                        playlistToDelete = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) {
                    Text("取消", color = TextSecondary)
                }
            }
        )
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
        Text("主题、背景与音源", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
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
    onBackgroundClick: () -> Unit,
    onThemeClick: () -> Unit,
    onOnlineSourceClick: () -> Unit,
    onDisclaimerClick: () -> Unit,
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
                label = "在线音乐来源",
                value = "连接聆澜 · 单选来源",
                onClick = onOnlineSourceClick
            )
            AppearanceSettingRow(
                label = "免责声明",
                value = "第三方服务与推广说明",
                onClick = onDisclaimerClick
            )
        }
    }
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
private fun MyPlaylistItem(
    playlist: Playlist,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?
) {
    var showMenu by remember { mutableStateOf(false) }
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
        onDelete?.let { deletePlaylist ->
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        androidx.compose.material.icons.Icons.Outlined.MoreVert,
                        contentDescription = "歌单操作",
                        tint = TextTertiary
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = GlassSurfaceStrong
                ) {
                    DropdownMenuItem(
                        text = { Text("删除歌单", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                androidx.compose.material.icons.Icons.Outlined.DeleteOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            showMenu = false
                            deletePlaylist()
                        }
                    )
                }
            }
        }
    }
}

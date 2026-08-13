package com.ncm.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ncm.app.data.UserPlaylistStore
import com.ncm.app.data.model.Playlist
import com.ncm.app.ui.theme.DarkBorder
import com.ncm.app.ui.theme.GlassSurfaceStrong
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextSecondary
import com.ncm.app.ui.theme.TextTertiary

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val normalizedName = name.trim()
    val isValid = normalizedName.isNotBlank() && name.length <= UserPlaylistStore.MAX_PLAYLIST_NAME_LENGTH

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassSurfaceStrong,
        title = { Text("新建歌单", color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { if (it.length <= UserPlaylistStore.MAX_PLAYLIST_NAME_LENGTH) name = it },
                label = { Text("歌单名称") },
                supportingText = {
                    Text("${name.length}/${UserPlaylistStore.MAX_PLAYLIST_NAME_LENGTH}")
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onCreate(normalizedName)
                    onDismiss()
                },
                enabled = isValid
            ) {
                Text("创建", color = if (isValid) Green500 else TextTertiary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

@Composable
fun AddToPlaylistDialog(
    playlists: List<Playlist>,
    onDismiss: () -> Unit,
    onPlaylistSelected: (Playlist) -> Unit,
    onCreatePlaylist: (String) -> Unit
) {
    var isCreating by remember { mutableStateOf(false) }
    if (isCreating) {
        CreatePlaylistDialog(
            onDismiss = { isCreating = false },
            onCreate = { name ->
                onCreatePlaylist(name)
                onDismiss()
            }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = GlassSurfaceStrong,
        title = { Text("添加到歌单", color = TextPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (playlists.isEmpty()) {
                    Text(
                        "还没有自建歌单，先创建一个吧。",
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(playlists, key = Playlist::id) { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onPlaylistSelected(playlist)
                                        onDismiss()
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    androidx.compose.material.icons.Icons.AutoMirrored.Outlined.QueueMusic,
                                    contentDescription = null,
                                    tint = Green500,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        playlist.name,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text("${playlist.trackCount} 首", color = TextTertiary)
                                }
                            }
                            HorizontalDivider(color = DarkBorder, thickness = 0.5.dp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { isCreating = true }) {
                Icon(
                    androidx.compose.material.icons.Icons.Outlined.Add,
                    contentDescription = null,
                    tint = Green500,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("新建歌单", color = Green500)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = TextSecondary) }
        }
    )
}

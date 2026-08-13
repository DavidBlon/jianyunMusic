package com.ncm.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.ui.components.MusicSourceKeyEditor
import com.ncm.app.ui.components.MusicSourceShopButton
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextSecondary
import com.ncm.app.ui.theme.TextTertiary
import com.ncm.app.viewmodel.OnlineMusicSourceViewModel

/** 设置页「在线音乐来源」区块（spec §10）：连接聆澜 → 单选一个来源，不下载脚本。 */
@Composable
fun OnlineMusicSourceSection(viewModel: OnlineMusicSourceViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("在线音乐来源", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        MusicSourceShopButton(modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))

        state.maskedSecret?.let {
            Text(
                "当前密钥：$it（仅显示末四位）",
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Text(
            if (state.maskedSecret == null) {
                "快速粘贴购买后的密钥，将自动识别并验证"
            } else {
                "需要更换时直接快速粘贴新密钥，验证通过后才会启用"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextTertiary,
            modifier = Modifier.padding(top = 6.dp)
        )
        MusicSourceKeyEditor(
            currentMaskedKey = state.maskedSecret,
            onValidateAndSave = viewModel::validateAndConnect,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp)
        )

        when (state.authState) {
            LinglanAuthState.DISCONNECTED -> {
                Text(
                    "未连接 · 请粘贴聆澜密钥，验证通过后选择一个在线来源",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                )
            }

            LinglanAuthState.VALIDATING -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 10.dp))
                    Text("验证中…", color = TextSecondary)
                }
                TextButton(onClick = { viewModel.cancelConnect() }) { Text("取消") }
            }

            LinglanAuthState.ACTIVE, LinglanAuthState.STALE_OFFLINE -> {
                state.validUntilEpochMs?.let { expire ->
                    Text(
                        "授权到期：${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", java.util.Date(expire))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
                Text(
                    if (state.authState == LinglanAuthState.STALE_OFFLINE) {
                        "正在使用缓存来源 · 联网后可刷新列表"
                    } else {
                        "已连接 · 选择一个在线音乐来源"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
                if (state.isRefreshing) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 10.dp),
                            strokeWidth = 2.dp
                        )
                        Text("正在更新来源列表…", color = TextSecondary)
                    }
                } else if (state.manifestItems.isEmpty()) {
                    Text(
                        "暂无可用来源 · 点击「刷新清单」重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                state.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                }
                state.manifestItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !state.isDownloading) { viewModel.selectSource(item.id) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = state.selectedPluginId == item.id,
                            enabled = !state.isDownloading,
                            onClick = { viewModel.selectSource(item.id) }
                        )
                        Text(
                            "${item.name} v${item.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }
                Row(modifier = Modifier.padding(top = 4.dp)) {
                    TextButton(
                        onClick = { viewModel.refreshManifest() },
                        enabled = !state.isRefreshing && !state.isDownloading
                    ) { Text("刷新清单") }
                    TextButton(
                        onClick = { viewModel.disconnect() },
                        enabled = !state.isDownloading
                    ) { Text("断开") }
                }
            }

            LinglanAuthState.EXPIRED, LinglanAuthState.REVOKED, LinglanAuthState.ERROR -> {
                Text(
                    state.error ?: "密钥无效，请重新输入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    "请检查密钥后重新粘贴，系统会再次自动验证。",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                )
            }
        }
    }
}

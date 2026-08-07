package com.ncm.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ncm.app.plugin.auth.LinglanAuthState
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextSecondary
import com.ncm.app.ui.theme.TextTertiary
import com.ncm.app.viewmodel.OnlineMusicSourceViewModel

/** 设置页「在线音乐来源」区块（spec §10）：连接聆澜 → 单选一个来源，不下载脚本。 */
@Composable
fun OnlineMusicSourceSection(viewModel: OnlineMusicSourceViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var secretInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        Text("在线音乐来源", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        when (state.authState) {
            LinglanAuthState.DISCONNECTED -> {
                Text(
                    "未连接 · 输入聆澜密钥后连接，验证通过后选择一个在线来源",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp)
                )
                OutlinedTextField(
                    value = secretInput,
                    onValueChange = { secretInput = it },
                    label = { Text("聆澜密钥") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.connect(secretInput) },
                    enabled = secretInput.trim().length >= 8,
                    modifier = Modifier.padding(top = 10.dp)
                ) {
                    Text("连接聆澜")
                }
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
                state.maskedSecret?.let {
                    Text(
                        "密钥：$it（不提供复制）",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                state.validUntilEpochMs?.let { expire ->
                    Text(
                        "授权到期：${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", java.util.Date(expire))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
                Text(
                    "已连接 · 选择一个在线音乐来源",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)
                )
                if (state.manifestItems.isEmpty()) {
                    Text(
                        "暂无可用来源 · 点击「刷新清单」重试",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextTertiary,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                state.manifestItems.forEach { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.selectSource(item.id) }
                            .padding(vertical = 4.dp)
                    ) {
                        RadioButton(
                            selected = state.selectedPluginId == item.id,
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
                    TextButton(onClick = { viewModel.refreshManifest() }) { Text("刷新清单") }
                    TextButton(onClick = { viewModel.disconnect() }) { Text("断开") }
                }
            }

            LinglanAuthState.EXPIRED, LinglanAuthState.REVOKED, LinglanAuthState.ERROR -> {
                Text(
                    state.error ?: "连接异常",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Green500,
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { viewModel.disconnect() }) { Text("断开并重新连接") }
            }
        }
    }
}

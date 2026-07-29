package com.ncm.app.ui.components

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ncm.app.data.repository.MusicSourceKeyValidationResult
import com.ncm.app.ui.screens.legal.DisclaimerDialog
import com.ncm.app.ui.theme.GlassSurfaceStrong
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.RedAccent
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextSecondary
import com.ncm.app.ui.theme.TextTertiary
import kotlinx.coroutines.delay

private const val MUSIC_SOURCE_SHOP_URL = "https://sumnera.shop.shiqianjiang.cn/"
private const val DEFAULT_COUPON_CODE = "SUMNERA-8JDAZH9G"

@Composable
fun FirstUseMusicSourcePrompt(
    currentMaskedKey: String?,
    onClose: (doNotShowAgain: Boolean) -> Unit,
    onValidateAndSave: suspend (String) -> MusicSourceKeyValidationResult
) {
    var doNotShowAgain by rememberSaveable { mutableStateOf(false) }
    var showDisclaimer by rememberSaveable { mutableStateOf(false) }

    if (showDisclaimer) {
        DisclaimerDialog(onDismiss = { showDisclaimer = false })
        return
    }

    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            color = GlassSurfaceStrong,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 0.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "开始使用简云音乐",
                            style = MaterialTheme.typography.titleLarge,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            "请选择适合您的听歌方式",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    TextButton(onClick = { onClose(doNotShowAgain) }) {
                        Text(
                            "×",
                            style = MaterialTheme.typography.headlineSmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Text(
                    "如果您是网易云会员，可以点击右上角的“×”忽略，软件会优先使用您的网易云会员音源。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Text(
                    "如果您不是网易云会员，也不购买卡密，同样可以点击右上角的“×”直接使用软件；购买卡密不是强制要求。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 10.dp)
                )
                Text(
                    "如需免费听歌，可前往商城登录注册并购买“聆澜音源”这个商品。购买备用音源后即可免费听歌，推荐选择 15 元的“理论永久”，再复制卡密粘贴到下方。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 10.dp)
                )

                CouponCodeCard(modifier = Modifier.padding(top = 14.dp))
                AffiliateDisclosure(modifier = Modifier.padding(top = 10.dp))
                ShopButton(modifier = Modifier.padding(top = 16.dp))

                MusicSourceKeyEditor(
                    currentMaskedKey = currentMaskedKey,
                    onValidateAndSave = onValidateAndSave,
                    modifier = Modifier.padding(top = 14.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = doNotShowAgain,
                        onCheckedChange = { doNotShowAgain = it },
                        colors = CheckboxDefaults.colors(checkedColor = Green500)
                    )
                    Text(
                        "不再提示",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                TextButton(
                    onClick = { showDisclaimer = true },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("查看《免责声明》", color = Green500)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicSourceKeySettingsSheet(
    currentMaskedKey: String?,
    onValidateAndSave: suspend (String) -> MusicSourceKeyValidationResult,
    onClearKey: () -> Unit,
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
                "备用音源卡密",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Text(
                if (currentMaskedKey == null) {
                    "请在商城购买“聆澜音源”。购买备用音源后可以免费听歌，粘贴卡密会自动验证并立即启用。"
                } else {
                    "当前卡密 $currentMaskedKey。输入新卡密并通过验证后才会替换。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
                modifier = Modifier.padding(top = 5.dp)
            )

            CouponCodeCard(modifier = Modifier.padding(top = 14.dp))
            AffiliateDisclosure(modifier = Modifier.padding(top = 10.dp))
            ShopButton(modifier = Modifier.padding(top = 10.dp))

            MusicSourceKeyEditor(
                currentMaskedKey = currentMaskedKey,
                onValidateAndSave = onValidateAndSave,
                modifier = Modifier.padding(top = 12.dp)
            )

            if (currentMaskedKey != null) {
                TextButton(
                    onClick = {
                        onClearKey()
                        onDismiss()
                    },
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(top = 6.dp)
                ) {
                    Text("移除当前卡密", color = RedAccent)
                }
            }
        }
    }
}

@Composable
private fun AffiliateDisclosure(modifier: Modifier = Modifier) {
    Surface(
        color = Green500.copy(alpha = 0.09f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            "推广说明：商城与音源由第三方独立运营。使用优惠码购买时，开发者会获得推广佣金；不会增加商品价格，是否购买完全自愿。",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ShopButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Button(
        onClick = {
            clipboard.setText(AnnotatedString(DEFAULT_COUPON_CODE))
            Toast.makeText(
                context,
                "优惠码 $DEFAULT_COUPON_CODE 已复制，结算时可直接粘贴",
                Toast.LENGTH_SHORT
            ).show()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(MUSIC_SOURCE_SHOP_URL))
            runCatching { context.startActivity(intent) }
        },
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Green500),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        Text("前往购买“聆澜音源”")
    }
}

@Composable
private fun CouponCodeCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    Surface(
        color = Green500.copy(alpha = 0.12f),
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "默认优惠码",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary
                )
                Text(
                    DEFAULT_COUPON_CODE,
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(DEFAULT_COUPON_CODE))
                    Toast.makeText(context, "优惠码已复制", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text("复制", color = Green500)
            }
        }
    }
}

@Composable
private fun MusicSourceKeyEditor(
    currentMaskedKey: String?,
    onValidateAndSave: suspend (String) -> MusicSourceKeyValidationResult,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var value by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf<KeyInputStatus>(KeyInputStatus.Idle) }

    LaunchedEffect(value) {
        val candidate = value.trim()
        status = KeyInputStatus.Idle
        if (candidate.isBlank()) return@LaunchedEffect
        if (candidate.length < 8) {
            status = KeyInputStatus.Message("继续输入或使用快速粘贴", isError = false)
            return@LaunchedEffect
        }

        delay(550)
        status = KeyInputStatus.Checking
        status = when (val result = onValidateAndSave(candidate)) {
            MusicSourceKeyValidationResult.Valid ->
                KeyInputStatus.Message("卡密验证成功，已自动启用", isError = false)
            is MusicSourceKeyValidationResult.Invalid ->
                KeyInputStatus.Message(result.message, isError = true)
            is MusicSourceKeyValidationResult.Unavailable ->
                KeyInputStatus.Message(result.message, isError = true)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(if (currentMaskedKey == null) "输入卡密" else "输入新卡密") },
            placeholder = { Text("粘贴购买后获得的卡密") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                TextButton(
                    onClick = {
                        clipboard.getText()
                            ?.text
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { value = it }
                    }
                ) {
                    Text("快速粘贴", color = Green500)
                }
            }
        )

        when (val current = status) {
            KeyInputStatus.Idle -> Unit
            KeyInputStatus.Checking -> {
                Row(
                    modifier = Modifier.padding(top = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .width(15.dp)
                            .height(15.dp),
                        strokeWidth = 2.dp,
                        color = Green500
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "正在自动验证卡密…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary
                    )
                }
            }
            is KeyInputStatus.Message -> {
                Text(
                    current.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (current.isError) RedAccent else Green500,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

private sealed interface KeyInputStatus {
    data object Idle : KeyInputStatus
    data object Checking : KeyInputStatus
    data class Message(val text: String, val isError: Boolean) : KeyInputStatus
}

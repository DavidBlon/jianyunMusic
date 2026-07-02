package com.ncm.app.ui.screens.login

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ncm.app.ui.theme.DarkBg
import com.ncm.app.ui.theme.DarkBorder
import com.ncm.app.ui.theme.DarkSurface
import com.ncm.app.ui.theme.DarkSurface2
import com.ncm.app.ui.theme.Green500
import com.ncm.app.ui.theme.RedAccent
import com.ncm.app.ui.theme.TextPrimary
import com.ncm.app.ui.theme.TextSecondary
import com.ncm.app.ui.theme.TextTertiary
import com.ncm.app.viewmodel.MainViewModel

private enum class LoginMode {
    Web,
    Qr
}

private fun neteaseCookie(): String {
    return listOf(
        "https://music.163.com",
        "https://m.music.163.com",
        "https://163.com"
    ).mapNotNull { CookieManager.getInstance().getCookie(it) }
        .distinct()
        .joinToString("; ")
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val loginState by viewModel.loginState.collectAsState()
    val appState by viewModel.appState.collectAsState()
    var mode by remember { mutableStateOf(LoginMode.Web) }
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var submittedCookie by remember { mutableStateOf(false) }

    LaunchedEffect(loginState.qrCode, appState.isLoggedIn) {
        if (loginState.qrCode == 803 && appState.isLoggedIn) {
            onLoginSuccess()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LoginHeader(mode = mode, onModeChange = { mode = it })

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(DarkSurface)
                .border(1.dp, DarkBorder, RoundedCornerShape(18.dp))
        ) {
            if (mode == LoginMode.Web) {
                WebLoginContent(
                    initialUrl = "https://music.163.com/m/login",
                    isLoading = isLoading || loginState.isLoggingIn,
                    showLoadingOverlay = true,
                    onLoadingChange = { isLoading = it },
                    appLoggedIn = appState.isLoggedIn,
                    submittedCookie = submittedCookie,
                    onSubmittedCookieChange = { submittedCookie = it },
                    onWebViewReady = { webView = it },
                    onCookieReady = viewModel::loginWithCookie
                )
            } else {
                QrLoginContent(
                    qrImg = loginState.qrImg,
                    qrCode = loginState.qrCode,
                    onRefresh = viewModel::refreshQrLogin
                )
            }
        }

        LoginActions(
            mode = mode,
            error = loginState.error,
            onReloadWeb = {
                isLoading = true
                webView?.reload()
            },
            onSubmitCookie = {
                val cookie = neteaseCookie()
                submittedCookie = cookie.contains("MUSIC_U")
                viewModel.loginWithCookie(cookie)
            },
            onRefreshQr = viewModel::refreshQrLogin
        )
    }
}

@Composable
private fun LoginHeader(mode: LoginMode, onModeChange: (LoginMode) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Green500.copy(alpha = 0.28f),
                        DarkSurface.copy(alpha = 0.96f)
                    )
                )
            )
            .border(1.dp, Green500.copy(alpha = 0.18f), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Text(
            text = "简云音乐",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "登录网易云音乐",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(modifier = Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ModeButton(
                text = "网页登录",
                selected = mode == LoginMode.Web,
                onClick = { onModeChange(LoginMode.Web) },
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                text = "二维码登录",
                selected = mode == LoginMode.Qr,
                onClick = { onModeChange(LoginMode.Qr) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Green500 else DarkSurface2
        ),
        modifier = modifier
    ) {
        Text(text, color = if (selected) Color.White else TextPrimary)
    }
}

@Composable
private fun WebLoginContent(
    initialUrl: String,
    isLoading: Boolean,
    showLoadingOverlay: Boolean,
    onLoadingChange: (Boolean) -> Unit,
    appLoggedIn: Boolean,
    submittedCookie: Boolean,
    onSubmittedCookieChange: (Boolean) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    onCookieReady: (String) -> Unit
) {
    AndroidView(
        factory = { context ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(context).apply {
                onWebViewReady(this)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.cacheMode = WebSettings.LOAD_DEFAULT
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                webChromeClient = WebChromeClient()
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        onLoadingChange(false)
                        val cookie = neteaseCookie()
                        if (!appLoggedIn && !submittedCookie && cookie.contains("MUSIC_U")) {
                            onSubmittedCookieChange(true)
                            onCookieReady(cookie)
                        }
                    }
                }
                loadUrl(initialUrl)
            }
        },
        modifier = Modifier.fillMaxSize()
    )

    if (showLoadingOverlay && isLoading) {
        LoadingOverlay()
    }
}

@Composable
private fun QrLoginContent(
    qrImg: String?,
    qrCode: Int,
    onRefresh: () -> Unit
) {
    val qrBitmap = remember(qrImg) {
        qrImg?.substringAfter("base64,", missingDelimiterValue = qrImg)
            ?.let { encoded ->
                runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }.getOrNull()
            }
    }

    LaunchedEffect(Unit) {
        if (qrImg.isNullOrBlank()) onRefresh()
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(236.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White)
                    .padding(14.dp),
                contentAlignment = Alignment.Center
            ) {
                if (qrImg.isNullOrBlank() || qrBitmap == null) {
                    CircularProgressIndicator(color = Green500)
                } else {
                    Image(
                        bitmap = qrBitmap,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = when (qrCode) {
                    802 -> "请在网易云音乐 App 确认登录"
                    803 -> "登录已确认"
                    800 -> "二维码已过期"
                    else -> "使用网易云音乐 App 扫码"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "打开网易云音乐 App，扫描上方二维码。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x99000000)),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = Green500)
    }
}

@Composable
private fun LoginActions(
    mode: LoginMode,
    error: String?,
    onReloadWeb: () -> Unit,
    onSubmitCookie: () -> Unit,
    onRefreshQr: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = if (mode == LoginMode.Web) {
                "网页登录完成后，如果没有自动进入主页，可以点“完成”。"
            } else {
                "请使用网易云音乐 App 扫码确认，成功后会自动进入主页。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (mode == LoginMode.Web) {
                Button(
                    onClick = onReloadWeb,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface2),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("刷新网页", color = TextPrimary)
                }
                Button(
                    onClick = onSubmitCookie,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green500),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("完成", color = Color.White)
                }
            } else {
                Button(
                    onClick = onRefreshQr,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green500),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("刷新二维码", color = Color.White)
                }
            }
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = RedAccent,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

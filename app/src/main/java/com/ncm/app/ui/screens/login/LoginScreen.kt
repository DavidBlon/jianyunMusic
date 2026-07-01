package com.ncm.app.ui.screens.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ncm.app.ui.theme.*
import com.ncm.app.viewmodel.MainViewModel

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
    var isLoading by remember { mutableStateOf(true) }
    var webView: WebView? by remember { mutableStateOf(null) }
    var submittedCookie by remember { mutableStateOf(false) }

    LaunchedEffect(loginState.qrCode, appState.isLoggedIn) {
        if (loginState.qrCode == 803 && appState.isLoggedIn) {
            onLoginSuccess()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
    ) {
        AndroidView(
            factory = { context ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(context).apply {
                    webView = this
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
                            isLoading = false
                            val cookie = neteaseCookie()
                            if (!appState.isLoggedIn && !submittedCookie && cookie.contains("MUSIC_U")) {
                                submittedCookie = true
                                viewModel.loginWithCookie(cookie)
                            }
                        }
                    }
                    loadUrl("https://music.163.com/m/login")
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (isLoading || loginState.isLoggingIn) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Green500)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(DarkBg)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "请登录网易云音乐。登录成功后，应用会自动进入主页。",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { webView?.reload() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurface),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("刷新", color = TextPrimary)
                }
                Button(
                    onClick = {
                        val cookie = neteaseCookie()
                        submittedCookie = cookie.contains("MUSIC_U")
                        viewModel.loginWithCookie(cookie)
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Green500),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("我已登录", color = Color.White)
                }
            }
            loginState.error?.let {
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
}

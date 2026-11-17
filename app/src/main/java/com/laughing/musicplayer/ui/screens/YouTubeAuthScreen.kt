package com.laughing.musicplayer.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laughing.musicplayer.data.youtube.YouTubeSessionCookieManager
import com.laughing.musicplayer.ui.theme.LocalMusicPlayerColors
import com.laughing.musicplayer.ui.viewmodel.MusicPlayerViewModel
import kotlinx.coroutines.launch

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun YouTubeAuthScreen(
    viewModel: MusicPlayerViewModel,
    onBack: () -> Unit
) {
    val colors = LocalMusicPlayerColors.current
    val isLoggedIn by viewModel.isYouTubeLoggedIn.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var isPageLoading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var sessionCapturedToastShown by remember { mutableStateOf(false) }

    BackHandler {
        val wv = webViewRef
        if (wv != null && wv.canGoBack()) {
            wv.goBack()
        } else {
            onBack()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                destroy()
            }
            webViewRef = null
        }
    }

    androidx.compose.runtime.LaunchedEffect(isLoggedIn) {
        while (!isLoggedIn && !sessionCapturedToastShown) {
            kotlinx.coroutines.delay(1500L)
            val cookieManager = runCatching { CookieManager.getInstance() }.getOrNull() ?: continue
            val current = runCatching { cookieManager.getCookie("https://music.youtube.com") }.getOrNull().orEmpty()
            if (YouTubeSessionCookieManager.isAuthenticatedCookieHeader(current)) {
                val saved = viewModel.onYouTubeWebViewCookiesDetected(cookieManager)
                if (saved) {
                    sessionCapturedToastShown = true
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("youtube_auth_screen")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Quay lại",
                        tint = colors.accent
                    )
                }
                Column {
                    Text(
                        text = "Đăng nhập YouTube",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (isLoggedIn) "Đã đăng nhập • Đã đồng bộ cookies" else "Đăng nhập Google để sửa lỗi YouTube Music",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isLoggedIn) colors.accent else colors.textSecondary
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoggedIn) {
                    OutlinedButton(
                        onClick = {
                            sessionCapturedToastShown = false
                            viewModel.logoutYouTube()
                            webViewRef?.reload()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.testTag("yt_auth_logout_btn")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Đăng xuất")
                    }
                }
                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Tải lại trang",
                        tint = colors.accent
                    )
                }
            }
        }

        if (isLoggedIn) {
            Surface(
                color = colors.accent.copy(alpha = 0.14f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Phiên YouTube Music đã sẵn sàng (cookies.txt đã được tiêm vào trình phát & tải về).",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = colors.textPrimary
                    )
                }
            }
        }

        if (isPageLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = colors.accent,
                trackColor = colors.divider
            )
        }

        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setAcceptThirdPartyCookies(this, true)

                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            userAgentString = YouTubeSessionCookieManager.DEFAULT_USER_AGENT
                        }

                        fun checkAndCaptureCookies() {
                            val cookieManager = CookieManager.getInstance()
                            val current = cookieManager.getCookie("https://music.youtube.com").orEmpty()
                            if (YouTubeSessionCookieManager.isAuthenticatedCookieHeader(current) && !sessionCapturedToastShown) {
                                scope.launch {
                                    val saved = viewModel.onYouTubeWebViewCookiesDetected(cookieManager)
                                    if (saved) {
                                        sessionCapturedToastShown = true
                                    }
                                }
                            }
                        }

                        webChromeClient = WebChromeClient()
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isPageLoading = true
                            }

                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                super.doUpdateVisitedHistory(view, url, isReload)
                                checkAndCaptureCookies()
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isPageLoading = false
                                checkAndCaptureCookies()
                            }
                        }

                        loadUrl("https://music.youtube.com")
                        webViewRef = this
                    }
                }
            )
        }
    }
}

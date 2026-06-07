package com.glut.schedule.ui.pages

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Full-screen WebView for Nanning campus login.
 * Nanning requires visual captcha input that can't be automated,
 * so we show the real login page and capture the cookie after success.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun NanningLoginWebView(
    onLoginSuccess: (cookie: String) -> Unit,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var hasReachedContent by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("南宁分校登录 — 请手动输入验证码", fontSize = 15.sp, color = Color(0xFF141821)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = Color(0xFF141821))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFE8E4D6))
            )
        },
        floatingActionButton = {
            if (hasReachedContent) {
                FloatingActionButton(
                    onClick = {
                        val cookie = extractNanningCookie()
                        if (cookie.isNotBlank()) {
                            onLoginSuccess(cookie)
                        }
                    },
                    containerColor = Color(0xFF3F7DF6)
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = "导入课表", tint = Color.White)
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

                        // Accept all cookies
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                // Check if we've navigated past the login page
                                if (url != null && isContentUrl(url)) {
                                    hasReachedContent = true
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                if (url != null && isContentUrl(url) && !hasReachedContent) {
                                    hasReachedContent = true
                                }
                            }
                        }

                        loadUrl("http://jw.glutnn.cn/academic/common/security/affairLogin.jsp")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                    color = Color(0xFF3F7DF6),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * Check if URL indicates we're past the login page (i.e., login succeeded).
 */
private fun isContentUrl(url: String): Boolean {
    val loginPatterns = listOf(
        "affairLogin.jsp",
        "j_acegi_security_check",
        "getCaptcha.do",
        "checkCaptcha.do",
        "affairLogin"
    )
    return loginPatterns.none { url.contains(it, ignoreCase = true) }
}

/**
 * Extract JSESSIONID cookie from system CookieManager for Nanning domain.
 */
private fun extractNanningCookie(): String {
    val cookieManager = CookieManager.getInstance()
    val url = "http://jw.glutnn.cn"
    val cookies = cookieManager.getCookie(url) ?: ""
    val altCookies = cookieManager.getCookie("http://jw.glutnn.cn/") ?: ""
    return listOf(cookies, altCookies)
        .filter { it.isNotBlank() }
        .joinToString("; ")
        .ifBlank { "" }
}

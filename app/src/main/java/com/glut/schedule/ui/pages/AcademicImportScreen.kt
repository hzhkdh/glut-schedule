package com.glut.schedule.ui.pages

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.glut.schedule.service.academic.AcademicImportConfig
import com.glut.schedule.service.academic.AcademicWebScripts
import com.glut.schedule.service.academic.isAcademicDomainPage
import com.glut.schedule.service.academic.isLoginPage
import com.glut.schedule.service.academic.isTimetablePage
import org.json.JSONArray

@Composable
fun AcademicImportScreen(
    viewModel: AcademicImportViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    var showGuide by remember { mutableStateOf(true) }
    var showDiagnostics by remember { mutableStateOf(false) }

    fun execJs(script: String, callback: (String) -> Unit = {}) {
        webViewRef.value?.evaluateJavascript(script) { result ->
            callback(decodeJavascriptString(result))
        }
    }

    fun importCurrentWebPage() {
        execJs(AcademicWebScripts.currentPageHtml()) { html ->
            if (html.isBlank()) {
                execJs(AcademicWebScripts.fallbackPageHtml()) { fb -> viewModel.importCurrentWebPage(fb) }
            } else {
                viewModel.importCurrentWebPage(html)
            }
        }
    }

    fun importWithApiResponses() {
        execJs(AcademicWebScripts.getInterceptedResponses()) { jsonStr ->
            viewModel.importApiResponses(jsonStr)
        }
    }

    fun clickTimetableMenu() {
        viewModel.startAutoOpenTimetable()
        execJs(AcademicWebScripts.clickTimetableMenuItem()) { result ->
            if (result.contains("not_found")) {
                viewModel.reportAutoOpenFailed(result)
            } else {
                viewModel.reportAutoOpenSucceeded(result)
            }
        }
    }

    fun navigateToDirectTimetable() {
        viewModel.startAutoOpenTimetable()
        execJs(AcademicWebScripts.navigateToDirectTimetableUrl()) { result ->
            viewModel.reportAutoOpenSucceeded("已尝试直接打开课表页面")
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07111F))
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("从教务导入", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isFetching) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color(0xFF7DD3FC))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        uiState.message,
                        color = when {
                            uiState.importedCourseCount > 0 -> Color(0xFF4ADE80)
                            uiState.isOnTimetablePage -> Color(0xFFFBBF24)
                            else -> Color.White.copy(alpha = 0.7f)
                        },
                        fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (uiState.importedCourseCount > 0) {
                Icon(Icons.Outlined.CheckCircle, "成功", tint = Color(0xFF4ADE80), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = { showDiagnostics = !showDiagnostics }) {
                Icon(Icons.Outlined.BugReport, "调试", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
            }
        }

        if (uiState.isFetching) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Color(0xFF7DD3FC),
                trackColor = Color(0xFF7DD3FC).copy(alpha = 0.15f))
        }

        if (showDiagnostics && uiState.debugInfo.isNotBlank()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color(0xFF0F172A).copy(alpha = 0.95f))
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("调试信息", color = Color(0xFF7DD3FC), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Button(onClick = viewModel::saveDebugInfo,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B82F6), contentColor = Color.White),
                        modifier = Modifier.height(28.dp)) {
                        Text("保存到文件", fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(uiState.debugInfo.take(3000), color = Color.White.copy(alpha = 0.7f),
                    fontSize = 9.sp, lineHeight = 12.sp)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webViewRef.value = this
                        with(settings) {
                            javaScriptEnabled = true
                            javaScriptCanOpenWindowsAutomatically = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            setSupportMultipleWindows(true)
                            setSupportZoom(true)
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            allowFileAccess = false
                            allowContentAccess = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                            setGeolocationEnabled(false)
                        }

                        webChromeClient = WebChromeClient()

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                viewModel.onPageUrlChanged(url)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                viewModel.onPageUrlChanged(url)
                                try {
                                    val cookie = CookieManager.getInstance().getCookie(url)
                                    viewModel.saveCookie(cookie)
                                } catch (_: Exception) {}
                                execJs(AcademicWebScripts.interceptApiResponses()) { }
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                val url = request.url.toString()
                                val headers = mutableMapOf<String, String>()
                                try { request.requestHeaders?.forEach { (k, v) -> headers[k] = v } } catch (_: Exception) {}
                                viewModel.recordNetworkRequest(url, request.method, headers)
                                return null
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView, request: WebResourceRequest
                            ): Boolean {
                                val url = request.url.toString()
                                viewModel.onPageUrlChanged(url)
                                viewModel.recordNetworkRequest(url, "GET", null)
                                return false
                            }
                        }

                        CookieManager.getInstance().setAcceptCookie(true)
                        loadUrl(uiState.loginUrl)
                    }
                }
            )

            if (uiState.hasSession) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = ::clickTimetableMenu,
                            enabled = !uiState.isFetching,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1E40AF),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Outlined.Search, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("点击课表菜单", fontSize = 11.sp)
                        }
                        Button(
                            onClick = ::navigateToDirectTimetable,
                            enabled = !uiState.isFetching,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF7C3AED),
                                contentColor = Color.White
                            ),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("直接打开课表页", fontSize = 11.sp)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (uiState.hasSession) {
                    FloatingActionButton(
                        onClick = { viewModel.probeApis() },
                        modifier = Modifier.size(48.dp),
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Text("探测", fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(4.dp))
                    FloatingActionButton(
                        onClick = ::importWithApiResponses,
                        modifier = Modifier.size(48.dp),
                        containerColor = Color(0xFFF59E0B),
                        contentColor = Color(0xFF1C1917),
                        shape = CircleShape
                    ) {
                        Text("API", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
                FloatingActionButton(
                    onClick = ::importCurrentWebPage,
                    modifier = Modifier.size(56.dp),
                    containerColor = if (uiState.isOnTimetablePage) Color(0xFF22C55E) else Color(0xFFDDE4FF),
                    contentColor = Color(0xFF061A3A),
                    shape = CircleShape
                ) {
                    Icon(Icons.Outlined.FileDownload, "导入HTML", modifier = Modifier.size(26.dp))
                }
            }
        }
    }

    if (showGuide) {
        AlertDialog(
            onDismissRequest = { showGuide = false },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        showGuide = false
                        clickTimetableMenu()
                    }) { Text("自动点击课表") }
                    Button(onClick = {
                        showGuide = false
                        navigateToDirectTimetable()
                    }) { Text("直接打开课表") }
                    Button(onClick = { showGuide = false }) { Text("我知道了") }
                }
            },
            title = { Text("导入说明") },
            text = {
                Text(
                    "登录成功后可直接点右下角下载按钮导入。\n\n" +
                        "如果需要查看教务页面，再使用上方按钮打开课表入口。\n" +
                        "红色[探测]会直接请求已登录状态下的课程接口。"
                )
            }
        )
    }
}

private fun decodeJavascriptString(value: String?): String {
    if (value.isNullOrBlank() || value == "null") return ""
    return runCatching { JSONArray("[$value]").getString(0) }
        .getOrElse {
            value.trim('"')
                .replace("\\u003C", "<").replace("\\u003E", ">")
                .replace("\\\"", "\"").replace("\\n", "\n")
                .replace("\\t", "\t").replace("\\/", "/").replace("\\\\", "\\")
        }
}

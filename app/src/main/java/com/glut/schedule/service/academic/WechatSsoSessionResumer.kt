package com.glut.schedule.service.academic

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 使用 App 已持久化的 WebView 统一认证会话，静默换取新的教务 JSESSIONID。
 *
 * 不把 CASTGC/TGC 复制到 DataStore 或凭据库；票据始终由 WebView CookieManager 保管。
 * 不可只按某个 Cookie 名提前判定会话失效：统一认证页本身可能借助已有状态自动跳回教务。
 * 仅在页面稳定停留于扫码登录页后，才通知调用方展示二维码。
 */
class WechatSsoSessionResumer(private val context: Context) {
    suspend fun resume(): AcademicLoginResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val cookieManager = CookieManager.getInstance()
            val handler = Handler(Looper.getMainLooper())
            lateinit var webView: WebView
            var finished = false
            fun finish(result: AcademicLoginResult) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                webView.stopLoading()
                webView.destroy()
                if (continuation.isActive) continuation.resume(result)
            }

            webView = WebView(context.applicationContext).apply {
                // 与可见扫码页保持同一认证能力：CAS 的已有登录态与回跳都可能由脚本触发。
                // 安全性仍由下方顶层导航白名单、禁用文件访问和不注册 JS Bridge 保证。
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        upgradeAcademicHttpToHttps(url)?.let {
                            view.loadUrl(it)
                            return true
                        }
                        return classifyWechatNavigation(url) == WechatNav.BLOCKED
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        when (classifyWechatNavigation(url)) {
                            WechatNav.ACADEMIC_LANDING -> {
                                val cookie = mergeWebLoginCookies(
                                    jwCookieHeader = cookieManager.getCookie(CasWebLoginConfig.ACADEMIC_COOKIE_URL).orEmpty(),
                                    casCookieHeader = cookieManager.getCookie(CasWebLoginConfig.CAS_COOKIE_URL).orEmpty()
                                )
                                if (hasLocalAcademicSession(cookie)) {
                                    cookieManager.flush()
                                    finish(AcademicLoginResult.Success(cookie))
                                }
                            }
                            WechatNav.ACADEMIC_LOGIN_PAGE -> {
                                // 已有统一认证状态时，登录页会在脚本运行后很快回跳教务。
                                // 不能一落到此页就销毁 WebView，否则会把可静默恢复的会话误判成要扫码。
                                handler.postDelayed({
                                    if (!finished && classifyWechatNavigation(view.url ?: url) == WechatNav.ACADEMIC_LOGIN_PAGE) {
                                        finish(AcademicLoginResult.CaptchaOrInteractiveLoginRequired)
                                    }
                                }, INTERACTIVE_LOGIN_PROBE_DELAY_MILLIS)
                            }
                            else -> Unit
                        }
                    }

                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame) {
                            finish(AcademicLoginResult.NetworkError("统一身份认证网络不可用"))
                        }
                    }
                }
            }
            handler.postDelayed(
                { finish(AcademicLoginResult.NetworkError("统一身份认证响应超时")) },
                RESUME_TIMEOUT_MILLIS
            )
            continuation.invokeOnCancellation { if (!finished) finish(AcademicLoginResult.NetworkError("已取消恢复")) }
            webView.loadUrl(CasWebLoginConfig.casLoginUrl())
        }
    }

    private companion object {
        const val RESUME_TIMEOUT_MILLIS = 10_000L
        /** 给统一认证页自动回跳留出的窗口；到期仍在登录页才真正展示二维码。 */
        const val INTERACTIVE_LOGIN_PROBE_DELAY_MILLIS = 1_000L
    }
}

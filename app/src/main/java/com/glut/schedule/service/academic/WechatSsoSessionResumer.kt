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
 * 当学校已让票据失效时会落到登录页，调用方再请用户确认是否重新扫码。
 */
class WechatSsoSessionResumer(private val context: Context) {
    suspend fun resume(): AcademicLoginResult = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { continuation ->
            val cookieManager = CookieManager.getInstance()
            val casCookie = cookieManager.getCookie(CasWebLoginConfig.CAS_COOKIE_URL).orEmpty()
            if (!hasCasSession(casCookie)) {
                continuation.resume(AcademicLoginResult.CaptchaOrInteractiveLoginRequired)
                return@suspendCancellableCoroutine
            }

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
                settings.javaScriptEnabled = false
                settings.domStorageEnabled = false
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
                            WechatNav.ACADEMIC_LOGIN_PAGE ->
                                finish(AcademicLoginResult.CaptchaOrInteractiveLoginRequired)
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

    private fun hasCasSession(cookieHeader: String): Boolean =
        cookieHeader.contains("CASTGC=", ignoreCase = true) || cookieHeader.contains("TGC=", ignoreCase = true)

    private companion object {
        const val RESUME_TIMEOUT_MILLIS = 10_000L
    }
}

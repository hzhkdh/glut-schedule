package com.glut.schedule.service.academic

import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 清除本 App WebView 持有的统一身份认证状态。
 *
 * 教务 DataStore 与 WebView Cookie 是两套存储：前者清空后，若后者仍保留 CAS 会话，
 * 用户再次点“微信登录”会被直接识别为已登录，看起来就像“重置应用失效”。
 */
internal suspend fun clearWebLoginSession() = suspendCancellableCoroutine { continuation ->
    val cookieManager = CookieManager.getInstance()
    cookieManager.removeAllCookies {
        // Cookie 删除是异步的；在回调中一并清掉网页 localStorage，才通知重置流程完成。
        WebStorage.getInstance().deleteAllData()
        cookieManager.flush()
        if (continuation.isActive) continuation.resume(Unit)
    }
}

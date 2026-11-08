package com.glut.schedule.service.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 在 OkHttp 回调线程读完整个响应后才恢复协程，让取消处理覆盖等待响应头和读取响应体。
 * 响应始终由此处关闭，取消与回调同时到达时也不会把未关闭的 Response 交给调用方。
 */
internal suspend fun <T> Call.awaitResponse(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val value = response.use {
                        if (!continuation.isActive) return
                        read(it)
                    }
                    if (continuation.isActive) continuation.resume(value)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

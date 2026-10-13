package com.glut.schedule.service.holiday

import android.util.Log
import com.glut.schedule.BuildConfig
import com.glut.schedule.service.network.MAX_JSON_RESPONSE_BYTES
import com.glut.schedule.service.network.readStringLimited
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 调休补课对照表的取数。失败一律返回 null，由调用方决定是否保留旧缓存。
 *
 * 不做运行时主机白名单：URL 是**编译期常量**（[BuildConfig.HOLIDAY_MAKEUP_TABLE_URL]），
 * 既不来自用户也不来自远端，与 `RemoteBackgroundModels.trustedUrl`（那里的 URL 来自远端
 * JSON，必须校验）是两种场景。构造参数注入只是为了单测能指向 MockWebServer。
 *
 * 也不校验 Content-Type：静态托管可能把 .json 当 text/plain 发出来，误拒的代价高于收益。
 * 真正防「HTML 兜底页」的是 [isUsableMakeupTablePayload] 那道写闸门。
 */
class MakeupTableClient(
    private val url: String = BuildConfig.HOLIDAY_MAKEUP_TABLE_URL,
    private val client: OkHttpClient = defaultClient()
) {
    suspend fun fetch(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.readStringLimited(MAX_JSON_RESPONSE_BYTES)
            }
        }.onFailure { error ->
            // 不记录响应内容，只留异常类型。
            Log.w(TAG, "调休对照表刷新失败：${error.javaClass.simpleName}")
        }.getOrNull()
    }

    companion object {
        private const val TAG = "MakeupTableClient"
        private const val USER_AGENT = "GlutSchedule-Makeups"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

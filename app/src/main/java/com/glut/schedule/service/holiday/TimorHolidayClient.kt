package com.glut.schedule.service.holiday

import android.util.Log
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readStringLimited
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * timor.tech 年度节假日数据获取。
 *
 * 全仓只保留这一份实现：首页课表角标（「休」）与学期概览的节假日列表读的是同一份数据，
 * 各写一份 HTTP 调用会让两处缓存互相覆盖、口径漂移。
 * 失败一律返回空串，由调用方决定是否保留旧缓存——网络异常不该清掉已缓存的数据。
 */
class TimorHolidayClient(
    private val client: OkHttpClient = defaultClient(),
    // 可注入只是为了让测试指向本地 MockWebServer——单元测试绝不能真的访问线上接口。
    // 生产一律用默认的 timor 地址。
    private val baseUrl: String = BASE_URL
) {
    suspend fun fetchYear(year: Int): String = withContext(Dispatchers.IO) {
        if (year <= 0) return@withContext ""
        try {
            val request = Request.Builder()
                .url("$baseUrl$year")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES).orEmpty()
                } else ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch holidays for $year", e)
            ""
        }
    }

    companion object {
        private const val TAG = "TimorHolidayClient"
        private const val BASE_URL = "https://timor.tech/api/holiday/year/"
        private const val USER_AGENT = "GlutSchedule/1.0"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

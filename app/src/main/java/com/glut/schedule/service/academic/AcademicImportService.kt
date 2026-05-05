package com.glut.schedule.service.academic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class AcademicImportService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
) {
    suspend fun fetchStudentTimetableHtml(
        cookie: String,
        url: String = AcademicImportConfig.directTimetableUrl
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!hasUsableAcademicCookie(cookie)) {
            return@withContext Result.failure(IllegalStateException("请先在教务页面完成登录"))
        }

        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do")
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("教务系统返回 HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) throw IOException("教务系统返回空白页面")
                if (body.contains("登录") && body.contains("密码") && !body.contains("课表")) {
                    throw IOException("Cookie 已过期，请重新登录")
                }
                body
            }
        }
    }
}

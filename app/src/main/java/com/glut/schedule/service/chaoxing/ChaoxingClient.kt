package com.glut.schedule.service.chaoxing

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit

data class ChaoxingCourse(val id: String, val classId: String, val cpi: String, val title: String)
data class CourseHomework(val course: ChaoxingCourse, val homework: List<HomeworkEntry>)
data class HomeworkSyncResult(val courses: List<CourseHomework>, val failed: List<ChaoxingCourse>, val failureReasons: Map<String, Int> = emptyMap())

/** CookieManager 按请求域匹配 Cookie，避免把登录域的会话误发到课程域。 */
class ChaoxingClient {
    private val cookies = CookieManager.getInstance()
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).cookieJar(object : CookieJar {
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.getCookie(url.toString())
                .orEmpty().split(';').mapNotNull { Cookie.parse(url, it.trim()) }
            override fun saveFromResponse(url: HttpUrl, values: List<Cookie>) {
                values.forEach { cookies.setCookie(url.toString(), it.toString()) }
                cookies.flush()
            }
        }).build()

    private fun fetch(url: HttpUrl, referer: String? = null): Pair<HttpUrl, String> {
        val builder = Request.Builder().url(url)
        if (referer != null) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "学习通请求失败（${response.code}）" }
            return response.request.url to (response.body?.string() ?: error("学习通返回空响应"))
        }
    }

    // 仅用课程列表结构确认会话，不读取或记录账号信息。
    fun verifyLogin(): Boolean {
        val url = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1".toHttpUrl()
        val body = fetch(url, "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction").second
        return runCatching { JSONObject(body).optJSONArray("channelList") != null }.getOrDefault(false)
    }

    fun sync(): HomeworkSyncResult {
        val url = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1".toHttpUrl()
        val raw = fetch(url, "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction").second
        val json = runCatching { JSONObject(raw) }.getOrElse { error("学习通登录已失效或课程列表格式发生变化") }
        val channels = json.optJSONArray("channelList") ?: error("学习通课程列表格式发生变化")
        val courses = (0 until channels.length()).mapNotNull { i ->
            val channel = channels.optJSONObject(i) ?: return@mapNotNull null
            val content = channel.optJSONObject("content") ?: return@mapNotNull null
            val course = content.optJSONObject("course")?.optJSONArray("data")?.optJSONObject(0) ?: return@mapNotNull null
            val id = course.optString("id"); val classId = content.optString("id")
            val cpi = channel.optString("cpi").ifBlank { content.optString("cpi") }
            if (id.isBlank() || classId.isBlank() || cpi.isBlank()) return@mapNotNull null
            ChaoxingCourse(id, classId, cpi, course.optString("name"))
        }.distinctBy { it.id to it.classId }
        check(channels.length() == 0 || courses.isNotEmpty()) { "学习通课程列表格式发生变化" }
        val success = mutableListOf<CourseHomework>(); val failed = mutableListOf<ChaoxingCourse>()
        val reasons = mutableMapOf<String, Int>()
        for (course in courses) {
            var stage = "课程页面请求失败"
            try {
                val middle = "https://mooc1.chaoxing.com/visit/stucoursemiddle".toHttpUrl().newBuilder()
                    .addQueryParameter("courseid", course.id).addQueryParameter("clazzid", course.classId)
                    .addQueryParameter("cpi", course.cpi).addQueryParameter("ismooc2", "1").addQueryParameter("v", "2").build()
                val (finalUrl, page) = fetch(middle)
                stage = "课程页面未提供可用作业入口"
                val studentEnc = finalUrl.queryParameter("enc").orEmpty()
                val workEnc = Jsoup.parse(page).selectFirst("#workEnc")?.attr("value").orEmpty()
                check(studentEnc.isNotBlank() && workEnc.isNotBlank()) { "无法获取课程作业入口" }
                val workUrl = "https://mooc1.chaoxing.com/mooc2/work/list".toHttpUrl().newBuilder()
                    .addQueryParameter("courseId", course.id).addQueryParameter("classId", course.classId)
                    .addQueryParameter("cpi", course.cpi).addQueryParameter("ut", "s")
                    .addQueryParameter("t", System.currentTimeMillis().toString())
                    .addQueryParameter("stuenc", studentEnc).addQueryParameter("enc", workEnc).build()
                stage = "作业列表请求失败"
                val (workFinalUrl, html) = fetch(workUrl, finalUrl.toString())
                stage = "作业页面发生跳转"
                check(workFinalUrl.host == "mooc1.chaoxing.com" && workFinalUrl.encodedPath.contains("/work/list")) {
                    "学习通作业页面发生跳转"
                }
                stage = "作业访问被学习通拒绝"
                check(!html.contains("无权限的操作") && !html.contains("无效的用户")) { "课程查询被拒绝" }
                stage = "作业列表结构无法识别"
                check(hasRecognizableHomeworkList(html)) { "作业列表结构无法识别" }
                success.add(CourseHomework(course, parseHomeworkList(html, System.currentTimeMillis())))
            } catch (exception: Exception) {
                failed.add(course)
                // 只持久化预定义的失败阶段，不写异常原文、请求地址和登录票据。
                val reason = if (exception is java.io.IOException) "网络连接失败或超时" else stage
                reasons[reason] = (reasons[reason] ?: 0) + 1
            }
        }
        return HomeworkSyncResult(success, failed, reasons)
    }
}

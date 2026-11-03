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
import com.glut.schedule.service.network.readStringLimited
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES

data class ChaoxingCourse(val id: String, val classId: String, val cpi: String, val title: String)
data class CourseHomework(val course: ChaoxingCourse, val homework: List<HomeworkEntry>)
data class HomeworkSyncResult(val courses: List<CourseHomework>, val failed: List<ChaoxingCourse>, val failureReasons: Map<String, Int> = emptyMap())

/** CookieManager 按请求域匹配 Cookie，避免把登录域的会话误发到课程域。 */
class ChaoxingClient(
    private val transport: ((HttpUrl, String?) -> Pair<HttpUrl, String>)? = null
) {
    // 签名链接仅保留在进程内，绝不进入 SharedPreferences 或日志。
    private val accesses = if (transport == null) homeworkAccesses else HomeworkAccesses()
    private val cookies by lazy { CookieManager.getInstance() }
    private val client by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).cookieJar(ChaoxingCookieJar(
            read = { url -> cookies.getCookie(url.toString()).orEmpty().split(';')
                .mapNotNull { Cookie.parse(url, it.trim()) } },
            write = { url, values ->
                values.forEach { cookies.setCookie(url.toString(), it.toString()) }
                cookies.flush()
            }
        )).build() }

    private fun fetch(url: HttpUrl, referer: String? = null): Pair<HttpUrl, String> {
        transport?.let { return it(url, referer) }
        val builder = Request.Builder().url(url)
            // 与网页登录使用同一桌面入口，避免服务器按默认 okhttp UA 返回简化页。
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
        if (referer != null) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            // 诊断仅记录主机、固定路径与状态码，绝不输出查询参数、Cookie 或正文。
            android.util.Log.i("ChaoxingHTTP", "${url.host}${url.encodedPath} -> ${response.request.url.host}${response.request.url.encodedPath} status=${response.code}")
            check(response.isSuccessful) { "学习通请求失败（${response.code}）" }
            return response.request.url to (response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES) ?: error("学习通返回空响应"))
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
                val (workFinalUrl, html) = workPage(course)
                stage = "作业页面发生跳转"
                check(workFinalUrl.host == "mooc1.chaoxing.com" && workFinalUrl.encodedPath.contains("/work/list")) {
                    "学习通作业页面发生跳转"
                }
                stage = "作业访问被学习通拒绝"
                check(!html.contains("无权限的操作") && !html.contains("无效的用户")) { "课程查询被拒绝" }
                stage = "作业列表结构无法识别"
                check(hasRecognizableHomeworkList(html)) { "作业列表结构无法识别" }
                rememberAccesses(course, workFinalUrl, html)
                success.add(CourseHomework(course, parseHomeworkList(html, System.currentTimeMillis())))
            } catch (exception: Exception) {
                failed.add(course)
                // 只持久化预定义的失败阶段，不写异常原文、请求地址和登录票据。
                val reason = when {
                    exception is java.io.IOException -> "网络连接失败或超时"
                    exception.message == "课程页面未提供可用作业入口" -> "课程页面未提供可用作业入口"
                    else -> stage
                }
                reasons[reason] = (reasons[reason] ?: 0) + 1
            }
        }
        return HomeworkSyncResult(success, failed, reasons)
    }
    /** 课程入口和详情共用最新签名；签名只在本次调用内使用，不进入缓存。 */
    private fun workPage(course: ChaoxingCourse): Pair<HttpUrl, String> {
        val middle = "https://mooc1.chaoxing.com/visit/stucoursemiddle".toHttpUrl().newBuilder()
            .addQueryParameter("courseid", course.id).addQueryParameter("clazzid", course.classId)
            .addQueryParameter("cpi", course.cpi).addQueryParameter("ismooc2", "1").addQueryParameter("v", "2").build()
        var (url, html) = fetch(middle)
        var enc = Jsoup.parse(html).selectFirst("#workEnc")?.attr("value").orEmpty()
        // 老入口可能返回不完整页面；只重取官方已验证的课程页，不猜测签名。
        if (enc.isBlank()) {
            val direct = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu".toHttpUrl().newBuilder()
                .addQueryParameter("courseid", course.id).addQueryParameter("clazzid", course.classId)
                .addQueryParameter("cpi", course.cpi).build()
            val response = fetch(direct)
            url = response.first; html = response.second
            enc = Jsoup.parse(html).selectFirst("#workEnc")?.attr("value").orEmpty()
        }
        val studentEnc = url.queryParameter("enc").orEmpty().ifBlank {
            Jsoup.parse(html).selectFirst("input#enc")?.attr("value").orEmpty()
        }
        check(enc.isNotBlank() && studentEnc.isNotBlank()) { "课程页面未提供可用作业入口" }
        val work = "https://mooc1.chaoxing.com/mooc2/work/list".toHttpUrl().newBuilder()
            .addQueryParameter("courseId", course.id).addQueryParameter("classId", course.classId)
            .addQueryParameter("cpi", course.cpi).addQueryParameter("ut", "s")
            .addQueryParameter("stuenc", studentEnc).addQueryParameter("enc", enc).build()
        return fetch(work, url.toString())
    }

    private fun rememberAccesses(course: ChaoxingCourse, url: HttpUrl, html: String) {
        Jsoup.parse(html).select("li[data]").forEach { item ->
            val link = url.resolve(item.attr("data")) ?: return@forEach
            val id = link.queryParameter("workId").orEmpty()
            if (id.isNotBlank() && validDetailLink(link)) accesses.put(course.id, course.classId, id, link, url.toString())
        }
    }

    /** 优先复用刚同步的官方入口，避免每次展开都重新进入课程并轮换会话。 */
    fun detail(row: CachedHomework): String {
        check(row.workId.isNotBlank() && row.cpi.isNotBlank()) { "请先刷新作业列表，再查看详情" }
        val recent = accesses.get(row.courseId, row.classId, row.workId)
        if (recent != null) {
            try { return readDetail(recent.first, recent.second) } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (_: HomeworkDetailException) {
                accesses.remove(row.courseId, row.classId, row.workId)
            }
        }
        val course = ChaoxingCourse(row.courseId, row.classId, row.cpi, row.courseName)
        val (url, html) = detailStep("无法获取作业列表，请刷新列表后重试") { workPage(course) }
        rememberAccesses(course, url, html)
        val target = accesses.get(row.courseId, row.classId, row.workId)
            ?: throw HomeworkDetailException("未找到这份作业，请刷新列表后重试")
        return readDetail(target.first, target.second)
    }

    private fun validDetailLink(url: HttpUrl): Boolean = url.scheme == "https" &&
        url.host == "mooc1.chaoxing.com" &&
        url.encodedPath in setOf("/mooc-ans/mooc2/work/task", "/mooc2/work/task")

    private fun readDetail(target: HttpUrl, referer: String): String {
        val body = detailStep("详情请求失败，请检查网络后重试") {
            fetch(target, referer).second
        }
        return detailStep("详情页面无法识别，请重新登录后重试") { parseHomeworkDetail(body) }
    }

}

/** 仅暴露固定阶段文案，不把异常中的签名地址或登录票据展示给用户。 */
class HomeworkDetailException(message: String) : IllegalStateException(message)

private inline fun <T> detailStep(message: String, block: () -> T): T = try {
    block()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (_: Exception) {
    throw HomeworkDetailException(message)
}

/** 缓存只服务刚同步的入口；五分钟后重新定位，退出账号立即销毁。 */
internal class HomeworkAccesses {
    private data class Entry(val url: HttpUrl, val referer: String, val at: Long)
    private val entries = mutableMapOf<Triple<String, String, String>, Entry>()
    @Synchronized fun put(course: String, clazz: String, work: String, url: HttpUrl, referer: String) {
        entries[Triple(course, clazz, work)] = Entry(url, referer, androidFreeNow())
    }
    @Synchronized fun get(course: String, clazz: String, work: String): Pair<HttpUrl, String>? {
        val key = Triple(course, clazz, work)
        val entry = entries[key] ?: return null
        if (androidFreeNow() - entry.at >= 300_000L) { entries.remove(key); return null }
        return entry.url to entry.referer
    }
    @Synchronized fun remove(course: String, clazz: String, work: String) { entries.remove(Triple(course, clazz, work)) }
    @Synchronized fun clear() { entries.clear() }
    private fun androidFreeNow() = System.nanoTime() / 1_000_000L
}
internal val homeworkAccesses = HomeworkAccesses()

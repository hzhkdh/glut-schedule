package com.glut.schedule.service.chaoxing

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.Response
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import com.glut.schedule.service.network.readStringLimited
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.MAX_JSON_RESPONSE_BYTES
import java.io.File

data class ChaoxingCourse(val id: String, val classId: String, val cpi: String, val title: String)
data class CourseHomework(val course: ChaoxingCourse, val homework: List<HomeworkEntry>, val kind: String = "homework")
data class FailedCourseTask(val course: ChaoxingCourse, val kind: String, val reason: String = "查询未完成")
data class HomeworkSyncResult(val courses: List<CourseHomework>, val failed: List<ChaoxingCourse>, val failureReasons: Map<String, Int> = emptyMap(), val failedTasks: List<FailedCourseTask> = failed.map { FailedCourseTask(it, "homework") }, val catalog: List<ChaoxingCourse>? = null)

/** 只表示已确认回到认证入口；普通结构变化和网络故障仍使用原有失败路径。 */
class ChaoxingLoginRequiredException : java.io.IOException("学习通登录已失效")

internal fun isChaoxingLoginPage(url: HttpUrl, html: String): Boolean {
    fun loginUrl(candidate: HttpUrl): Boolean = candidate.host == "passport2.chaoxing.com" &&
        candidate.encodedPath.lowercase() in setOf("/login", "/fanyalogin", "/mlogin", "/sso/tologin")
    if (loginUrl(url)) return true
    val document = Jsoup.parse(html)
    // 密码框本身不足以判定失效：必须同时存在官方认证表单或学习通固定账号表单。
    return document.select("form").any { form ->
        form.selectFirst("input[type=password]") != null &&
            (url.resolve(form.attr("action"))?.let(::loginUrl) == true ||
                (form.id() in setOf("loginFrom", "loginForm") &&
                    form.selectFirst("input#phone, input[name=uname]") != null))
    }
}

/** CookieManager 按请求域匹配 Cookie，避免把登录域的会话误发到课程域。 */
class ChaoxingClient(
    private val transport: ((HttpUrl, String?) -> Pair<HttpUrl, String>)? = null
) {
    private val cancelled = AtomicBoolean(false)
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    /** 仅取消本次同步实例；不会中断手动页或其他账号持有的客户端。 */
    fun cancel() {
        cancelled.set(true)
        activeCalls.forEach { it.cancel() }
    }

    private fun ensureNotCancelled() {
        if (cancelled.get()) throw CancellationException("学习通同步已取消")
    }

    /** 注册与取消之间再次检查标记，消除“刚取消便发出下一请求”的竞态。 */
    private inline fun <T> executeCancellable(http: OkHttpClient, request: Request, read: (Response) -> T): T {
        ensureNotCancelled()
        val call = http.newCall(request)
        activeCalls += call
        try {
            ensureNotCancelled()
            return call.execute().use(read)
        } catch (error: java.io.IOException) {
            ensureNotCancelled()
            throw error
        } finally {
            activeCalls -= call
        }
    }

    // 签名链接仅保留在进程内，绝不进入 SharedPreferences 或日志。
    private val accesses = if (transport == null) homeworkAccesses else HomeworkAccesses()
    private val modernCoursePages = mutableMapOf<Pair<String, String>, Pair<HttpUrl, String>>()
    private val coursePages = mutableMapOf<Pair<String, String>, Pair<HttpUrl, String>>()
    private val cookies by lazy { CookieManager.getInstance() }
    private val client by lazy { OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).cookieJar(ChaoxingCookieJar(
            read = { url -> cookies.getCookie(url.toString()).orEmpty().split(';')
                .mapNotNull { Cookie.parse(url, it.trim()) } },
            write = { url, values ->
                values.forEach { cookies.setCookie(url.toString(), it.toString()) }
                cookies.flush()
            }
        )).build() }
    // 任务链路逐跳校验白名单，关闭 OkHttp 自动跟随，避免先访问考试作答页再事后拒绝。
    private val taskClient by lazy { client.newBuilder().followRedirects(false).followSslRedirects(false).build() }
    // 官网预览 CDN 不继承学习通账号 Cookie，也不跟随跳转。
    private val documentCdn by lazy { taskClient.newBuilder().cookieJar(CookieJar.NO_COOKIES)
        .callTimeout(2, TimeUnit.MINUTES).build() }

    private fun fetch(url: HttpUrl, referer: String? = null): Pair<HttpUrl, String> {
        ensureNotCancelled()
        transport?.let { return it(url, referer).also { ensureNotCancelled() } }
        val builder = Request.Builder().url(url)
            // 与网页登录使用同一桌面入口，避免服务器按默认 okhttp UA 返回简化页。
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
        if (referer != null) builder.header("Referer", referer)
        executeCancellable(client, builder.build()) { response ->
            // 诊断仅记录主机、固定路径与状态码，绝不输出查询参数、Cookie 或正文。
            android.util.Log.i("ChaoxingHTTP", "${url.host}${url.encodedPath} -> ${response.request.url.host}${response.request.url.encodedPath} status=${response.code}")
            check(response.isSuccessful) { "学习通请求失败（${response.code}）" }
            return response.request.url to (response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES) ?: error("学习通返回空响应"))
        }
    }

    internal fun validTaskUrl(url: HttpUrl): Boolean = url.scheme == "https" && url.port == 443 &&
        url.encodedUsername.isEmpty() && url.encodedPassword.isEmpty() && when (url.host) {
        "groupweb.chaoxing.com" -> url.encodedPath in setOf("/course/topic/topicList", "/pc/invitation/subject") ||
            discussionListPath.matches(url.encodedPath) || discussionDetailPath.matches(url.encodedPath)
        "mooc1.chaoxing.com", "mooc2-ans.chaoxing.com" -> (url.host == "mooc1.chaoxing.com" && url.encodedPath == "/exam-ans/mooc2/exam/task") || url.encodedPath in setOf(
            "/mooc2-ans/coursemanagev3/stu-base-info", "/mooc2-ans-vue/fanyav3/stu", "/mooc-ans/work/api/task", "/mooc-ans/mooc2/exam/task", "/visit/stucoursemiddle", "/mooc2-ans/mycourse/stu", "/mycourse/studentcourse",
            "/mooc2-ans/exam/exam-list", "/exam-ans/mooc2/exam/exam-list")
        else -> false
    }

    /** 任务请求手动跟随重定向，每一跳都先校验再发出；最多六跳。 */
    private fun fetchTask(url: HttpUrl, referer: String? = null, topicTags: String? = null): Pair<HttpUrl, String> {
        ensureNotCancelled()
        check(validTaskUrl(url)) { "学习通任务入口不在只读白名单" }
        check(topicTags == null || (url.host == "groupweb.chaoxing.com" && discussionListPath.matches(url.encodedPath)))
        transport?.let { return it(url, referer).also { ensureNotCancelled() } }
        var current = url
        var ref = referer
        repeat(6) {
            val builder = Request.Builder().url(current)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
            if (ref != null) builder.header("Referer", ref)
            if (discussionDetailPath.matches(current.encodedPath)) builder.header("X-Requested-With", "XMLHttpRequest")
            if (topicTags != null) {
                val form = FormBody.Builder()
                if (topicTags.isNotEmpty()) form.add("tags", topicTags)
                builder.header("X-Requested-With", "XMLHttpRequest").header("Origin", "https://groupweb.chaoxing.com")
                    .post(form.build())
            }
            executeCancellable(taskClient, builder.build()) { response ->
                // 动态路径含讨论组 ID，诊断只使用固定占位符。
                val path = if (topicTags != null || discussionDetailPath.matches(current.encodedPath)) "/course/topic/{id}" else current.encodedPath
                android.util.Log.i("ChaoxingTaskHTTP", current.host + path + " -> status=" + response.code)
                if (response.code in 301..308) {
                    val location = response.header("Location") ?: error("学习通任务跳转异常")
                    val next = current.resolve(location) ?: error("学习通任务跳转异常")
                    if (isChaoxingLoginPage(next, "")) throw ChaoxingLoginRequiredException()
                    check(validTaskUrl(next)) { "学习通任务入口发生跳转" }
                    check(topicTags == null) { "讨论列表已跳转，请刷新后重试" }
                    ref = current.toString()
                    current = next
                    return@repeat
                }
                check(response.isSuccessful) { "学习通任务请求失败（${response.code}）" }
                val body = response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES) ?: error("学习通返回空响应")
                if (isChaoxingLoginPage(current, body)) throw ChaoxingLoginRequiredException()
                return current to body
            }
        }
        error("学习通任务跳转次数过多")
    }

    // 仅用课程列表结构确认会话，不读取或记录账号信息。
    fun verifyLogin(): Boolean {
        val url = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1".toHttpUrl()
        val body = fetch(url, "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction").second
        return runCatching { JSONObject(body).optJSONArray("channelList") != null }.getOrDefault(false)
    }

    fun sync(shouldLoad: (ChaoxingCourse, String) -> Boolean = { _, _ -> true }): HomeworkSyncResult {
        val url = "https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1".toHttpUrl()
        val (responseUrl, raw) = fetch(url, "https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction")
        if (isChaoxingLoginPage(responseUrl, raw)) throw ChaoxingLoginRequiredException()
        val json = runCatching { JSONObject(raw) }.getOrElse { error("学习通课程列表格式发生变化") }
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
        val failedTasks = mutableListOf<FailedCourseTask>()
        for (course in courses) {
            ensureNotCancelled()
            if (listOf("homework", "exam", "discussion").none { shouldLoad(course, it) }) continue
            // 新版课程由 JSON 提供任务，不再强求旧版 workEnc 隐藏字段。
            val entrance = try { coursePage(course) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (entrance?.first?.encodedPath == "/mooc2-ans-vue/fanyav3/stu") {
                for (kind in sequenceOf("homework", "exam", "discussion").filter { shouldLoad(course, it) }) try {
                    success.add(CourseHomework(course, if (kind == "discussion") modernDiscussion(course) else modernTasks(course, kind), kind))
                } catch (e: CancellationException) { throw e } catch (e: ChaoxingLoginRequiredException) { throw e } catch (_: Exception) {
                    failedTasks.add(FailedCourseTask(course, kind, "新版任务查询失败"))
                    if (course !in failed) failed.add(course)
                    reasons["新版任务查询失败"] = (reasons["新版任务查询失败"] ?: 0) + 1
                }
                continue
            }
            var stage = "课程页面请求失败"
            if (shouldLoad(course, "homework")) try {
                val (workFinalUrl, html) = workPage(course, entrance)
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
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (exception: Exception) {
                ensureNotCancelled()
                val recovered = recoverModern(course, "homework")
                if (recovered != null) success.add(CourseHomework(course, recovered)) else {
                failed.add(course)
                // 只持久化预定义的失败阶段，不写异常原文、请求地址和登录票据。
                val reason = when {
                    exception is java.io.IOException -> "网络连接失败或超时"
                    exception.message == "课程页面未提供可用作业入口" -> "课程页面未提供可用作业入口"
                    else -> stage
                }
                failedTasks.add(FailedCourseTask(course, "homework", reason))
                reasons[reason] = (reasons[reason] ?: 0) + 1
                }
            }
            // 每种任务独立成功或失败，某个入口异常不能清掉同课程其他类型的缓存。
            for (kind in sequenceOf("discussion", "exam").filter { shouldLoad(course, it) }) try {
                success.add(CourseHomework(course, loadOtherTasks(course, kind), kind))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: ChaoxingLoginRequiredException) { throw e
            } catch (_: LearningTaskUnsupportedException) {
                val recovered = recoverModern(course, kind)
                if (recovered != null) success.add(CourseHomework(course, recovered, kind)) else {
                if (course !in failed) failed.add(course)
                val reason = if (kind == "discussion") "讨论列表结构暂不支持" else "考试列表结构暂不支持"
                failedTasks.add(FailedCourseTask(course, kind, reason))
                reasons[reason] = (reasons[reason] ?: 0) + 1
                }
            } catch (error: Exception) {
                ensureNotCancelled()
                val recovered = recoverModern(course, kind)
                if (recovered != null) success.add(CourseHomework(course, recovered, kind)) else {
                if (course !in failed) failed.add(course)
                // 只显示预定义阶段，避免将上游链接或正文暴露在诊断界面。
                val reason = when {
                    error is java.io.IOException -> "网络连接失败或超时"
                    kind != "discussion" -> "考试列表无法确认"
                    error.message == "讨论分页未完成" || error.message == "讨论分页超限" -> "讨论分页未完成"
                    error.message in setOf("讨论课程配置无法识别", "讨论班级范围无法识别", "讨论教师名单无法识别") -> "讨论课程配置无法识别"
                    else -> "讨论查询未完成"
                }
                failedTasks.add(FailedCourseTask(course, kind, reason))
                reasons[reason] = (reasons[reason] ?: 0) + 1
                }
            }
        }
        return HomeworkSyncResult(success, failed, reasons, failedTasks, catalog = courses)
    }
    /** 有效课程页不能是登录页或错误页；讨论/考试不依赖作业入口存在。 */
    private fun validCoursePage(url: HttpUrl, html: String): Boolean {
        if (url.host !in setOf("mooc1.chaoxing.com", "mooc2-ans.chaoxing.com")) return false
        if (url.encodedPath == "/mooc2-ans-vue/fanyav3/stu") return true
        val document = Jsoup.parse(html)
        // 登录页会带密码框或登录域脚本，绝不能当成“没有讨论/考试”的空课程页。
        if (document.selectFirst("input[type=password]") != null) return false
        if (html.contains("passport2.chaoxing.com", ignoreCase = true)) return false
        return document.selectFirst("#workEnc, input#enc, #courseId, input[name=courseId], input#courseid") != null ||
            html.contains("workEnc", ignoreCase = true) ||
            html.contains("bbsid", ignoreCase = true) ||
            html.contains("groupweb.chaoxing.com", ignoreCase = true) ||
            html.contains("/mooc2-ans/exam/exam-list", ignoreCase = true) ||
            html.contains("/exam-ans/mooc2/exam/exam-list", ignoreCase = true)
    }

    /** 获取课程中间页：作业、讨论、考试各自解析自己的入口。 */
    private fun coursePage(course: ChaoxingCourse, task: Boolean = false): Pair<HttpUrl, String> {
        val middle = "https://mooc1.chaoxing.com/visit/stucoursemiddle".toHttpUrl().newBuilder()
            .addQueryParameter("courseid", course.id).addQueryParameter("clazzid", course.classId)
            .addQueryParameter("cpi", course.cpi).addQueryParameter("ismooc2", "1").addQueryParameter("v", "2").build()
        var (url, html) = if (task) fetchTask(middle) else fetch(middle)
        // 遵循官方回退按钮：保留原始签名，只修改本次课程页入口。
        if (url.encodedPath == "/mooc2-ans-vue/fanyav3/stu") {
            modernCoursePages[course.id to course.classId] = url to html
            val legacy = url.newBuilder().encodedPath("/mooc2-ans/mycourse/stu")
                .setQueryParameter("courseid", course.id).setQueryParameter("clazzid", course.classId)
                .setQueryParameter("v", "2").setQueryParameter("pageHeader", "-1")
                .setQueryParameter("hideHead", "0").setQueryParameter("forceMooc2", "true").build()
            try {
                val old = if (task) fetchTask(legacy, url.toString()) else fetch(legacy, url.toString())
                if (old.first.encodedPath == "/mooc2-ans/mycourse/stu" && validCoursePage(old.first, old.second)) { url = old.first; html = old.second }
            } catch (e: CancellationException) { throw e } catch (e: ChaoxingLoginRequiredException) { throw e } catch (_: Exception) { ensureNotCancelled() }
        }
        // 老入口可能返回不完整页面；只重取官方已验证的课程页，不猜测签名。
        if (!validCoursePage(url, html)) {
            val direct = "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu".toHttpUrl().newBuilder()
                .addQueryParameter("courseid", course.id).addQueryParameter("clazzid", course.classId)
                .addQueryParameter("cpi", course.cpi).build()
            val response = if (task) fetchTask(direct) else fetch(direct)
            url = response.first; html = response.second
        }
        check(validCoursePage(url, html)) { "学习通登录已失效或课程页面无法识别" }
        return url to html
    }

    /** 课程入口和详情共用最新签名；签名只在本次调用内使用，不进入缓存。 */
    private fun workPage(course: ChaoxingCourse, entrance: Pair<HttpUrl, String>? = null): Pair<HttpUrl, String> {
        val (url, html) = entrance ?: coursePage(course)
        val enc = Jsoup.parse(html).selectFirst("#workEnc")?.attr("value").orEmpty()
        val studentEnc = url.queryParameter("enc").orEmpty().ifBlank {
            Jsoup.parse(html).selectFirst("input#enc")?.attr("value").orEmpty()
        }
        // 只有确认是有效作业入口才缓存，登录页/错误页不能被后续任务当成“没有讨论/考试”。
        check(enc.isNotBlank() && studentEnc.isNotBlank()) { "课程页面未提供可用作业入口" }
        coursePages[course.id to course.classId] = url to html
        val work = "https://mooc1.chaoxing.com/mooc2/work/list".toHttpUrl().newBuilder()
            .addQueryParameter("courseId", course.id).addQueryParameter("classId", course.classId)
            .addQueryParameter("cpi", course.cpi).addQueryParameter("ut", "s")
            .addQueryParameter("stuenc", studentEnc).addQueryParameter("enc", enc).build()
        return fetch(work, url.toString())
    }

    /** 旧版当前类型失败时才重试新版，失败仍保留该类型旧缓存。 */
    private fun recoverModern(course: ChaoxingCourse, kind: String): List<HomeworkEntry>? {
        if (kind == "homework" && !modernCoursePages.containsKey(course.id to course.classId)) return null
        return try { if (kind == "discussion") modernDiscussion(course) else modernTasks(course, kind) }
        catch (e: CancellationException) { throw e } catch (e: ChaoxingLoginRequiredException) { throw e }
        catch (_: Exception) { ensureNotCancelled(); null }
    }

    /** 从官方嵌套导航取得讨论入口，不猜测 bbsid 或签名。 */
    private fun modernDiscussion(course: ChaoxingCourse): List<HomeworkEntry> {
        val infoUrl = "https://mooc2-ans.chaoxing.com/mooc2-ans/coursemanagev3/stu-base-info".toHttpUrl().newBuilder()
            .addQueryParameter("courseId", course.id).addQueryParameter("clazzId", course.classId).addQueryParameter("cpi", course.cpi).build()
        val info = JSONObject(fetchTask(infoUrl).second)
        check(info.optBoolean("status")) { "新版讨论导航查询失败" }
        val navigation = info.optJSONArray("navigationList") ?: error("新版讨论导航无法识别")
        fun find(list: org.json.JSONArray): HttpUrl? {
            for (i in 0 until list.length()) {
                val item = list.getJSONObject(i)
                val link = infoUrl.resolve(item.optString("linkUrl"))
                if (link?.scheme == "https" && link.host == "groupweb.chaoxing.com" && link.encodedPath == "/course/topic/topicList") return link
                item.optJSONArray("children")?.let { find(it) }?.let { return it }
            }
            return null
        }
        val target = find(navigation) ?: return emptyList()
        return discussionList(course, target, modernCoursePages[course.id to course.classId]?.first?.toString())
    }

    /** 新旧导航共用同一讨论列表协议与会话。 */
    private fun discussionList(course: ChaoxingCourse, target: HttpUrl, referer: String?): List<HomeworkEntry> {
        val (source, html) = fetchTask(target, referer)
        check(source.host == "groupweb.chaoxing.com" && source.encodedPath == "/course/topic/topicList")
        return loadLearningDiscussions(html, source, course.id) { url, tags ->
            JSONObject(fetchTask(url, source.toString(), topicTags = tags).second)
        }
    }

    /** 官方新版列表分页读取；仅缓存列表实际提供的只读作业入口。 */
    private fun modernTasks(course: ChaoxingCourse, kind: String): List<HomeworkEntry> {
        val entries = mutableListOf<HomeworkEntry>()
        for (page in 1..300) {
            val path = if (kind == "homework") "/mooc-ans/work/api/task" else "/mooc-ans/mooc2/exam/task"
            val url = ("https://mooc1.chaoxing.com" + path).toHttpUrl().newBuilder()
                .addQueryParameter("courseId", course.id).addQueryParameter("classId", course.classId)
                .addQueryParameter("pageNum", page.toString()).addQueryParameter("pageSize", "50").build()
            val json = JSONObject(fetchTask(url).second)
            check(json.optBoolean("status")) { "新版任务查询被拒绝" }
            val rows = json.optJSONArray("data") ?: error("新版任务列表无法识别")
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                val id = if (kind == "homework") item.optString("workId") else item.optString("examRelationId").ifBlank { item.optString("examId") }.ifBlank { item.optString("id") }
                check(id.isNotBlank()) { "新版任务身份缺失" }
                var status = if (kind == "homework") when (item.optInt("stuStatus", -1)) {
                    0, 1, 2 -> "未交"; 3 -> "待批阅"; 4 -> "已完成"; 5 -> "被打回"; else -> "状态待确认"
                } else when (item.optInt("stuStatus", -1)) {
                    0, 1, 2 -> "未交"; 3 -> "已完成"; 4 -> "待批阅"; 5 -> "待重做"; else -> "状态待确认"
                }
                // 官方互评状态独立于普通提交状态；未发布的任务不能推断为待交。
                if (item.optInt("evaluation") > 0) status = when (item.optInt("stuStatus", -1)) {
                    0 -> "待互评"; (if (kind == "exam") 3 else 4) -> "已互评"; else -> "状态待确认"
                }
                // taskUrl 可能是相对路径（新版接口实际返回 /mooc2/work/task?...）；
                // 必须先用列表请求的 url 解析成绝对地址，否则 releasedLink 永远为 false。
                val resolvedTaskUrl = item.optString("taskUrl")
                    .takeIf { it.isNotBlank() }
                    ?.let { url.resolve(it) }
                val workLink = if (kind == "homework") resolvedTaskUrl?.takeIf(::validDetailLink) else null
                if (status == "未交") {
                    val now = System.currentTimeMillis()
                    val releasedLink = resolvedTaskUrl?.let {
                        it.scheme == "https" && it.host == "mooc1.chaoxing.com" && it.port == 443 &&
                            it.encodedUsername.isEmpty() && it.encodedPassword.isEmpty() &&
                            it.encodedPath in setOf("/mooc-ans/mooc2/work/task", "/mooc2/work/task")
                    } == true
                    val released = kind == "homework" && isReleasedHomeworkVariant(item, releasedLink, now)
                    val future = kind == "homework" && item.optLong("startTime") > now
                    if (future || (item.has("taskStatus") && item.optInt("taskStatus") != 2 && !released)) status = "状态待确认"
                }
                entries.add(HomeworkEntry(item.optString("title"), status, item.optLong("endTime").takeIf { it > 0 }, if (kind == "homework") id else "", id))
                workLink?.let {
                    accesses.put(course.id, course.classId, id, it, url.toString())
                }
            }
            if ((json.has("total") && entries.size >= json.getInt("total")) || (!json.has("total") && rows.length() < 50)) return entries
            check(rows.length() > 0) { "新版任务分页未完成" }
        }
        error("新版任务分页超限")
    }

    private fun rememberAccesses(course: ChaoxingCourse, url: HttpUrl, html: String) {
        Jsoup.parse(html).select("li[data]").forEach { item ->
            val link = url.resolve(item.attr("data")) ?: return@forEach
            val id = link.queryParameter("workId").orEmpty()
            if (id.isNotBlank() && validDetailLink(link)) accesses.put(course.id, course.classId, id, link, url.toString())
        }
    }

    /**
     * 仅接受课程页提供的官方只读入口，绝不猜测签名或打开考试作答页面。
     * 课程本身没有讨论/考试入口是合法情况，返回 null 由调用方按空列表处理。
     */
    private fun taskEntrance(course: ChaoxingCourse, kind: String): Pair<HttpUrl, String>? {
        val page = coursePages[course.id to course.classId] ?: run {
            val fetched = coursePage(course, task = true)
            coursePages[course.id to course.classId] = fetched
            fetched
        }
        val document = Jsoup.parse(page.second)
        val candidates = document.select("a[href],iframe[src],input[value]").mapNotNull {
            page.first.resolve(it.attr(if (it.tagName() == "a") "href" else if (it.tagName() == "iframe") "src" else "value"))
        }.toMutableList()
        // data-url 是官方旧版导航，参数来自当前课程页隐藏字段，不能丢掉签名。
        fun field(id: String) = document.getElementById(id)?.attr("value").orEmpty()
        for (node in document.select("a[data-url]")) {
            val link = page.first.resolve(node.attr("data-url")) ?: continue
            val discussion = link.host == "groupweb.chaoxing.com" && link.encodedPath == "/course/topic/topicList"
            val exam = link.host in setOf("mooc1.chaoxing.com", "mooc2-ans.chaoxing.com") && link.encodedPath in setOf("/exam-ans/mooc2/exam/exam-list", "/mooc2-ans/exam/exam-list")
            if (!discussion && !exam) continue
            val special = discussion && field("courseApp") == "2" && field("bbsUrlSwitch") == "false"
            val params = mutableMapOf((if (special) "courseId" else "courseid") to field("courseid"), "clazzid" to field("clazzid"), "cpi" to field("cpi"), "ut" to "s", "t" to field("t"), "stuenc" to field("enc"), "enc" to field(if (discussion) { if (special) "oldenc" else "enc" } else "examEnc"), "pageHeader" to if (discussion) "2" else "9")
            if (discussion) {
                if (special) { params["openc"] = field("openc"); params["hideHead"] = "true" } else params["bbsid"] = field("bbsid")
                for (key in listOf("learnSilverStartTime", "learnSilverEndTime", "showAIEnc")) if (field(key).isNotBlank()) params[key] = field(key)
                params["supportAI"] = field("showAiAssistantTea").ifBlank { "false" }
            } else params["openc"] = field("openc")
            val builder = link.newBuilder()
            for ((key, value) in params) if (link.queryParameter(key) == null) builder.addQueryParameter(key, value)
            candidates.add(builder.build())
        }
        val target = candidates.firstOrNull { link -> link.scheme == "https" && if (kind == "discussion")
            link.host == "groupweb.chaoxing.com" && link.encodedPath == "/course/topic/topicList"
        // 现行考试列表入口在 mooc2-ans 域；保留旧的 mooc1 路径以兼容不同课程页。
        else (link.host == "mooc2-ans.chaoxing.com" && link.encodedPath == "/mooc2-ans/exam/exam-list") ||
            (link.host == "mooc1.chaoxing.com" && link.encodedPath == "/exam-ans/mooc2/exam/exam-list") }
            ?: return null
        return target to page.first.toString()
    }

    private fun loadOtherTasks(course: ChaoxingCourse, kind: String): List<HomeworkEntry> {
        // 课程页没有对应入口说明该课程没有这类任务，按空列表覆盖旧缓存。
        val entrance = taskEntrance(course, kind)
        if (entrance == null && modernCoursePages.containsKey(course.id to course.classId)) throw LearningTaskUnsupportedException("任务入口无法确认，保留已有缓存")
        val (target, referer) = entrance ?: return emptyList()
        if (kind == "discussion") return discussionList(course, target, referer)
        val (finalUrl, html) = fetchTask(target, referer)
        check(finalUrl.host == target.host && finalUrl.encodedPath == target.encodedPath) { "任务入口发生跳转" }
        return parseLearningExamList(html)
    }

    private fun otherTaskDetail(row: CachedHomework): String {
        if (row.kind == "exam") return "<p>考试信息以学习通官方页面为准。</p>"
        val identity = Regex("[\\w-]+")
        check(row.kind == "discussion" && identity.matches(row.bbsId) && identity.matches(row.taskId)) { "请刷新讨论列表后重试" }
        val target = "https://groupweb.chaoxing.com/".toHttpUrl().newBuilder()
            .addPathSegments("course/topic").addPathSegment(row.taskId).addPathSegment("getTopic").build()
        val (url, html) = fetchTask(target)
        check(url.host == target.host && url.encodedPath == target.encodedPath) { "讨论详情发生跳转" }
        return parseLearningDiscussionDetail(JSONObject(html), row.taskId, row.bbsId)
    }

    /** 优先复用刚同步的官方入口，避免每次展开都重新进入课程并轮换会话。 */
    fun detail(row: CachedHomework): String = detailContent(row, includeDocumentNames = true).html

    internal fun detailContent(row: CachedHomework, includeDocumentNames: Boolean = false): HomeworkDetails {
        if (row.kind != "homework") return HomeworkDetails(otherTaskDetail(row))
        return readHomework(row, includeDocumentNames).second
    }

    private fun readHomework(
        row: CachedHomework, includeDocumentNames: Boolean = false, requireDocumentSource: Boolean = false
    ): Pair<HttpUrl, HomeworkDetails> {
        check(row.workId.isNotBlank() && row.cpi.isNotBlank()) { "请先刷新作业列表，再查看详情" }
        fun read(access: Pair<HttpUrl, String>) = readDetail(
            access.first, access.second, includeDocumentNames, row.workId.takeIf { requireDocumentSource }
        )
        val recent = accesses.get(row.courseId, row.classId, row.workId)
        if (recent != null) {
            try { return read(recent) } catch (e: CancellationException) { throw e
            } catch (_: HomeworkDetailException) {
                accesses.remove(row.courseId, row.classId, row.workId)
            }
        }
        val course = ChaoxingCourse(row.courseId, row.classId, row.cpi, row.courseName)
        val entrance = detailStep("无法获取作业列表，请刷新列表后重试") { coursePage(course) }
        val access = freshHomeworkAccess(course, row, entrance)
        return read(access)
    }

    /** 外跳和详情共用相同入口恢复逻辑，只打开列表实际返回的只读作业地址。 */
    fun homeworkAppEntry(row: CachedHomework): String {
        check(row.workId.isNotBlank() && row.cpi.isNotBlank())
        val course = ChaoxingCourse(row.courseId, row.classId, row.cpi, row.courseName)
        return freshHomeworkAccess(course, row, coursePage(course)).first.toString()
    }

    /** 每次重新定位都先清理旧签名，防止撤回的作业继续使用缓存入口。 */
    private fun freshHomeworkAccess(course: ChaoxingCourse, row: CachedHomework, entrance: Pair<HttpUrl, String>): Pair<HttpUrl, String> {
        accesses.remove(row.courseId, row.classId, row.workId)
        if (entrance.first.encodedPath != "/mooc2-ans-vue/fanyav3/stu") try {
            val (url, html) = workPage(course, entrance)
            // 详情定位只认列表实际返回的身份；不要求每行都具有任务筛选字段。
            rememberAccesses(course, url, html)
            accesses.get(row.courseId, row.classId, row.workId)?.let { return it }
        } catch (e: CancellationException) { throw e } catch (_: Exception) { ensureNotCancelled() }
        if (!modernCoursePages.containsKey(course.id to course.classId)) throw HomeworkDetailException("未找到作业入口")
        detailStep("无法获取作业列表，请刷新列表后重试") { modernTasks(course, "homework") }
        return accesses.get(row.courseId, row.classId, row.workId) ?: throw HomeworkDetailException("未找到作业入口")
    }

    private fun validDetailLink(url: HttpUrl): Boolean = url.scheme == "https" &&
        url.host == "mooc1.chaoxing.com" &&
        url.encodedPath in setOf("/mooc-ans/mooc2/work/task", "/mooc2/work/task")

    private fun readDetail(
        target: HttpUrl, referer: String, includeDocumentNames: Boolean, documentWorkId: String?
    ): Pair<HttpUrl, HomeworkDetails> {
        val (url, body) = detailStep("详情请求失败，请检查网络后重试") {
            if (documentWorkId == null) fetch(target, referer) else fetchDocumentSource(target, referer, documentWorkId)
        }
        return url to detailStep("详情页面无法识别，请重新登录后重试") {
            parseHomeworkDetailContent(body, includeDocumentNames)
        }
    }

    private fun fetchDocumentSource(target: HttpUrl, referer: String, workId: String): Pair<HttpUrl, String> {
        // 官网作业入口会跳转到详情。仅放行同一作业的一次 HTTPS 跳转。
        check(validDetailLink(target) && target.port == 443 && target.username.isEmpty() && target.password.isEmpty() &&
            target.queryParameterValues("workId") == listOf(workId))
        var current = target
        var source = referer
        repeat(2) { hop ->
            val request = Request.Builder().url(current).header("Referer", source)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/130.0.0.0 Safari/537.36")
                .build()
            executeCancellable(taskClient, request) { response ->
                if (hop == 0 && response.code in 301..303) {
                    val next = response.header("Location")?.let { homeworkDocumentSourceRedirect(current, it, workId) }
                        ?: error("学习通作业详情跳转异常")
                    source = current.toString()
                    current = next
                    return@executeCancellable
                }
                check(response.isSuccessful)
                return current to (response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES) ?: error("学习通返回空响应"))
            }
        }
        error("学习通作业详情跳转异常")
    }

    internal fun downloadHomeworkDocument(row: CachedHomework, document: HomeworkDocument, destination: File) {
        // 缓存只用于显示。点击后重新从作业实际页面核对身份与下载权限。
        check(row.kind == "homework" && document.downloadAllowed)
        val (source, current) = readHomework(row, requireDocumentSource = true)
        if (document !in current.documents) throw HomeworkDetailException("附件或查看权限已变化，请刷新作业详情。")
        val target = "https://mooc1.chaoxing.com/mooc-ans/ueditorupload/read".toHttpUrl().newBuilder()
            .addQueryParameter("objectId", document.objectId)
            .addQueryParameter("fileOriName", document.name)
            .build()
        val request = Request.Builder().url(target).header("Referer", source.toString())
            .header("Origin", "https://mooc1.chaoxing.com").post(FormBody.Builder().build()).build()
        detailStep("学习通附件预览页加载失败，请在学习通查看。") {
            executeCancellable(taskClient, request) { response ->
                check(response.code == 200)
                val html = response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES) ?: error("附件预览未返回内容")
                check(isHomeworkDocumentPreview(Jsoup.parse(html), document.objectId))
            }
        }
        // PDF 与 DOCX 都使用官网查看器返回的 PDF 预览地址。
        val status = target.newBuilder().encodedPath("/ananas/status/${document.objectId}")
            .query(null).addQueryParameter("flag", "normal").build()
        val statusRequest = Request.Builder().url(status).header("Referer", target.toString()).build()
        val download = detailStep("学习通暂未提供可读取的 PDF 预览，请在学习通查看。") {
            val statusJson = executeCancellable(taskClient, statusRequest) { response ->
                check(response.code == 200)
                response.body?.readStringLimited(MAX_JSON_RESPONSE_BYTES) ?: error("附件转换状态为空")
            }
            homeworkConvertedPdfUrl(statusJson, document.objectId) ?: error("附件预览入口无法识别")
        }
        executeCancellable(documentCdn, Request.Builder().url(download).build()) { response ->
            check(response.code == 200)
            val body = response.body ?: error("附件为空")
            check(body.contentLength() <= MAX_HOMEWORK_DOCUMENT_BYTES)
            body.byteStream().use { input ->
                destination.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_HOMEWORK_DOCUMENT_BYTES)
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
        check(destination.matchesHomeworkPdf()) { "文件格式与附件不符" }
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

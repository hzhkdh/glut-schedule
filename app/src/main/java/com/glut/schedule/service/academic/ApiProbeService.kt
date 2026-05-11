package com.glut.schedule.service.academic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class ApiProbeService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class ProbeResult(
        val url: String,
        val method: String,
        val httpCode: Int,
        val contentType: String,
        val body: String,
        val bodyLength: Int
    )

    data class AcademicCalendar(
        val currentWeekNumber: Int,
        val semesterStartMonday: LocalDate,
        val semesterEndDate: LocalDate? = null
    )

    suspend fun probeAllEndpoints(
        cookie: String,
        capturedTimetableUrls: List<String> = emptyList()
    ): List<ProbeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ProbeResult>()
        val baseHeaders = mapOf(
            "Cookie" to cookie,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
            "Accept" to "text/html,application/json,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Referer" to "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do"
        )

        fun probeGet(url: String) {
            try {
                val req = Request.Builder().url(url)
                baseHeaders.forEach { (k, v) -> req.header(k, v) }
                client.newCall(req.build()).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    results.add(ProbeResult(url, "GET", resp.code,
                        resp.header("content-type") ?: "", body.take(100000), body.length))
                }
            } catch (e: Exception) {
                results.add(ProbeResult(url, "GET", -1, "", e.message ?: "error", 0))
            }
        }

        fun probePost(url: String, body: String = "") {
            try {
                val req = Request.Builder().url(url)
                baseHeaders.forEach { (k, v) -> req.header(k, v) }
                req.header("Content-Type", "application/x-www-form-urlencoded")
                if (body.isNotBlank()) {
                    req.post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                } else {
                    req.post("".toRequestBody(null))
                }
                client.newCall(req.build()).execute().use { resp ->
                    val respBody = resp.body?.string() ?: ""
                    results.add(ProbeResult(url, "POST", resp.code,
                        resp.header("content-type") ?: "", respBody.take(100000), respBody.length))
                }
            } catch (e: Exception) {
                results.add(ProbeResult(url, "POST", -1, "", e.message ?: "error", 0))
            }
        }

        buildProbeUrls(capturedTimetableUrls).forEach(::probeGet)

        probePost("http://jw.glut.edu.cn/academic/personal/framePage.do")
        probeGet("http://jw.glut.edu.cn/academic/manager/coursearrange/graphicalBasicInfo.do")
        buildCurrentStudentTimetableUrls(results).forEach(::probeGet)

        // 登录后的传统“本学期课程安排”页面，只作为 showTimetable 网格页失败时的兜底。
        probeGet("http://jw.glut.edu.cn/academic/student/currcourse/currcourse.jsdo")

        val today = LocalDate.now()
        probePost("http://jw.glut.edu.cn/academic/personal/currentTodayPlan.do?currentDate=${today.year}-${today.monthValue}-${today.dayOfMonth}")
        probePost("http://jw.glut.edu.cn/academic/personal/moduleMenu.do")
        probePost("http://jw.glut.edu.cn/academic/personal/myTodo.do")

        probeGet("http://jw.glut.edu.cn/academic/student/timetable/timetable.do")
        probeGet("http://jw.glut.edu.cn/academic/student/coursetable/coursetable.do")

        results
    }

    companion object {
        fun buildProbeUrls(capturedTimetableUrls: List<String>): List<String> {
            return capturedTimetableUrls
                .asSequence()
                .map { it.trim() }
                .filter { it.contains("showTimetable.do", ignoreCase = true) }
                .filter { it.contains("timetableType=STUDENT", ignoreCase = true) }
                .filter { Regex("""[?&]id=\d+""").containsMatchIn(it) }
                .distinct()
                .toList()
        }

        fun buildCurrentStudentTimetableUrls(results: List<ProbeResult>): List<String> {
            val frameBodies = results
                .filter { result ->
                    result.url.contains("/personal/framePage.do", ignoreCase = true) ||
                        (result.body.contains("schoolCalendarAlias") && result.body.contains("\"user\""))
                }
                .map { it.body }

            val studentId = frameBodies.firstNotNullOfOrNull { body ->
                Regex(""""user"\s*:\s*\{[^}]*"id"\s*:\s*(\d+)""")
                    .find(body)
                    ?.groupValues
                    ?.get(1)
            }.orEmpty()
            if (studentId.isBlank()) return emptyList()

            val yearCandidates = buildList {
                frameBodies.forEach { body ->
                    Regex(""""schoolCalendarAlias"\s*:\s*"(\d{4})([春秋])"""")
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.let { calendarYear ->
                            val academicYearId = calendarYear - 1980
                            add(academicYearId.toString())
                            add((academicYearId - 1).toString())
                            add((academicYearId + 1).toString())
                        }
                    Regex(""""year"\s*:\s*"(\d{4})"""")
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()
                        ?.let { add((it - 1980).toString()) }
                }
                results.forEach { result ->
                    Regex(""""arrangeCourseYear"\s*:\s*(\d+)""")
                        .find(result.body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.takeIf { it.toIntOrNull() != null }
                        ?.let(::add)
                }
                add(AcademicImportConfig.defaultYearId)
            }.distinct().filter { it.toIntOrNull() in 1..99 }.take(6)

            val termCandidates = buildList {
                frameBodies.forEach { body ->
                    Regex(""""schoolCalendarAlias"\s*:\s*"\d{4}([春秋])"""")
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { add(if (it == "秋") "2" else "1") }
                    Regex(""""term"\s*:\s*"([春秋])"""")
                        .find(body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.let { add(if (it == "秋") "2" else "1") }
                }
                results.forEach { result ->
                    Regex(""""arrangeCourseTerm"\s*:\s*(\d+)""")
                        .find(result.body)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.takeIf { it.toIntOrNull() != null }
                        ?.let(::add)
                }
                add(AcademicImportConfig.defaultTermId)
                add("2")
            }.distinct().filter { it.toIntOrNull() in 1..3 }.take(3)

            return yearCandidates.flatMap { yearId ->
                termCandidates.map { termId ->
                    AcademicImportConfig.buildStudentTimetableUrl(
                        studentId = studentId,
                        yearId = yearId,
                        termId = termId
                    )
                }
            }
        }

        fun extractAcademicCalendar(results: List<ProbeResult>): AcademicCalendar? {
            val body = results.firstOrNull { result ->
                result.url.contains("/personal/framePage.do", ignoreCase = true) ||
                    result.body.contains("schoolCalendarStartDate")
            }?.body ?: return null

            val currentWeek = Regex(""""whichweek"\s*:\s*(\d{1,2})""")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: return null
            val semesterStart = Regex(""""schoolCalendarStartDate"\s*:\s*"(\d{4}-\d{2}-\d{2})"""")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
                ?: return null
            val semesterEnd = Regex(""""schoolCalendarEndDate"\s*:\s*"(\d{4}-\d{2}-\d{2})"""")
                .find(body)
                ?.groupValues
                ?.get(1)
                ?.let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }

            return AcademicCalendar(
                currentWeekNumber = currentWeek,
                semesterStartMonday = semesterStart,
                semesterEndDate = semesterEnd
            )
        }

        fun findTodayPlanJsonResult(results: List<ProbeResult>): ProbeResult? {
            return results.firstOrNull { result ->
                result.httpCode == 200 &&
                    result.url.contains("/personal/currentTodayPlan.do", ignoreCase = true) &&
                    result.body.trimStart().startsWith("{") &&
                    result.body.contains(""""data"""") &&
                    result.body.contains(""""arrangeDate"""") &&
                    result.body.contains(""""time"""") &&
                    result.body.contains(""""name"""")
            }
        }
    }

    fun analyzeResults(results: List<ProbeResult>): ApiProbeAnalysis {
        val successful = results.filter { it.httpCode == 200 && it.bodyLength > 500 }
        val timetableCandidates = successful.filter { r ->
            timetableHtmlScore(r) > 0 || looksLikeTimetableJson(r.body)
        }
        val jsonResponses = successful.filter {
            it.contentType.contains("json") || it.body.trimStart().startsWith("{") ||
                it.body.trimStart().startsWith("[")
        }

        val errorResults = results.filter {
            it.httpCode != 200 || it.body.contains("错误") || it.body.contains("error")
        }

        return ApiProbeAnalysis(
            totalProbes = results.size,
            successCount = successful.size,
            timetableCandidates = timetableCandidates,
            jsonResponses = jsonResponses,
            errorResults = errorResults,
            allResults = results
        )
    }

    data class ApiProbeAnalysis(
        val totalProbes: Int,
        val successCount: Int,
        val timetableCandidates: List<ProbeResult>,
        val jsonResponses: List<ProbeResult>,
        val errorResults: List<ProbeResult>,
        val allResults: List<ProbeResult>
    ) {
        val summary: String
            get() = buildString {
                appendLine("API探测完成: $totalProbes 个请求")
                appendLine("成功: $successCount 个")
                appendLine("含课表数据: ${timetableCandidates.size} 个")
                appendLine("JSON响应: ${jsonResponses.size} 个")
                if (timetableCandidates.isNotEmpty()) {
                    appendLine("---课表候选---")
                    timetableCandidates.forEach { r ->
                        appendLine("  ${r.url.takeLast(80)} [${r.httpCode}] ${r.bodyLength}B")
                    }
                }
                if (errorResults.isNotEmpty()) {
                    appendLine("---错误响应---")
                    errorResults.take(5).forEach { r ->
                        val preview = r.body.take(100).replace("\n", " ")
                        appendLine("  ${r.url.takeLast(60)} [${r.httpCode}] $preview")
                    }
                }
            }
    }

    fun findTimetableHtmlResult(results: List<ProbeResult>): ProbeResult? {
        return results
            .asSequence()
            .filter { it.httpCode == 200 && it.body.length > 100 }
            .map { it to timetableHtmlScore(it) }
            .filter { (_, score) -> score > 0 }
            .sortedByDescending { (_, score) -> score }
            .firstOrNull()
            ?.first
    }

    fun extractTimetableHtml(results: List<ProbeResult>): String? {
        return findTimetableHtmlResult(results)?.body
    }

    fun findTimetableJsonResult(results: List<ProbeResult>): ProbeResult? {
        return results
            .filter { it.httpCode == 200 && it.body.length > 100 }
            .firstOrNull { r ->
                looksLikeTimetableJson(r.body)
            }
    }

    fun extractTimetableJson(results: List<ProbeResult>): String? {
        return findTimetableJsonResult(results)?.body
    }

    private fun timetableHtmlScore(result: ProbeResult): Int {
        val body = result.body
        val trimmed = body.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return 0
        if (isClassTimetableResult(result)) return 0
        if (isShowTimetableResult(result) && !isStudentTimetableResult(result)) return 0
        if (!body.contains("<table", ignoreCase = true) && !body.contains("<td", ignoreCase = true)) return 0
        if (body.contains("学年传递错误") ||
            body.contains("学生只能查看本人课表") ||
            (body.contains("登录") && body.contains("密码") && !body.contains("课程名称"))
        ) return 0

        var score = 0
        if (result.url.contains("showTimetable.do", ignoreCase = true)) score += 30
        if (body.contains("""id="timetable"""", ignoreCase = true) ||
            body.contains("""id='timetable'""", ignoreCase = true)
        ) score += 15
        if (body.contains("<<") && body.contains(">>")) score += 8
        if (body.contains("周一") || body.contains("星期一")) score += 4
        if (result.url.contains("currcourse", ignoreCase = true)) score += 10
        if (body.contains("本学期课程安排")) score += 8
        if (body.contains("课程名称")) score += 4
        if (body.contains("任课教师") || body.contains("教师")) score += 3
        if (body.contains("上课时间") && body.contains("地点")) score += 5
        if (body.contains("星期") && body.contains("第") && body.contains("节")) score += 3
        if (body.contains("暂无数据") && score < 10) return 0
        return score.takeIf { it >= 8 } ?: 0
    }

    private fun isShowTimetableResult(result: ProbeResult): Boolean {
        return result.url.contains("showTimetable.do", ignoreCase = true)
    }

    private fun isStudentTimetableResult(result: ProbeResult): Boolean {
        return result.url.contains("timetableType=STUDENT", ignoreCase = true) ||
            result.body.contains("timetableType=STUDENT", ignoreCase = true)
    }

    private fun isClassTimetableResult(result: ProbeResult): Boolean {
        return result.url.contains("timetableType=CLASS", ignoreCase = true) ||
            result.body.contains("timetableType=CLASS", ignoreCase = true) ||
            result.body.contains("班级课表")
    }

    private fun looksLikeTimetableJson(body: String): Boolean {
        val trimmed = body.trimStart()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false

        val hasTitle = body.contains("课程名称") ||
            body.contains("courseName", ignoreCase = true) ||
            body.contains("kcmc", ignoreCase = true)
        val hasTeacher = body.contains("任课教师") ||
            body.contains("teacher", ignoreCase = true) ||
            body.contains("jsxm", ignoreCase = true)
        val hasRoom = body.contains("教室") ||
            body.contains("地点") ||
            body.contains("room", ignoreCase = true) ||
            body.contains("jsmc", ignoreCase = true)
        val hasTime = body.contains("上课时间") ||
            body.contains("sksj", ignoreCase = true) ||
            body.contains("dayOfWeek", ignoreCase = true) ||
            body.contains("startSection", ignoreCase = true) ||
            body.contains("arrangeDate", ignoreCase = true)

        return hasTitle && hasTeacher && hasRoom && hasTime
    }
}

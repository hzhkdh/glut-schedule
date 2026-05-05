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

    suspend fun probeAllEndpoints(cookie: String): List<ProbeResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ProbeResult>()
        val baseHeaders = mapOf(
            "Cookie" to cookie,
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36",
            "Accept" to "text/html,application/json,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Referer" to "http://jw.glut.edu.cn/academic/preGotoAffairFrame.do"
        )

        val yearIds = listOf(
            AcademicImportConfig.defaultYearId.toInt(),
            47,
            45,
            44
        ).distinct()
        val termIds = listOf(
            AcademicImportConfig.defaultTermId.toInt(),
            2,
            3
        ).distinct()

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

        for (yid in yearIds) {
            for (tid in termIds) {
                probeGet(
                    AcademicImportConfig.buildStudentTimetableUrl(
                        yearId = yid.toString(),
                        termId = tid.toString()
                    )
                )
            }
        }

        probeGet("http://jw.glut.edu.cn/academic/manager/coursearrange/graphicalBasicInfo.do")
        val today = LocalDate.now()
        probePost("http://jw.glut.edu.cn/academic/personal/currentTodayPlan.do?currentDate=${today.year}-${today.monthValue}-${today.dayOfMonth}")
        probePost("http://jw.glut.edu.cn/academic/personal/moduleMenu.do")
        probePost("http://jw.glut.edu.cn/academic/personal/framePage.do")
        probePost("http://jw.glut.edu.cn/academic/personal/myTodo.do")
        probeGet(AcademicImportConfig.directTimetableUrl)

        probeGet("http://jw.glut.edu.cn/academic/student/currcourse/currcourse.jsdo")
        probeGet("http://jw.glut.edu.cn/academic/student/timetable/timetable.do")
        probeGet("http://jw.glut.edu.cn/academic/student/coursetable/coursetable.do")

        results
    }

    fun analyzeResults(results: List<ProbeResult>): ApiProbeAnalysis {
        val successful = results.filter { it.httpCode == 200 && it.bodyLength > 500 }
        val timetableCandidates = successful.filter { r ->
            val body = r.body
            (body.contains("课表") || body.contains("课程") || body.contains("星期") ||
                body.contains("周一") || body.contains("timetable", ignoreCase = true) ||
                body.contains("course", ignoreCase = true)) &&
                !body.contains("学年传递错误") && !body.contains("登录") &&
                !body.contains("暂无数据")
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

    fun extractTimetableHtml(results: List<ProbeResult>): String? {
        return results
            .filter { it.httpCode == 200 && it.bodyLength > 500 }
            .firstOrNull { r ->
                val body = r.body
                body.contains("周一") && body.contains("节") &&
                    (body.contains("<table") || body.contains("<td")) &&
                    !body.contains("学年传递错误") &&
                    !body.contains("暂无数据")
            }?.body
    }

    fun extractTimetableJson(results: List<ProbeResult>): String? {
        return results
            .filter { it.httpCode == 200 && it.bodyLength > 100 }
            .firstOrNull { r ->
                val body = r.body.trimStart()
                (body.startsWith("{") || body.startsWith("[")) &&
                    (body.contains("course") || body.contains("课程") ||
                        body.contains("课表") || body.contains("timetable"))
            }?.body
    }
}

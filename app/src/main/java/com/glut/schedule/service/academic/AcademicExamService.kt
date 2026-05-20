package com.glut.schedule.service.academic

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.service.parser.ExamParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class AcademicExamService(
    private val examParser: ExamParser,
    private val apiProbeService: ApiProbeService = ApiProbeService()
) {
    var lastSuccessfulExamUrl: String = ""
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun fetchExamData(
        cookie: String,
        storedExamApiUrl: String = ""
    ): Result<List<ExamInfo>> {
        if (cookie.isBlank()) return Result.failure(IllegalStateException("请先登录教务系统"))

        // Try the stored URL first, then fall back to the known access module URL
        val urlsToTry = buildList {
            if (storedExamApiUrl.isNotBlank()) add(storedExamApiUrl)
            add("http://jw.glut.edu.cn/academic/accessModule.do?moduleId=2030")
        }.distinct()

        for (url in urlsToTry) {
            val result = fetchFromUrl(url, cookie)
            if (result.isSuccess && result.getOrNull()?.isNotEmpty() == true) {
                lastSuccessfulExamUrl = url
                return result
            }
        }

        val probeResults = apiProbeService.probeAllEndpoints(cookie = cookie)
        val selected = selectExamDataFromProbeResults(probeResults)
        if (selected != null) {
            lastSuccessfulExamUrl = selected.url
            return Result.success(filterCurrentSemester(selected.exams))
        }

        return Result.failure(IllegalStateException("会话已过期，请在课表导入页面重新登录后刷新"))
    }

    data class SelectedExamData(
        val url: String,
        val exams: List<ExamInfo>
    )

    fun selectExamDataFromProbeResults(results: List<ApiProbeService.ProbeResult>): SelectedExamData? {
        val jsonResult = apiProbeService.findExamJsonResult(results)
        if (jsonResult != null) {
            val exams = examParser.parseExamJson(jsonResult.body)
            if (exams.isNotEmpty()) return SelectedExamData(jsonResult.url, exams)
        }

        val htmlResult = apiProbeService.findExamHtmlResult(results)
        if (htmlResult != null) {
            val exams = examParser.parseExamHtml(htmlResult.body)
            if (exams.isNotEmpty()) return SelectedExamData(htmlResult.url, exams)
        }

        return null
    }

    private suspend fun fetchFromUrl(url: String, cookie: String): Result<List<ExamInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Accept", "application/json, text/html, */*")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful || body.isBlank()) {
                    Result.failure(IllegalStateException("请求失败"))
                } else {
                    val trimmed = body.trimStart()
                    val exams = if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                        examParser.parseExamJson(body)
                    } else {
                        examParser.parseExamHtml(body)
                    }
                    if (exams.isNotEmpty()) {
                        Result.success(filterCurrentSemester(exams))
                    } else {
                        Result.failure(IllegalStateException("解析结果为空"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun filterCurrentSemester(exams: List<ExamInfo>): List<ExamInfo> {
        val now = LocalDate.now()
        val cutoff = now.plusMonths(6)
        val filtered = exams.filter { exam ->
            exam.examDate.isBefore(cutoff) && exam.examDate.isAfter(now.minusMonths(6))
        }
        return filtered.ifEmpty { exams }
    }
}

package com.glut.schedule.service.academic

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.service.parser.ExamParser

class AcademicExamService(
    private val examParser: ExamParser,
    private val apiProbeService: ApiProbeService = ApiProbeService()
) {
    var lastSuccessfulExamUrl: String = ""
        private set

    suspend fun fetchExamData(
        cookie: String,
        storedExamApiUrl: String = "",
        baseUrl: String = "http://jw.glut.edu.cn"
    ): Result<List<ExamInfo>> {
        if (cookie.isBlank()) return Result.failure(IllegalStateException("请先登录教务系统"))

        val probeResults = apiProbeService.probeExamEndpoints(
            cookie = cookie,
            storedExamApiUrl = storedExamApiUrl,
            baseUrl = baseUrl
        )
        val selected = selectExamDataFromProbeResults(probeResults)
        if (selected != null) {
            lastSuccessfulExamUrl = selected.url
            return Result.success(selected.exams)
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
}

package com.glut.schedule.service.academic

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.service.parser.ExamParser
import org.jsoup.Jsoup
import java.io.IOException

/** 仅明确的登录响应触发后台续期；网络与解析错误保留原有缓存。 */
class AcademicExamSessionExpiredException : IOException("会话已过期，请重新登录后刷新")

class AcademicExamService(
    private val examParser: ExamParser,
    private val apiProbeService: ApiProbeService = ApiProbeService()
) {
    var lastSuccessfulExamUrl: String = ""
        private set
    var lastUpdatedCookie: String = ""
        private set

    suspend fun fetchExamData(
        cookie: String,
        storedExamApiUrl: String = "",
        baseUrl: String = AcademicLoginResult.DEFAULT_GUILIN_URL
    ): Result<List<ExamInfo>> {
        lastUpdatedCookie = cookie
        if (cookie.isBlank()) return Result.failure(AcademicExamSessionExpiredException())

        val probeResults = apiProbeService.probeExamEndpoints(
            cookie = cookie,
            storedExamApiUrl = storedExamApiUrl,
            baseUrl = baseUrl
        )
        val selected = selectExamDataFromProbeResults(probeResults)
        lastUpdatedCookie = probeResults.lastOrNull()?.updatedCookie?.ifBlank { cookie } ?: cookie
        if (selected != null) {
            lastSuccessfulExamUrl = selected.url
            return Result.success(selected.exams)
        }

        val expired = probeResults.any { it.httpCode == 401 || looksLikeAcademicLoginPage(it.body) }
        return Result.failure(if (expired) AcademicExamSessionExpiredException()
            else IOException("暂未取得可识别的考试安排，请稍后重试"))
    }

    data class SelectedExamData(
        val url: String,
        val exams: List<ExamInfo>
    )

    fun selectExamDataFromProbeResults(results: List<ApiProbeService.ProbeResult>): SelectedExamData? {
        // 每个候选都单独解析，首个形似考试但无法解析的页面不能遮住后续有效响应。
        for (result in results) {
            if (result.httpCode != 200 || looksLikeAcademicLoginPage(result.body)) continue
            // 考试模块首页只列“最近 7 天”的考试，是摘要而不是全量，不能拿它覆盖历史缓存。
            // 例如教务新发布一场考试后，首页只剩这 1 条；若在此短路，历史考试会全部消失。
            // 这里直接跳过摘要页，让探测继续走到“全部考试安排”接口。
            if (isRecentOnlyExamPage(result.url, result.body)) continue
            val body = result.body.trimStart()
            val exams = if (body.startsWith("{") || body.startsWith("[")) {
                // 通用 name/room JSON 也可能被解析器接受，先确认存在考试字段再使用。
                if (apiProbeService.findExamJsonResult(listOf(result)) == null) continue
                examParser.parseExamJson(body)
            } else examParser.parseExamHtml(body)
            if (exams.isNotEmpty()) return SelectedExamData(result.url, exams)
            if (isRecognizableEmptyExamPage(body)) return SelectedExamData(result.url, emptyList())
        }

        return null
    }

    /**
     * 识别教务的「学生考试安排」摘要页（moduleId=2030 首页 / student/exam/index.jsdo）。
     *
     * 该页固定只列「7天前开始的全部考试安排」，真正的全量列表要 POST 到
     * studentQueryAllExamPre.do 才会出现。只有摘要页会带下面两类固定文案，
     * 用它判断比只看 URL 更稳（存储的旧地址、重定向后的地址都能覆盖）。
     */
    private fun isRecentOnlyExamPage(url: String, body: String): Boolean {
        if (body.contains("这里列出7天前") || body.contains("查看全部考试安排")) return true
        return ApiProbeService.isExamSummaryUrl(url)
    }

    private fun isRecognizableEmptyExamPage(body: String): Boolean {
        // 空数组、通用“暂无数据”或解析失败不足以清空缓存；要求完整考试表头和明确空提示。
        return Jsoup.parse(body).select("table").any { table ->
            val rows = table.select("tr")
            val headers = rows.firstOrNull()?.select("th,td")?.map { it.text() }.orEmpty()
            val examColumns = headers.any { it.contains("课程名称") || it.contains("科目") } &&
                headers.any { it.contains("考试时间") || it.contains("考试日期") } &&
                headers.any { it.contains("考试地点") || it.contains("考场") }
            val dataRows = rows.drop(1)
            examColumns && dataRows.size == 1 && dataRows.single().select("td,th").size == 1 &&
                Regex("暂无(?:考试安排|考试信息|数据)|没有考试安排|无考试安排").containsMatchIn(dataRows.single().text())
        }
    }
}

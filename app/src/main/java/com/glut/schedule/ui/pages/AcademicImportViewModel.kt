package com.glut.schedule.ui.pages

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicImportConfig
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.AcademicTodayPlanParser
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.CredentialStore
import com.glut.schedule.service.academic.DebugCaptureService
import com.glut.schedule.service.academic.hasUsableAcademicCookie
import com.glut.schedule.service.academic.isAcademicPage
import com.glut.schedule.service.academic.isExamPage
import com.glut.schedule.service.academic.isLoginPage
import com.glut.schedule.service.academic.isTimetablePage
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.parser.GlutExamParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AcademicImportUiState(
    val loginUrl: String = AcademicImportConfig.loginUrl,
    val cookie: String = "",
    val hasSession: Boolean = false,
    val isFetching: Boolean = false,
    val message: String = "请先在教务系统页面完成登录",
    val htmlPreview: String = "",
    val debugInfo: String = "",
    val currentUrl: String = "",
    val isOnTimetablePage: Boolean = false,
    val isOnExamPage: Boolean = false,
    val importedCourseCount: Int = 0,
    val importedExamCount: Int = 0,
    val apiUrls: List<String> = emptyList()
)

class AcademicImportViewModel(
    private val sessionStore: AcademicSessionStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val parser: AcademicScheduleParser,
    private val captureService: DebugCaptureService,
    private val apiProbeService: ApiProbeService,
    private val credentialStore: CredentialStore
) : ViewModel() {
    private val operationState = MutableStateFlow(
        ImportOperationState(message = "请先在教务系统页面完成登录")
    )

    val uiState: StateFlow<AcademicImportUiState> = combine(
        sessionStore.academicCookie,
        sessionStore.lastHtmlPreview,
        operationState
    ) { cookie, htmlPreview, operation ->
        AcademicImportUiState(
            cookie = cookie,
            hasSession = hasConfirmedAcademicLogin(
                cookie = cookie,
                currentUrl = operation.currentUrl,
                isLoginFormVisible = operation.isLoginFormVisible
            ),
            isFetching = operation.isFetching,
            message = operation.message,
            htmlPreview = htmlPreview,
            debugInfo = operation.debugInfo,
            currentUrl = operation.currentUrl,
            isOnTimetablePage = operation.isOnTimetablePage,
            isOnExamPage = operation.isOnExamPage,
            importedCourseCount = operation.importedCourseCount,
            importedExamCount = operation.importedExamCount,
            apiUrls = operation.apiUrls
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AcademicImportUiState()
    )

    fun onPageUrlChanged(url: String?) {
        val sanitized = url.orEmpty()
        captureLoginCredentials(sanitized)
        val isTimetable = isTimetablePage(sanitized)
        val onExam = isExamPage(sanitized)
        val current = operationState.value
        val message = if (
            hasConfirmedAcademicLogin(
                cookie = uiState.value.cookie,
                currentUrl = sanitized,
                isLoginFormVisible = current.isLoginFormVisible
            ) &&
            current.message == "请先在教务系统页面完成登录"
        ) {
            "已登录，可直接点击右下角导入按钮"
        } else {
            current.message
        }
        operationState.update {
            it.copy(currentUrl = sanitized, isOnTimetablePage = isTimetable, isOnExamPage = onExam, message = message)
        }
    }

    fun onLoginFormDetected(isVisible: Boolean) {
        val current = operationState.value
        val message = if (
            !isVisible &&
            hasConfirmedAcademicLogin(
                cookie = uiState.value.cookie,
                currentUrl = current.currentUrl,
                isLoginFormVisible = false
            ) &&
            current.message == "请先在教务系统页面完成登录"
        ) {
            "已登录，可直接点击右下角导入按钮"
        } else if (isVisible && current.message == "已登录，可直接点击右下角导入按钮") {
            "请先在教务系统页面完成登录"
        } else {
            current.message
        }
        operationState.update {
            it.copy(isLoginFormVisible = isVisible, message = message)
        }
    }

    private fun captureLoginCredentials(url: String) {
        if (!url.contains("j_acegi_security_check")) return
        val username = Regex("""[?&]j_username=([^&]+)""").find(url)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
        val password = Regex("""[?&]j_password=([^&]+)""").find(url)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
        if (username.isNotBlank() && password.isNotBlank()) {
            credentialStore.saveCredentials(username, password)
        }
    }

    fun saveCredentialsFromWebForm(username: String?, password: String?) {
        val sanitizedUsername = username.orEmpty().trim()
        val rawPassword = password.orEmpty()
        if (sanitizedUsername.isNotBlank() && rawPassword.isNotBlank()) {
            credentialStore.saveCredentials(sanitizedUsername, rawPassword)
        }
    }

    fun recordNetworkRequest(url: String, method: String, headers: Map<String, String>?) {
        captureService.recordRequest(url, method, headers)
        val apiUrls = captureService.findApiUrls()
        val timetableUrls = captureService.findTimetableUrls()
        if (apiUrls.isNotEmpty() || timetableUrls.isNotEmpty()) {
            operationState.update {
                it.copy(
                    debugInfo = captureService.clearCookieHint(),
                    apiUrls = timetableUrls.ifEmpty { apiUrls }
                )
            }
        }
    }

    fun saveCookie(cookie: String?) {
        val value = cookie.orEmpty()
        if (!hasUsableAcademicCookie(value)) return
        viewModelScope.launch {
            sessionStore.saveCookie(value)
            if (
                hasConfirmedAcademicLogin(
                    cookie = value,
                    currentUrl = operationState.value.currentUrl,
                    isLoginFormVisible = operationState.value.isLoginFormVisible
                )
            ) {
                operationState.update {
                    it.copy(message = "已登录，可直接点击右下角导入按钮")
                }
            }
        }
    }

    fun probeApis() {
        val cookie = uiState.value.cookie
        if (!hasUsableAcademicCookie(cookie)) {
            operationState.update { it.copy(message = "请先登录教务系统") }
            return
        }
        operationState.update { it.copy(isFetching = true, message = "正在探测API端点...") }
        viewModelScope.launch {
            val diagnostics = StringBuilder()
            if (!tryImportFromSessionApi(diagnostics, "手动API探测")) {
                operationState.update {
                    it.copy(
                        isFetching = false,
                        debugInfo = diagnostics.toString(),
                        message = "API探测完成，未找到可导入的完整课表\n详情见调试面板"
                    )
                }
            }
        }
    }

    fun importExamSchedule(interceptedResponsesJson: String) {
        if (interceptedResponsesJson.isBlank() || interceptedResponsesJson == "[]") {
            operationState.update { it.copy(message = "未捕获到考试数据，请先点击考试安排菜单加载数据") }
            return
        }
        operationState.update { it.copy(isFetching = true, message = "正在解析考试安排...") }
        viewModelScope.launch {
            try {
                val result = parseExamFromResponses(interceptedResponsesJson)
                finishExamImport(result)
            } catch (e: Exception) {
                operationState.update {
                    it.copy(isFetching = false, message = "解析考试数据失败: ${e.message}")
                }
            }
        }
    }

    fun importExamHtml(pageHtml: String) {
        if (pageHtml.isBlank()) {
            operationState.update { it.copy(message = "未捕获到页面数据") }
            return
        }
        operationState.update { it.copy(isFetching = true, message = "正在解析考试安排...") }
        viewModelScope.launch {
            try {
                val examParser = GlutExamParser()
                val exams = examParser.parseExamHtml(pageHtml)
                if (exams.isEmpty()) {
                    val jsonExams = examParser.parseExamJson(pageHtml)
                    finishExamImport(jsonExams)
                } else {
                    finishExamImport(exams)
                }
            } catch (e: Exception) {
                operationState.update {
                    it.copy(isFetching = false, message = "解析考试数据失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun parseExamFromResponses(jsonStr: String): List<ExamInfo> {
        val examParser = GlutExamParser()
        val responses = org.json.JSONArray(jsonStr)

        suspend fun trySaveUrl(url: String) {
            if (url.isNotBlank()) sessionStore.saveExamApiUrl(url)
        }

        for (i in 0 until responses.length()) {
            val resp = responses.getJSONObject(i)
            val body = resp.optString("body", "")
            val url = resp.optString("url", "")
            if (body.isBlank() || body.length < 50) continue

            val trimmed = body.trimStart()
            if ((trimmed.startsWith("{") || trimmed.startsWith("[")) &&
                (body.contains("考试") || body.contains("exam") ||
                    body.contains("座位") || body.contains("seat") ||
                    url.contains("exam", ignoreCase = true))
            ) {
                val parsed = examParser.parseExamJson(body)
                if (parsed.isNotEmpty()) { trySaveUrl(url); return parsed }
            }
            if (body.contains("<table", ignoreCase = true) &&
                (body.contains("考试") || url.contains("exam", ignoreCase = true))
            ) {
                val parsed = examParser.parseExamHtml(body)
                if (parsed.isNotEmpty()) { trySaveUrl(url); return parsed }
            }
        }
        for (i in 0 until responses.length()) {
            val resp = responses.getJSONObject(i)
            val body = resp.optString("body", "")
            val url = resp.optString("url", "")
            if (body.isBlank() || body.length < 50) continue
            val parsed = examParser.parseExamJson(body)
            if (parsed.isNotEmpty()) { trySaveUrl(url); return parsed }
        }
        return emptyList()
    }

    private fun finishExamImport(examResult: List<ExamInfo>) {
        viewModelScope.launch {
            if (examResult.isNotEmpty()) {
                val now = java.time.LocalDate.now()
                val sixMonthsAgo = now.minusMonths(6)
                val sixMonthsAhead = now.plusMonths(6)
                val currentExams = examResult.filter { exam ->
                    exam.examDate.isAfter(sixMonthsAgo) && exam.examDate.isBefore(sixMonthsAhead)
                }
                scheduleRepository.replaceExams(if (currentExams.isNotEmpty()) currentExams else examResult)
                operationState.update {
                    it.copy(
                        isFetching = false,
                        importedExamCount = examResult.size,
                        message = "已导入 ${examResult.size} 门考试"
                    )
                }
            } else {
                operationState.update {
                    it.copy(
                        isFetching = false,
                        message = "未找到考试安排数据，请确认已进入考试安排页面"
                    )
                }
            }
        }
    }

    private suspend fun tryImportFromSessionApi(
        diagnostics: StringBuilder,
        reason: String
    ): Boolean {
        val cookie = uiState.value.cookie
        if (!hasUsableAcademicCookie(cookie)) {
            diagnostics.appendLine("接口兜底跳过：没有可用教务登录态")
            return false
        }

        diagnostics.appendLine(reason)
        val results = runCatching {
            apiProbeService.probeAllEndpoints(
                cookie = cookie,
                capturedTimetableUrls = operationState.value.apiUrls
            )
        }.getOrElse { e ->
            diagnostics.appendLine("接口探测异常: ${e.message}")
            return false
        }

        val analysis = apiProbeService.analyzeResults(results)
        diagnostics.appendLine(analysis.summary)
        val fileMsg = saveProbeResultsToFile(results)
        diagnostics.appendLine(fileMsg)

        val calendar = ApiProbeService.extractAcademicCalendar(results)
        if (calendar != null) {
            settingsStore.setSemesterStartMonday(calendar.semesterStartMonday)
            calendar.semesterEndDate?.let { settingsStore.setSemesterEndDate(it) }
            settingsStore.setCurrentWeekNumber(calendar.currentWeekNumber)
            diagnostics.appendLine(
                buildString {
                    append("已同步教务日历: 第${calendar.currentWeekNumber}周, 学期起点 ${calendar.semesterStartMonday}")
                    calendar.semesterEndDate?.let { append(", 学期结束 $it") }
                }
            )
        }

        val examCount = tryImportExamFromProbeResults(results, diagnostics)

        val courses = parseBestProbeResult(results, diagnostics)
        if (courses.isEmpty()) {
            if (examCount > 0) {
                captureService.saveDiagnostics(
                    diagnostics.toString(), "", operationState.value.currentUrl, true
                )
                operationState.update {
                    it.copy(
                        isFetching = false, debugInfo = diagnostics.toString(),
                        message = "已导入 $examCount 门考试安排；未找到课表数据",
                        importedExamCount = examCount
                    )
                }
                return true
            }
            return false
        }

        scheduleRepository.replaceImportedCourses(courses)
        diagnostics.appendLine("成功导入 ${courses.size} 门课程")
        captureService.saveDiagnostics(
            diagnostics.toString(),
            uiState.value.htmlPreview.take(3000),
            operationState.value.currentUrl,
            true
        )
        val finalExamCount = examCount
        operationState.update {
            val importedFromTodayPlanOnly = courses.all { course -> course.id.startsWith("today-") }
            val examMsg = if (finalExamCount > 0) " + ${finalExamCount} 门考试安排" else ""
            it.copy(
                isFetching = false,
                debugInfo = diagnostics.toString(),
                message = if (importedFromTodayPlanOnly) {
                    "仅导入今日课程 ${courses.size} 门$examMsg；未拿到完整个人课表"
                } else {
                    "已成功导入 ${courses.size} 门课程$examMsg，返回首页查看"
                },
                importedCourseCount = courses.size,
                importedExamCount = finalExamCount
            )
        }
        return true
    }

    private suspend fun tryImportExamFromProbeResults(
        results: List<ApiProbeService.ProbeResult>,
        diagnostics: StringBuilder
    ): Int {
        diagnostics.appendLine("--- 考试数据诊断 ---")
        try {
            // Dump all exam-related probe URLs and their status
            val examUrlResults = results.filter { r ->
                r.url.contains("exam", ignoreCase = true) ||
                    r.url.contains("examination", ignoreCase = true)
            }
            diagnostics.appendLine("探测了 ${examUrlResults.size} 个考试相关URL:")
            examUrlResults.forEach { r ->
                val preview = r.body.take(200).replace("\n", " ").replace("\r", "")
                diagnostics.appendLine("  [${r.httpCode}] ${r.url.takeLast(70)} len=${r.bodyLength}")
                if (r.bodyLength > 20) {
                    diagnostics.appendLine("    预览: $preview")
                }
            }

            // Also check moduleMenu for exam URLs
            val menuResult = results.find { it.url.contains("moduleMenu.do") && it.httpCode == 200 }
            if (menuResult != null) {
                val discoveredUrls = ApiProbeService.extractExamUrlsFromMenuResponse(menuResult.body)
                diagnostics.appendLine("moduleMenu中发现的考试URL: $discoveredUrls")
            }

            val examParser = GlutExamParser()
            val jsonResult = apiProbeService.findExamJsonResult(results)
            diagnostics.appendLine("findExamJsonResult: ${jsonResult?.url?.takeLast(70) ?: "null"}")

            if (jsonResult != null) {
                val exams = examParser.parseExamJson(jsonResult.body)
                diagnostics.appendLine("JSON解析得到 ${exams.size} 条考试")
                if (exams.isNotEmpty()) {
                    val now = java.time.LocalDate.now()
                    val currentExams = exams.filter { exam ->
                        exam.examDate.isAfter(now.minusMonths(6)) && exam.examDate.isBefore(now.plusMonths(6))
                    }
                    val toSave = if (currentExams.isNotEmpty()) currentExams else exams
                    scheduleRepository.replaceExams(toSave)
                    sessionStore.saveExamApiUrl("http://jw.glut.edu.cn/academic/accessModule.do?moduleId=2030")
                    diagnostics.appendLine("考试导入成功: ${toSave.size} 门")
                    return toSave.size
                }
            }

            val htmlResult = apiProbeService.findExamHtmlResult(results)
            diagnostics.appendLine("findExamHtmlResult: ${htmlResult?.url?.takeLast(70) ?: "null"}")

            if (htmlResult != null) {
                val exams = examParser.parseExamHtml(htmlResult.body)
                diagnostics.appendLine("HTML解析得到 ${exams.size} 条考试")
                if (exams.isNotEmpty()) {
                    val now = java.time.LocalDate.now()
                    val currentExams = exams.filter { exam ->
                        exam.examDate.isAfter(now.minusMonths(6)) && exam.examDate.isBefore(now.plusMonths(6))
                    }
                    val toSave = if (currentExams.isNotEmpty()) currentExams else exams
                    scheduleRepository.replaceExams(toSave)
                    sessionStore.saveExamApiUrl("http://jw.glut.edu.cn/academic/accessModule.do?moduleId=2030")
                    diagnostics.appendLine("考试导入(HTML)成功: ${toSave.size} 门")
                    return toSave.size
                }
            }

            // Show all non-exam-url JSON responses that might contain exam data
            val otherJsonResponses = results.filter { r ->
                r.httpCode == 200 && r.bodyLength > 100 &&
                    (r.contentType.contains("json") || r.body.trimStart().startsWith("{") || r.body.trimStart().startsWith("[")) &&
                    !r.url.contains("exam", ignoreCase = true) &&
                    !r.url.contains("examination", ignoreCase = true)
            }
            if (otherJsonResponses.isNotEmpty()) {
                diagnostics.appendLine("其他JSON响应(${otherJsonResponses.size}个):")
                otherJsonResponses.forEach { r ->
                    val preview = r.body.take(150).replace("\n", " ")
                    diagnostics.appendLine("  ${r.url.takeLast(60)} len=${r.bodyLength}: $preview")
                }
            }

            diagnostics.appendLine("未找到考试安排数据")
        } catch (e: Exception) {
            diagnostics.appendLine("考试导入异常: ${e.message}")
        }
        return 0
    }

    private suspend fun parseBestProbeResult(
        results: List<ApiProbeService.ProbeResult>,
        diagnostics: StringBuilder
    ): List<ScheduleCourse> {
        val htmlResult = apiProbeService.findTimetableHtmlResult(results)
        val calendar = ApiProbeService.extractAcademicCalendar(results)
        if (htmlResult != null) {
            diagnostics.appendLine("使用HTML课表接口: ${htmlResult.url.takeLast(90)}")
            sessionStore.saveHtmlPreview(htmlResult.body.take(3000))
            diagnostics.appendLine(captureService.saveHtmlToFile(htmlResult.body, "found_timetable_html"))
            val courses = runCatching {
                parser.parsePersonalSchedule(htmlResult.body)
            }.getOrElse { e ->
                diagnostics.appendLine("HTML课表解析异常: ${e.message}")
                emptyList()
            }
            if (shouldUseParsedCourses(courses, calendar, results, diagnostics, "HTML课表接口")) {
                return mergeWithTodayPlanIfUseful(courses, results, calendar, diagnostics)
            }
            diagnostics.appendLine("HTML课表接口未得到可用课程")
        }

        val jsonResult = apiProbeService.findTimetableJsonResult(results)
        if (jsonResult != null) {
            diagnostics.appendLine("使用JSON课表接口: ${jsonResult.url.takeLast(90)}")
            diagnostics.appendLine(captureService.saveHtmlToFile(jsonResult.body, "found_timetable_json"))
            val courses = runCatching {
                parseApiJsonInternal(jsonResult.body, diagnostics)
            }.getOrElse { e ->
                diagnostics.appendLine("JSON解析异常: ${e.message}")
                emptyList()
            }
            if (shouldUseParsedCourses(courses, calendar, results, diagnostics, "JSON课表接口")) {
                return mergeWithTodayPlanIfUseful(courses, results, calendar, diagnostics)
            }
            diagnostics.appendLine("JSON课表接口未得到可用课程")
        }

        val todayPlan = ApiProbeService.findTodayPlanJsonResult(results)
        if (todayPlan != null && calendar != null) {
            diagnostics.appendLine("使用今日课程计划兜底: ${todayPlan.url.takeLast(90)}")
            diagnostics.appendLine(captureService.saveHtmlToFile(todayPlan.body, "today_plan_json"))
            val courses = AcademicTodayPlanParser.parse(todayPlan.body, calendar.currentWeekNumber)
            if (courses.isNotEmpty()) return courses
            diagnostics.appendLine("今日课程计划解析结果为空")
        } else if (todayPlan != null) {
            diagnostics.appendLine("发现今日课程计划，但缺少教务周次，跳过今日计划兜底")
        }

        diagnostics.appendLine("没有找到可解析的完整课表响应")
        return emptyList()
    }

    private fun mergeWithTodayPlanIfUseful(
        courses: List<ScheduleCourse>,
        results: List<ApiProbeService.ProbeResult>,
        calendar: ApiProbeService.AcademicCalendar?,
        diagnostics: StringBuilder
    ): List<ScheduleCourse> {
        val todayPlan = ApiProbeService.findTodayPlanJsonResult(results) ?: return courses
        val currentWeek = calendar?.currentWeekNumber ?: return courses
        val todayCourses = AcademicTodayPlanParser.parse(todayPlan.body, currentWeek)
        if (todayCourses.isEmpty()) return courses

        val existingSlots = courses.flatMap { course ->
            course.occurrences.map { occurrence ->
                "${course.title}|${occurrence.dayOfWeek}|${occurrence.startSection}|${occurrence.endSection}"
            }
        }.toSet()
        val additions = todayCourses.filter { course ->
            course.occurrences.any { occurrence ->
                "${course.title}|${occurrence.dayOfWeek}|${occurrence.startSection}|${occurrence.endSection}" !in existingSlots
            }
        }
        if (additions.isEmpty()) return courses

        diagnostics.appendLine("今日计划补充 ${additions.size} 门当天课程；完整个人课表仍作为主数据源")
        return courses + additions
    }

    private fun shouldUseParsedCourses(
        courses: List<ScheduleCourse>,
        calendar: ApiProbeService.AcademicCalendar?,
        results: List<ApiProbeService.ProbeResult>,
        diagnostics: StringBuilder,
        source: String
    ): Boolean {
        if (courses.isEmpty()) return false
        val currentWeek = calendar?.currentWeekNumber ?: return true
        val activeBlockCount = courses.sumOf { course ->
            course.occurrences.count { occurrence -> occurrence.isActiveInWeek(currentWeek) }
        }
        diagnostics.appendLine("$source 解析出 ${courses.size} 门课程，第${currentWeek}周可显示课程块: $activeBlockCount")
        if (activeBlockCount == 0 && ApiProbeService.findTodayPlanJsonResult(results) != null) {
            diagnostics.appendLine("$source 当前周暂未匹配到课程块，但不会用当天计划覆盖完整课表")
        }
        return true
    }

    private suspend fun saveProbeResultsToFile(results: List<ApiProbeService.ProbeResult>): String {
        val allResultsText = results.joinToString("\n\n---\n\n") { r ->
            "URL: ${r.url}\nMethod: ${r.method}\nHTTP: ${r.httpCode}\nType: ${r.contentType}\nBody(${r.bodyLength}B):\n${r.body.take(4000)}"
        }
        return captureService.saveHtmlToFile(allResultsText, "api_probe_results")
    }

    private fun parseApiJsonInternal(jsonStr: String, diagnostics: StringBuilder): List<com.glut.schedule.data.model.ScheduleCourse> {
        val courses = mutableListOf<com.glut.schedule.data.model.ScheduleCourse>()
        try {
            fun extractCourse(item: org.json.JSONObject): com.glut.schedule.data.model.ScheduleCourse? {
                val title = item.optString("kcmc", "").ifBlank {
                    item.optString("courseName", "").ifBlank {
                        item.optString("name", "").ifBlank {
                            item.optString("title", "")
                        }
                    }
                }
                if (title.isBlank()) return null
                val teacher = item.optString("jsxm", "").ifBlank {
                    item.optString("teacher", "").ifBlank { item.optString("teacherName", "") }
                }.ifBlank { "待确认" }
                val room = item.optString("jsmc", "").ifBlank {
                    item.optString("room", "").ifBlank { item.optString("classroom", "") }
                }.ifBlank { "待确认" }
                val id = "probe-${title.hashCode()}-${teacher.hashCode()}"
                val timeText = item.optString("sksj", "").ifBlank {
                    item.optString("schedule", "").ifBlank { item.optString("time", "") }
                }
                val occurrences = if (timeText.isNotBlank()) {
                    parseTimeText(timeText, id)
                } else {
                    val day = item.optInt("dayOfWeek", item.optInt("day", 0))
                    val start = item.optInt("startSection", item.optInt("start", 0))
                    val end = item.optInt("endSection", item.optInt("end", 0))
                    if (day > 0 && start > 0) {
                        listOf(com.glut.schedule.data.model.CourseOccurrence(
                            id = "$id-0", courseId = id,
                            dayOfWeek = day.coerceIn(1, 7),
                            startSection = start.coerceIn(1, 12),
                            endSection = end.coerceIn(start, 12),
                            weekText = item.optString("weekText", "1-16周"),
                            note = room
                        ))
                    } else emptyList()
                }
                if (occurrences.isEmpty()) return null
                return com.glut.schedule.data.model.ScheduleCourse(
                    id = id, title = title, room = room, teacher = teacher,
                    colorHex = CourseColorMapper.colorForCourse(id, title),
                    occurrences = occurrences
                )
            }

            fun tryJsonArray(json: String): org.json.JSONArray? {
                return try { org.json.JSONArray(json) } catch (_: Exception) { null }
            }
            fun tryJsonObject(json: String): org.json.JSONObject? {
                return try { org.json.JSONObject(json) } catch (_: Exception) { null }
            }

            val obj = tryJsonObject(jsonStr)
            if (obj != null) {
                val dataKeys = listOf("data", "result", "list", "rows", "records")
                for (key in dataKeys) {
                    val value = obj.opt(key)
                    if (value is org.json.JSONArray) {
                        for (i in 0 until value.length()) {
                            val item = value.getJSONObject(i)
                            val course = extractCourse(item)
                            if (course != null) courses.add(course)
                        }
                    }
                }
            } else {
                val arr = tryJsonArray(jsonStr)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val course = extractCourse(item)
                        if (course != null) courses.add(course)
                    }
                }
            }
        } catch (e: Exception) {
            diagnostics.appendLine("JSON解析异常: ${e.message}")
        }
        return courses
    }

    private fun parseTimeText(timeText: String, courseId: String): List<com.glut.schedule.data.model.CourseOccurrence> {
        val results = mutableListOf<com.glut.schedule.data.model.CourseOccurrence>()
        val regex = Regex(
            """([第\d,，\-－—至单双周节、\s]*)星期([一二三四五六日天])\s*第\s*(\d{1,2})\s*(?:[、,，]|至|~|-|－|—)\s*(\d{1,2})\s*节\s*([^\s<]*)"""
        )
        regex.findAll(timeText).forEachIndexed { i, m ->
            val day = when (m.groupValues[2].trim()) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "日", "天" -> 7
                else -> return@forEachIndexed
            }
            results.add(com.glut.schedule.data.model.CourseOccurrence(
                id = "$courseId-$i", courseId = courseId, dayOfWeek = day,
                startSection = (m.groupValues[3].toIntOrNull() ?: 1).coerceIn(1, 12),
                endSection = (m.groupValues[4].toIntOrNull() ?: 1).coerceIn(1, 12),
                weekText = m.groupValues[1].trim().ifBlank { "全周" },
                note = m.groupValues[5].trim().ifBlank { "待确认" }
            ))
        }
        return results
    }

    fun saveDebugInfo() {
        viewModelScope.launch {
            val msg = captureService.saveHtmlToFile(
                uiState.value.htmlPreview.ifBlank { "<empty>" },
                "manual_debug",
                forceFileExport = true
            )
            operationState.update { it.copy(debugInfo = msg, message = msg) }
        }
    }
}

internal fun hasConfirmedAcademicLogin(
    cookie: String,
    currentUrl: String,
    isLoginFormVisible: Boolean
): Boolean {
    return hasUsableAcademicCookie(cookie) &&
        isAcademicPage(currentUrl) &&
        !isLoginPage(currentUrl) &&
        !isLoginFormVisible
}

private data class ImportOperationState(
    val isFetching: Boolean = false,
    val message: String,
    val debugInfo: String = "",
    val currentUrl: String = "",
    val isOnTimetablePage: Boolean = false,
    val isOnExamPage: Boolean = false,
    val isLoginFormVisible: Boolean = true,
    val importedCourseCount: Int = 0,
    val importedExamCount: Int = 0,
    val apiUrls: List<String> = emptyList()
)

class AcademicImportViewModelFactory(
    private val sessionStore: AcademicSessionStore,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val parser: AcademicScheduleParser,
    private val captureService: DebugCaptureService,
    private val apiProbeService: ApiProbeService,
    private val credentialStore: CredentialStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AcademicImportViewModel(
            sessionStore, scheduleRepository, settingsStore, parser, captureService, apiProbeService, credentialStore
        ) as T
    }
}

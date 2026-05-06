package com.glut.schedule.ui.pages

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicImportConfig
import com.glut.schedule.service.academic.AcademicImportService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.AcademicTodayPlanParser
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.DebugCaptureService
import com.glut.schedule.service.academic.hasUsableAcademicCookie
import com.glut.schedule.service.academic.isClassTimetablePage
import com.glut.schedule.service.academic.isTimetablePage
import com.glut.schedule.service.parser.AcademicScheduleParser
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
    val importedCourseCount: Int = 0,
    val apiUrls: List<String> = emptyList()
)

class AcademicImportViewModel(
    private val sessionStore: AcademicSessionStore,
    private val importService: AcademicImportService,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val parser: AcademicScheduleParser,
    private val captureService: DebugCaptureService,
    private val apiProbeService: ApiProbeService
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
            hasSession = hasUsableAcademicCookie(cookie),
            isFetching = operation.isFetching,
            message = operation.message,
            htmlPreview = htmlPreview,
            debugInfo = operation.debugInfo,
            currentUrl = operation.currentUrl,
            isOnTimetablePage = operation.isOnTimetablePage,
            importedCourseCount = operation.importedCourseCount,
            apiUrls = operation.apiUrls
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = AcademicImportUiState()
    )

    fun onPageUrlChanged(url: String?) {
        val sanitized = url.orEmpty()
        val isTimetable = isTimetablePage(sanitized)
        operationState.update {
            it.copy(currentUrl = sanitized, isOnTimetablePage = isTimetable)
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
            operationState.update {
                it.copy(message = "已登录，可直接点击右下角导入按钮")
            }
        }
    }

    fun startAutoOpenTimetable() {
        operationState.update {
            it.copy(isFetching = true, message = "正在自动查找本学期课表...")
        }
    }

    fun reportAutoOpenSucceeded(result: String) {
        operationState.update {
            it.copy(isFetching = false, message = "已尝试打开课表，请在课表页面点击导入按钮")
        }
    }

    fun reportAutoOpenFailed(result: String) {
        operationState.update {
            it.copy(
                isFetching = false,
                message = if (result.contains("not_found")) {
                    "未找到课表入口，请手动点击 学生空间 -> 本学期课表"
                } else {
                    "自动打开失败，请手动导航到课表页面"
                }
            )
        }
    }

    fun importCurrentWebPage(html: String) {
        if (html.isBlank()) {
            operationState.update { it.copy(message = "页面为空，请先进入课表页面") }
            return
        }
        operationState.update { it.copy(isFetching = true, message = "正在解析课表...") }
        viewModelScope.launch {
            sessionStore.saveHtmlPreview(html)

            val diagnostics = StringBuilder()

            if (isClassTimetablePage(operationState.value.currentUrl)) {
                diagnostics.appendLine("当前页面是班级课表，跳过页面HTML导入，改用登录态个人课表接口")
                if (tryImportFromSessionApi(diagnostics, "班级课表页面触发后的个人课表探测")) {
                    return@launch
                }
                val fileMsg = captureService.saveHtmlToFile(html, "class_timetable_skipped")
                operationState.update {
                    it.copy(
                        isFetching = false,
                        debugInfo = "$diagnostics\n\n$fileMsg",
                        message = "检测到班级课表，已阻止导入；未找到可用个人课表接口"
                    )
                }
                return@launch
            }

            diagnostics.appendLine("HTML 长度: ${html.length} 字符")
            diagnostics.appendLine("含 '课表': ${html.contains("课表")}")
            diagnostics.appendLine("含 '周一': ${html.contains("周一")}")
            diagnostics.appendLine("含 'table': ${html.contains("<table")}")
            diagnostics.appendLine("含 'td': ${html.contains("<td")}")

            val tableCount = Regex("""(?i)<table""").findAll(html).count()
            val tdCount = Regex("""(?i)<td\b""").findAll(html).count()
            val trCount = Regex("""(?i)<tr\b""").findAll(html).count()
            diagnostics.appendLine("table 数量: $tableCount")
            diagnostics.appendLine("tr 数量: $trCount")
            diagnostics.appendLine("td 数量: $tdCount")

            val hasDataDay = Regex("""data-day""").containsMatchIn(html)
            val hasDataStart = Regex("""data-start""").containsMatchIn(html)
            diagnostics.appendLine("含 data-day 属性: $hasDataDay")
            diagnostics.appendLine("含 data-start 属性: $hasDataStart")

            diagnostics.appendLine("含 '星期': ${html.contains("星期")}")
            diagnostics.appendLine("含 '周': ${html.contains("周")}")
            diagnostics.appendLine("含 '节': ${html.contains("节")}")

            val courses = runCatching {
                parser.parsePersonalSchedule(html)
            }.getOrElse { e ->
                diagnostics.appendLine("解析异常: ${e.message}")

                captureService.saveDiagnostics(
                    diagnostics.toString(),
                    html.take(3000),
                    operationState.value.currentUrl,
                    hasUsableAcademicCookie(uiState.value.cookie)
                )

                val fileMsg = captureService.saveHtmlToFile(html, "parse_error")
                operationState.update {
                    it.copy(
                        isFetching = false,
                        debugInfo = "$diagnostics\n\n$fileMsg",
                        message = "解析失败: ${e.message}\nHTML已保存到下载目录"
                    )
                }
                return@launch
            }

            diagnostics.appendLine("解析出课程数: ${courses.size}")
            courses.forEachIndexed { i, course ->
                diagnostics.appendLine(
                    "  [$i] ${course.title} | ${course.teacher} | ${course.room} | " +
                        "上课次数: ${course.occurrences.size}"
                )
                course.occurrences.forEach { occ ->
                    diagnostics.appendLine(
                        "    星期${occ.dayOfWeek} 第${occ.startSection}-${occ.endSection}节 ${occ.weekText}"
                    )
                }
            }

            val fileMsg = captureService.saveHtmlToFile(html, "timetable")

            if (courses.isEmpty()) {
                diagnostics.appendLine("当前页面未识别到课程，改用登录态课程接口兜底")
                if (tryImportFromSessionApi(diagnostics, "页面HTML解析为空后的接口兜底")) {
                    return@launch
                }

                diagnostics.appendLine("未识别到课程，HTML已保存到下载目录")

                captureService.saveDiagnostics(
                    diagnostics.toString(),
                    html.take(3000),
                    operationState.value.currentUrl,
                    hasUsableAcademicCookie(uiState.value.cookie)
                )

                operationState.update {
                    it.copy(
                        isFetching = false,
                        debugInfo = "$diagnostics\n\n$fileMsg",
                        message = "当前页面未识别到个人课表\nHTML已保存到下载目录/scheduleApp_debug/"
                    )
                }
                return@launch
            }

            val (currentWeek, activeBlockCount) = currentWeekActiveBlockCount(courses)
            diagnostics.appendLine("当前第${currentWeek}周可显示课程块: $activeBlockCount")
            if (activeBlockCount == 0) {
                diagnostics.appendLine("页面解析结果当前周不可见，改用登录态课程接口兜底")
                if (tryImportFromSessionApi(diagnostics, "页面HTML当前周不可见后的接口兜底")) {
                    return@launch
                }

                captureService.saveDiagnostics(
                    diagnostics.toString(),
                    html.take(3000),
                    operationState.value.currentUrl,
                    hasUsableAcademicCookie(uiState.value.cookie)
                )
                operationState.update {
                    it.copy(
                        isFetching = false,
                        debugInfo = "$diagnostics\n\n$fileMsg",
                        message = "解析到 ${courses.size} 门课程，但第${currentWeek}周没有可显示课程，未覆盖首页"
                    )
                }
                return@launch
            }

            scheduleRepository.replaceImportedCourses(courses)
            diagnostics.appendLine("已成功导入 ${courses.size} 门课程")

            captureService.saveDiagnostics(
                diagnostics.toString(),
                html.take(3000),
                operationState.value.currentUrl,
                hasUsableAcademicCookie(uiState.value.cookie)
            )

            operationState.update {
                it.copy(
                    isFetching = false,
                    debugInfo = "$diagnostics\n\n$fileMsg",
                    message = "已成功导入 ${courses.size} 门课程，返回首页查看",
                    importedCourseCount = courses.size
                )
            }
        }
    }

    fun importApiResponses(jsonStr: String) {
        if (jsonStr.isBlank() || jsonStr == "[]") {
            operationState.update { it.copy(message = "没有拦截到API响应，请先点击课表菜单加载数据") }
            return
        }
        operationState.update { it.copy(isFetching = true, message = "正在解析API响应...") }
        viewModelScope.launch {
            try {
                val responses = org.json.JSONArray(jsonStr)
                val diagnostics = StringBuilder()
                diagnostics.appendLine("拦截到 ${responses.length()} 个API响应")

                var bestResponse: org.json.JSONObject? = null
                var bestScore = 0

                for (i in 0 until responses.length()) {
                    val resp = responses.getJSONObject(i)
                    val url = resp.optString("url", "")
                    val body = resp.optString("body", "")
                    val type = resp.optString("type", "")

                    val isJson = type.contains("json") || body.trimStart().startsWith("{") || body.trimStart().startsWith("[")
                    val hasCourse = body.contains("课程") || body.contains("course") || body.contains("课表")
                    val hasTeacher = body.contains("教师") || body.contains("teacher")
                    val hasRoom = body.contains("教室") || body.contains("地点") || body.contains("room")
                    val hasDay = body.contains("星期") || body.contains("周一")
                    val hasSection = body.contains("节")

                    val score = (if (isJson) 3 else 0) + (if (hasCourse) 2 else 0) +
                        (if (hasTeacher) 1 else 0) + (if (hasRoom) 1 else 0) +
                        (if (hasDay) 2 else 0) + (if (hasSection) 2 else 0)

                    diagnostics.appendLine("  [$i] ${url.takeLast(60)} type=$type score=$score len=${body.length}")

                    if (score > bestScore) {
                        bestScore = score
                        bestResponse = resp
                    }
                }

                if (bestResponse != null && bestScore >= 3) {
                    val body = bestResponse.optString("body", "")
                    diagnostics.appendLine("使用最高分响应: score=$bestScore")
                    sessionStore.saveHtmlPreview(body.take(3000))
                    val fileMsg = captureService.saveHtmlToFile(body, "api_response")

                    val courses = if (body.trimStart().startsWith("{") || body.trimStart().startsWith("[")) {
                        parseApiJson(body, diagnostics)
                    } else {
                        parser.parsePersonalSchedule(body)
                    }

                    if (courses.isEmpty()) {
                        diagnostics.appendLine("未识别到课程数据")
                        captureService.saveDiagnostics(diagnostics.toString(), body.take(3000),
                            operationState.value.currentUrl, hasUsableAcademicCookie(uiState.value.cookie))
                        operationState.update { it.copy(isFetching = false,
                            debugInfo = "$diagnostics\n\n$fileMsg",
                            message = "API响应未识别到课程数据\n数据已保存到下载目录") }
                    } else {
                        val (currentWeek, activeBlockCount) = currentWeekActiveBlockCount(courses)
                        diagnostics.appendLine("当前第${currentWeek}周可显示课程块: $activeBlockCount")
                        if (activeBlockCount == 0) {
                            captureService.saveDiagnostics(diagnostics.toString(), body.take(3000),
                                operationState.value.currentUrl, hasUsableAcademicCookie(uiState.value.cookie))
                            operationState.update { it.copy(isFetching = false,
                                debugInfo = "$diagnostics\n\n$fileMsg",
                                message = "API解析到 ${courses.size} 门课程，但第${currentWeek}周没有可显示课程，未覆盖首页") }
                            return@launch
                        }

                        scheduleRepository.replaceImportedCourses(courses)
                        diagnostics.appendLine("成功导入 ${courses.size} 门课程")
                        operationState.update { it.copy(isFetching = false,
                            debugInfo = "$diagnostics\n\n$fileMsg",
                            message = "已成功导入 ${courses.size} 门课程！返回首页查看",
                            importedCourseCount = courses.size) }
                    }
                } else {
                    diagnostics.appendLine("没有找到包含课程数据的API响应")
                    val allBodies = StringBuilder()
                    for (i in 0 until minOf(responses.length(), 5)) {
                        val resp = responses.getJSONObject(i)
                        allBodies.appendLine("--- Response $i: ${resp.optString("url", "")} ---")
                        allBodies.appendLine(resp.optString("body", "").take(500))
                    }
                    saveApiResponsesToFile(allBodies.toString())
                    operationState.update { it.copy(isFetching = false,
                        debugInfo = "$diagnostics\n\nAPI响应已保存到下载目录",
                        message = "未找到课程API，请先点击[点击课表菜单]后再试") }
                }
            } catch (e: Exception) {
                operationState.update { it.copy(isFetching = false,
                    message = "API解析失败: ${e.message}") }
            }
        }
    }

    private suspend fun currentWeekActiveBlockCount(courses: List<ScheduleCourse>): Pair<Int, Int> {
        val currentWeek = settingsStore.currentWeekNumber.first()
        val activeBlockCount = courses.sumOf { course ->
            course.occurrences.count { occurrence -> occurrence.isActiveInWeek(currentWeek) }
        }
        return currentWeek to activeBlockCount
    }

    private fun parseApiJson(jsonStr: String, diagnostics: StringBuilder): List<com.glut.schedule.data.model.ScheduleCourse> {
        val courses = mutableListOf<com.glut.schedule.data.model.ScheduleCourse>()
        try {
            val root = org.json.JSONObject(jsonStr)
            diagnostics.appendLine("JSON顶层keys: ${root.keys().asSequence().toList()}")

            val dataKeys = listOf("data", "result", "list", "rows", "records", "courseList", "courses", "items")
            var dataArray: org.json.JSONArray? = null
            for (key in dataKeys) {
                if (root.has(key)) {
                    val value = root.get(key)
                    if (value is org.json.JSONArray) {
                        dataArray = value
                        diagnostics.appendLine("在 '$key' 找到数据数组, 长度=${dataArray.length()}")
                        break
                    }
                }
            }
            if (dataArray == null && root.has("data")) {
                val data = root.get("data")
                if (data is org.json.JSONObject) {
                    for (key in dataKeys) {
                        if (data.has(key)) {
                            val value = data.get(key)
                            if (value is org.json.JSONArray) {
                                dataArray = value
                                diagnostics.appendLine("在 'data.$key' 找到数据数组, 长度=${dataArray.length()}")
                                break
                            }
                        }
                    }
                }
            }

            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val course = parseApiCourse(item)
                    if (course != null) courses.add(course)
                }
            } else {
                diagnostics.appendLine("无法找到数据数组, 尝试直接解析")

                val allKeys = root.keys().asSequence().toList()
                if (root.length() == 0) {
                    val arr = org.json.JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val course = parseApiCourse(item)
                        if (course != null) courses.add(course)
                    }
                }
            }
        } catch (e: Exception) {
            diagnostics.appendLine("JSON解析错误: ${e.message}")
        }
        return courses
    }

    private fun parseApiCourse(item: org.json.JSONObject): com.glut.schedule.data.model.ScheduleCourse? {
        val title = findField(item, "课程名称", "courseName", "name", "title", "kcmc", "course_name")
            ?: return null
        val teacher = findField(item, "任课教师", "teacher", "teacherName", "jsxm", "teacher_name",
            "instructor", "rkjs").orEmpty().ifBlank { "待确认" }
        val room = findField(item, "教室", "room", "classroom", "location", "jsmc", "place",
            "address").orEmpty().ifBlank { "待确认" }

        val id = "api-${title.hashCode()}-${teacher.hashCode()}"
        val occurrences = parseApiOccurrences(item, id)
        if (occurrences.isEmpty()) return null

        val colorHex = CourseColorMapper.colorForCourse(id, title)

        return com.glut.schedule.data.model.ScheduleCourse(
            id = id,
            title = title,
            room = room,
            teacher = teacher,
            colorHex = colorHex,
            occurrences = occurrences
        )
    }

    private fun findField(item: org.json.JSONObject, vararg names: String): String? {
        for (name in names) {
            val value = item.optString(name, "")
            if (value.isNotBlank()) return value
        }
        val keys = item.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            for (name in names) {
                if (key.contains(name, ignoreCase = true)) {
                    val value = item.optString(key, "")
                    if (value.isNotBlank()) return value
                }
            }
        }
        return null
    }

    private fun parseApiOccurrences(
        item: org.json.JSONObject,
        courseId: String
    ): List<com.glut.schedule.data.model.CourseOccurrence> {
        val timeFields = listOf("上课时间", "schedule", "time", "timeInfo", "sksj",
            "classTime", "courseTime", "arrangement")
        val timeText = timeFields.firstNotNullOfOrNull { item.optString(it, "").takeIf { v -> v.isNotBlank() } }
            .orEmpty()

        if (timeText.isNotBlank()) {
            return parseTimeTextToOccurrences(timeText, courseId)
        }

        val day = item.optInt("dayOfWeek", item.optInt("day", item.optInt("weekDay", 0)))
        val start = item.optInt("startSection", item.optInt("start", item.optInt("section", 0)))
        val end = item.optInt("endSection", item.optInt("end", item.optInt("sectionEnd", start)))
        val weekText = item.optString("weekText", item.optString("weeks", item.optString("weekInfo", "")))
        val room = item.optString("room", item.optString("classroom", item.optString("jsmc", "")))

        if (day > 0 && start > 0) {
            return listOf(
                com.glut.schedule.data.model.CourseOccurrence(
                    id = "$courseId-occ-0",
                    courseId = courseId,
                    dayOfWeek = day.coerceIn(1, 7),
                    startSection = start.coerceIn(1, 12),
                    endSection = end.coerceIn(start, 12),
                    weekText = weekText.ifBlank { "1-16周" },
                    note = room
                )
            )
        }

        return emptyList()
    }

    private fun parseTimeTextToOccurrences(
        timeText: String,
        courseId: String
    ): List<com.glut.schedule.data.model.CourseOccurrence> {
        val results = mutableListOf<com.glut.schedule.data.model.CourseOccurrence>()
        val regex = Regex(
            """([第\d,，\-－—至单双周节、\s]*)星期([一二三四五六日天])\s*第\s*(\d{1,2})\s*(?:[、,，]|至|~|-|－|—)\s*(\d{1,2})\s*节\s*([^\s<]*)"""
        )
        regex.findAll(timeText).forEachIndexed { index, match ->
            val weekText = match.groupValues[1].trim().ifBlank { "全周" }
            val day = when (match.groupValues[2].trim()) {
                "一" -> 1; "二" -> 2; "三" -> 3; "四" -> 4; "五" -> 5; "六" -> 6; "日", "天" -> 7
                else -> return@forEachIndexed
            }
            val start = match.groupValues[3].toIntOrNull() ?: return@forEachIndexed
            val end = match.groupValues[4].toIntOrNull() ?: start
            val room = match.groupValues[5].trim().ifBlank { "待确认" }
            results.add(
                com.glut.schedule.data.model.CourseOccurrence(
                    id = "$courseId-occ-$index",
                    courseId = courseId,
                    dayOfWeek = day,
                    startSection = start.coerceIn(1, 12),
                    endSection = end.coerceIn(start, 12),
                    weekText = weekText,
                    note = room
                )
            )
        }
        return results
    }

    private suspend fun saveApiResponsesToFile(content: String) {
        captureService.saveHtmlToFile(content, "api_all_responses")
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
            settingsStore.setCurrentWeekNumber(calendar.currentWeekNumber)
            diagnostics.appendLine(
                "已同步教务日历: 第${calendar.currentWeekNumber}周, 学期起点 ${calendar.semesterStartMonday}"
            )
        }

        val courses = parseBestProbeResult(results, diagnostics)
        if (courses.isEmpty()) return false

        scheduleRepository.replaceImportedCourses(courses)
        diagnostics.appendLine("成功导入 ${courses.size} 门课程")
        captureService.saveDiagnostics(
            diagnostics.toString(),
            uiState.value.htmlPreview.take(3000),
            operationState.value.currentUrl,
            true
        )
        operationState.update {
            val importedFromTodayPlanOnly = courses.all { course -> course.id.startsWith("today-") }
            it.copy(
                isFetching = false,
                debugInfo = diagnostics.toString(),
                message = if (importedFromTodayPlanOnly) {
                    "仅导入今日课程 ${courses.size} 门；未拿到完整个人课表"
                } else {
                    "已成功导入 ${courses.size} 门个人课表课程，返回首页查看"
                },
                importedCourseCount = courses.size
            )
        }
        return true
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
                "manual_debug"
            )
            operationState.update { it.copy(debugInfo = msg, message = msg) }
        }
    }
}

private data class ImportOperationState(
    val isFetching: Boolean = false,
    val message: String,
    val debugInfo: String = "",
    val currentUrl: String = "",
    val isOnTimetablePage: Boolean = false,
    val importedCourseCount: Int = 0,
    val apiUrls: List<String> = emptyList()
)

class AcademicImportViewModelFactory(
    private val sessionStore: AcademicSessionStore,
    private val importService: AcademicImportService,
    private val scheduleRepository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val parser: AcademicScheduleParser,
    private val captureService: DebugCaptureService,
    private val apiProbeService: ApiProbeService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AcademicImportViewModel(
            sessionStore, importService, scheduleRepository, settingsStore, parser, captureService, apiProbeService
        ) as T
    }
}

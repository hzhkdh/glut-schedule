package com.glut.schedule.ui.pages

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicImportConfig
import com.glut.schedule.service.academic.AcademicImportService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.DebugCaptureService
import com.glut.schedule.service.academic.hasUsableAcademicCookie
import com.glut.schedule.service.academic.isTimetablePage
import com.glut.schedule.service.parser.AcademicScheduleParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
                it.copy(message = "已登录，请进入本学期课表页面后点击右下角导入按钮")
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

        val colors = listOf("#2F6EEA", "#C87505", "#2687C7", "#2CBF91", "#C77908",
            "#21B989", "#7C6FE6", "#B9577F", "#E05A3E", "#5B8C5A")
        val colorHex = colors[id.fold(0) { acc, c -> acc + c.code }.mod(colors.size)]

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
            val results = apiProbeService.probeAllEndpoints(cookie)
            val analysis = apiProbeService.analyzeResults(results)
            val diagnostics = StringBuilder(analysis.summary)

            val allResultsText = results.joinToString("\n\n---\n\n") { r: ApiProbeService.ProbeResult ->
                "URL: ${r.url}\nMethod: ${r.method}\nHTTP: ${r.httpCode}\nType: ${r.contentType}\nBody(${r.bodyLength}B):\n${r.body.take(2000)}"
            }
            val fileMsg = captureService.saveHtmlToFile(allResultsText, "api_probe_results")

            val htmlResult: String? = apiProbeService.extractTimetableHtml(results)
            val jsonResult: String? = apiProbeService.extractTimetableJson(results)

            when {
                htmlResult != null -> {
                    diagnostics.appendLine("\n找到HTML课表! 正在解析...")
                    sessionStore.saveHtmlPreview(htmlResult?.take(3000) ?: "")
                    val courses = parser.parsePersonalSchedule(htmlResult ?: "")
                    if (courses.isNotEmpty()) {
                        scheduleRepository.replaceImportedCourses(courses)
                        diagnostics.appendLine("成功导入 ${courses.size} 门课程!")
                        operationState.update { it.copy(isFetching = false,
                            debugInfo = "$diagnostics\n\n$fileMsg",
                            message = "已成功导入 ${courses.size} 门课程！返回首页查看",
                            importedCourseCount = courses.size) }
                    } else {
                        diagnostics.appendLine("HTML解析仍失败，需修复解析器")
                        captureService.saveHtmlToFile(htmlResult ?: "", "found_timetable_html")
                        operationState.update { it.copy(isFetching = false,
                            debugInfo = "$diagnostics\n\n$fileMsg",
                            message = "找到课表HTML但解析失败，HTML已保存到下载目录") }
                    }
                }
                jsonResult != null -> {
                    diagnostics.appendLine("\n找到JSON课表! 正在解析...")
                    captureService.saveHtmlToFile(jsonResult ?: "", "found_timetable_json")
                    val courses = try {
                        parseApiJsonInternal(jsonResult ?: "", diagnostics)
                    } catch (e: Exception) {
                        diagnostics.appendLine("JSON解析异常: ${e.message}")
                        emptyList()
                    }
                    if (courses.isNotEmpty()) {
                        scheduleRepository.replaceImportedCourses(courses)
                        diagnostics.appendLine("成功导入 ${courses.size} 门课程!")
                        operationState.update { it.copy(isFetching = false,
                            debugInfo = "$diagnostics\n\n$fileMsg",
                            message = "已成功导入 ${courses.size} 门课程！返回首页查看",
                            importedCourseCount = courses.size) }
                    } else {
                        operationState.update { it.copy(isFetching = false,
                            debugInfo = "$diagnostics\n\n$fileMsg",
                            message = "找到JSON但解析失败，数据已保存到下载目录") }
                    }
                }
                else -> {
                    operationState.update { it.copy(isFetching = false,
                        debugInfo = "$diagnostics\n\n$fileMsg",
                        message = "API探测完成，未找到课表数据\n详情见调试面板") }
                }
            }
        }
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
                val colors = listOf("#2F6EEA", "#C87505", "#2687C7", "#2CBF91", "#C77908")
                return com.glut.schedule.data.model.ScheduleCourse(
                    id = id, title = title, room = room, teacher = teacher,
                    colorHex = colors[id.fold(0) { a, c -> a + c.code }.mod(colors.size)],
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
    private val parser: AcademicScheduleParser,
    private val captureService: DebugCaptureService,
    private val apiProbeService: ApiProbeService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AcademicImportViewModel(
            sessionStore, importService, scheduleRepository, parser, captureService, apiProbeService
        ) as T
    }
}

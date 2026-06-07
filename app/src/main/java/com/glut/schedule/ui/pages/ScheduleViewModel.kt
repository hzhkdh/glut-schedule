package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
import com.glut.schedule.data.model.DEFAULT_SEMESTER_END_DATE
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.normalizeSemesterStartMonday
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.shouldUseExistingAcademicCookie
import com.glut.schedule.service.parser.AcademicScheduleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.LocalDate

data class ScheduleUiState(
    val week: ScheduleWeek = scheduleWeekForNumber(9, DEFAULT_SEMESTER_START_MONDAY),
    val today: LocalDate = LocalDate.now(),
    val currentWeekNumber: Int = academicWeekForDate(LocalDate.now(), DEFAULT_SEMESTER_START_MONDAY),
    val semesterStartMonday: LocalDate = DEFAULT_SEMESTER_START_MONDAY,
    val semesterEndDate: LocalDate = DEFAULT_SEMESTER_END_DATE,
    val maxAcademicWeek: Int = academicMaxWeekForCalendar(DEFAULT_SEMESTER_START_MONDAY, DEFAULT_SEMESTER_END_DATE),
    val classPeriods: List<ClassPeriod> = emptyList(),
    val courses: List<ScheduleCourse> = emptyList(),
    val courseBlocks: List<CourseBlock> = emptyList(),
    val showWeekend: Boolean = false,
    val customBackgroundUri: String = "",
    val isRefreshing: Boolean = false,
    val message: String = "",
    val needsInteractiveLogin: Boolean = false
)

private data class ScheduleSettingsUiState(
    val weekNumber: Int,
    val showWeekend: Boolean,
    val semesterStartMonday: LocalDate,
    val semesterEndDate: LocalDate,
    val customBackgroundUri: String
)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val apiProbeService: ApiProbeService,
    private val parser: AcademicScheduleParser
) : ViewModel() {
    val uiState: StateFlow<ScheduleUiState>
    private var initialWeekSet = false
    private val isRefreshing = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val needsInteractiveLogin = MutableStateFlow(false)

    init {
        val coldBgUri = runBlocking(Dispatchers.IO) {
            settingsStore.customBackgroundUri.first()
        }
        val coldShowWeekend = runBlocking(Dispatchers.IO) {
            settingsStore.showWeekend.first()
        }
        val coldCourses = runBlocking(Dispatchers.IO) {
            CourseColorMapper.assignColors(repository.courses.first())
        }
        val coldPeriods = runBlocking(Dispatchers.IO) {
            repository.classPeriods.first()
        }
        val initialWeek = scheduleWeekForNumber(
            academicWeekForDate(LocalDate.now(), DEFAULT_SEMESTER_START_MONDAY),
            DEFAULT_SEMESTER_START_MONDAY
        )
        val coldBlocks = coldCourses.flatMap { course ->
            course.occurrences
                .filter { it.isActiveInWeek(initialWeek.number) }
                .map { CourseBlock(course, it) }
        }

        viewModelScope.launch {
            repository.seedIfEmpty()
        }

        val settingsState = combine(
            settingsStore.currentWeekNumber,
            settingsStore.showWeekend,
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate,
            settingsStore.customBackgroundUri
        ) { weekNumber, showWeekend, semesterStartMonday, semesterEndDate, customBackgroundUri ->
            ScheduleSettingsUiState(
                weekNumber = weekNumber,
                showWeekend = showWeekend,
                semesterStartMonday = semesterStartMonday,
                semesterEndDate = semesterEndDate,
                customBackgroundUri = customBackgroundUri
            )
        }

        val scheduleState = combine(
            settingsState,
            repository.classPeriods,
            repository.courses
        ) { settings, periods, courses ->
            val normalizedStart = normalizeSemesterStartMonday(settings.semesterStartMonday)
            val maxAcademicWeek = academicMaxWeekForCalendar(normalizedStart, settings.semesterEndDate)
            val clampedWeekNumber = if (initialWeekSet) {
                clampAcademicWeek(settings.weekNumber, maxAcademicWeek)
            } else {
                initialWeekSet = true
                val correctedWeek = academicWeekForDate(LocalDate.now(), normalizedStart, maxAcademicWeek)
                if (correctedWeek != settings.weekNumber) {
                    viewModelScope.launch { settingsStore.setCurrentWeekNumber(correctedWeek) }
                }
                correctedWeek
            }
            val today = LocalDate.now()
            val coloredCourses = CourseColorMapper.assignColors(courses)
            ScheduleUiState(
                week = scheduleWeekForNumber(clampedWeekNumber, normalizedStart, maxAcademicWeek),
                today = today,
                currentWeekNumber = academicWeekForDate(today, normalizedStart, maxAcademicWeek),
                semesterStartMonday = normalizedStart,
                semesterEndDate = settings.semesterEndDate,
                maxAcademicWeek = maxAcademicWeek,
                classPeriods = periods,
                courses = coloredCourses,
                courseBlocks = coloredCourses.flatMap { course ->
                    course.occurrences
                        .filter { occurrence -> occurrence.isActiveInWeek(clampedWeekNumber) }
                        .map { occurrence -> CourseBlock(course, occurrence) }
                },
                showWeekend = settings.showWeekend,
                customBackgroundUri = settings.customBackgroundUri
            )
        }

        uiState = combine(
            scheduleState,
            isRefreshing,
            message,
            needsInteractiveLogin
        ) { state, refreshing, currentMessage, interactiveLogin ->
            state.copy(
                isRefreshing = refreshing,
                message = currentMessage,
                needsInteractiveLogin = interactiveLogin
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ScheduleUiState(
                week = initialWeek,
                customBackgroundUri = coldBgUri,
                showWeekend = coldShowWeekend,
                courses = coldCourses,
                classPeriods = coldPeriods,
                courseBlocks = coldBlocks,
                isRefreshing = isRefreshing.value,
                message = message.value,
                needsInteractiveLogin = needsInteractiveLogin.value
            )
        )
    }

    fun previousWeek() {
        val nextWeek = uiState.value.week.previous(uiState.value.maxAcademicWeek).number
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(nextWeek) }
    }

    fun nextWeek() {
        val nextWeek = uiState.value.week.next(uiState.value.maxAcademicWeek).number
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(nextWeek) }
    }

    fun setWeekNumber(weekNumber: Int) {
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(clampAcademicWeek(weekNumber, uiState.value.maxAcademicWeek)) }
    }

    fun setShowWeekend(showWeekend: Boolean) {
        viewModelScope.launch { settingsStore.setShowWeekend(showWeekend) }
    }

    fun returnToCurrentWeek() {
        val currentWeekNumber = academicWeekForDate(
            LocalDate.now(),
            uiState.value.semesterStartMonday,
            uiState.value.maxAcademicWeek
        )
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(currentWeekNumber) }
    }

    fun setCustomBackgroundUri(uri: String) {
        viewModelScope.launch { settingsStore.setCustomBackgroundUri(uri) }
    }

    fun clearCustomBackground() {
        viewModelScope.launch { settingsStore.setCustomBackgroundUri("") }
    }

    fun refreshSchedule() {
        viewModelScope.launch {
            isRefreshing.value = true
            message.value = "正在刷新课表..."
            needsInteractiveLogin.value = false
            try {
                val oldCourseCount = repository.courses.first().size
                val existingCookie = sessionStore.academicCookie.first()
                if (shouldUseExistingAcademicCookie(existingCookie) && fetchAndSaveSchedule(existingCookie, oldCourseCount)) {
                    return@launch
                }

                when (val loginResult = loginService.silentLogin()) {
                    is AcademicLoginResult.Success -> Unit
                    AcademicLoginResult.MissingCredentials -> {
                        message.value = "请先登录教务系统以保存账号密码"
                        needsInteractiveLogin.value = true
                        return@launch
                    }
                    AcademicLoginResult.InvalidCredentials -> {
                        message.value = "教务账号或密码错误，请重新登录"
                        needsInteractiveLogin.value = true
                        return@launch
                    }
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        message.value = "教务系统需要手动验证，请重新登录"
                        needsInteractiveLogin.value = true
                        return@launch
                    }
                    is AcademicLoginResult.NetworkError -> {
                        message.value = "登录失败: ${loginResult.message}"
                        needsInteractiveLogin.value = true
                        return@launch
                    }
                }

                val cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank() || !fetchAndSaveSchedule(cookie, oldCourseCount)) {
                    message.value = "未获取到课表数据，请重新登录教务系统"
                    needsInteractiveLogin.value = true
                }
            } catch (e: Exception) {
                message.value = "刷新失败: ${e.message}"
            } finally {
                isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchAndSaveSchedule(cookie: String, oldCourseCount: Int): Boolean {
        val results = apiProbeService.probeAllEndpoints(cookie = cookie)
        val calendar = ApiProbeService.extractAcademicCalendar(results)
        if (calendar != null) {
            settingsStore.setSemesterStartMonday(calendar.semesterStartMonday)
            calendar.semesterEndDate?.let { settingsStore.setSemesterEndDate(it) }
            settingsStore.setCurrentWeekNumber(calendar.currentWeekNumber)
        }

        val courses = parseScheduleFromProbeResults(results)
        if (courses.isEmpty()) return false

        repository.replaceImportedCourses(courses)
        val newCount = courses.size
        message.value = if (newCount != oldCourseCount)
            "课表已更新: $oldCourseCount → $newCount 门课程"
        else
            "课表未发生变化 ($newCount 门课程)"
        return true
    }

    private suspend fun parseScheduleFromProbeResults(
        results: List<ApiProbeService.ProbeResult>
    ): List<ScheduleCourse> {
        val htmlResult = apiProbeService.findTimetableHtmlResult(results)
        if (htmlResult != null) {
            sessionStore.saveHtmlPreview(htmlResult.body.take(3000))
            val courses = runCatching { parser.parsePersonalSchedule(htmlResult.body) }.getOrDefault(emptyList())
            if (courses.isNotEmpty()) return courses
        }

        val jsonResult = apiProbeService.findTimetableJsonResult(results)
        if (jsonResult != null) {
            val courses = parseTimetableJson(jsonResult.body)
            if (courses.isNotEmpty()) return courses
        }

        return emptyList()
    }

    private fun parseTimetableJson(json: String): List<ScheduleCourse> {
        val courses = mutableListOf<ScheduleCourse>()
        fun extractCourse(item: org.json.JSONObject): ScheduleCourse? {
            val title = item.optString("kcmc").ifBlank {
                item.optString("courseName").ifBlank { item.optString("name") }
            }.trim()
            if (title.isBlank()) return null
            val teacher = item.optString("jsxm").ifBlank {
                item.optString("teacher").ifBlank { item.optString("teacherName") }
            }.ifBlank { "待确认" }
            val room = item.optString("jsmc").ifBlank {
                item.optString("room").ifBlank { item.optString("classroom") }
            }.ifBlank { "待确认" }
            val timeText = item.optString("sksj").ifBlank {
                item.optString("schedule").ifBlank { item.optString("time") }
            }
            val id = "refresh-${title.hashCode()}-${teacher.hashCode()}-${room.hashCode()}"
            val occurrences = if (timeText.isNotBlank()) {
                parseJsonTimeText(timeText, id)
            } else {
                val day = item.optInt("dayOfWeek", item.optInt("day", 0))
                val start = item.optInt("startSection", item.optInt("start", 0))
                val end = item.optInt("endSection", item.optInt("end", start))
                if (day > 0 && start > 0) {
                    listOf(
                        CourseOccurrence(
                            id = "$id-occurrence",
                            courseId = id,
                            dayOfWeek = day.coerceIn(1, 7),
                            startSection = start.coerceIn(1, 12),
                            endSection = end.coerceIn(start, 12),
                            weekText = item.optString("weekText").ifBlank { "1-22周" },
                            note = room
                        )
                    )
                } else {
                    emptyList()
                }
            }
            if (occurrences.isEmpty()) return null
            return ScheduleCourse(
                id = id,
                title = title,
                room = room,
                teacher = teacher,
                colorHex = CourseColorMapper.colorForCourse(id, title),
                occurrences = occurrences
            )
        }

        runCatching {
            val trimmed = json.trim()
            val items = when {
                trimmed.startsWith("[") -> org.json.JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val obj = org.json.JSONObject(trimmed)
                    listOf("data", "result", "list", "rows", "records")
                        .firstNotNullOfOrNull { key -> obj.optJSONArray(key) }
                        ?: org.json.JSONArray()
                }
                else -> org.json.JSONArray()
            }
            for (i in 0 until items.length()) {
                val course = extractCourse(items.optJSONObject(i) ?: continue)
                if (course != null) courses += course
            }
        }
        return CourseColorMapper.assignColors(courses)
    }

    private fun parseJsonTimeText(timeText: String, courseId: String): List<CourseOccurrence> {
        val regex = Regex(
            """([第\d,，\-－—至单双周节、\s]*)星期([一二三四五六日天])\s*第\s*(\d{1,2})\s*(?:[、,，]|至|~|-|－|—)\s*(\d{1,2})\s*节\s*([^\s<]*)"""
        )
        return regex.findAll(timeText).mapIndexedNotNull { index, match ->
            val day = when (match.groupValues[2].trim()) {
                "一" -> 1
                "二" -> 2
                "三" -> 3
                "四" -> 4
                "五" -> 5
                "六" -> 6
                "日", "天" -> 7
                else -> return@mapIndexedNotNull null
            }
            val room = match.groupValues[5].trim()
            CourseOccurrence(
                id = "$courseId-occurrence-$index",
                courseId = courseId,
                dayOfWeek = day,
                startSection = (match.groupValues[3].toIntOrNull() ?: 1).coerceIn(1, 12),
                endSection = (match.groupValues[4].toIntOrNull() ?: 1).coerceIn(1, 12),
                weekText = match.groupValues[1].trim().ifBlank { "全周" },
                note = room
            )
        }.toList()
    }

    fun clearMessage() {
        message.value = ""
    }

    fun consumeInteractiveLoginRequest() {
        needsInteractiveLogin.value = false
    }
}

class ScheduleViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val apiProbeService: ApiProbeService,
    private val parser: AcademicScheduleParser
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScheduleViewModel(repository, settingsStore, sessionStore, loginService, apiProbeService, parser) as T
    }
}

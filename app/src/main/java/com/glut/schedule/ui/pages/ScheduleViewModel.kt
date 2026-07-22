package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.NOON_SECTIONS
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
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

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
    val showNoon: Boolean = false,
    val customBackgroundUri: String = "",
    val courseColorOverrides: Map<String, String> = emptyMap(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val needsInteractiveLogin: Boolean = false,
    val semesters: List<AcademicSemester> = emptyList(),
    val viewedSemester: AcademicSemester? = null,
    val isHistoricalSemester: Boolean = false,
    val hasAuthoritativeCalendar: Boolean = true
)

private data class ScheduleSettingsUiState(
    val weekNumber: Int,
    val showWeekend: Boolean,
    val showNoon: Boolean,
    val semesterStartMonday: LocalDate,
    val semesterEndDate: LocalDate,
    val customBackgroundUri: String
)

private data class ScheduleCalendarSettings(
    val weekNumber: Int,
    val showWeekend: Boolean,
    val showNoon: Boolean,
    val semesterStartMonday: LocalDate,
    val semesterEndDate: LocalDate
)

private data class ColoredCoursesState(
    val courses: List<ScheduleCourse>,
    val overrides: Map<String, String>
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
        val coldShowNoon = runBlocking(Dispatchers.IO) {
            settingsStore.showNoon.first()
        }
        val coldOverrides = runBlocking(Dispatchers.IO) {
            settingsStore.courseColorOverrides.first()
        }
        val coldCourses = runBlocking(Dispatchers.IO) {
            CourseColorMapper.assignColors(repository.courses.first(), coldOverrides)
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
            combine(
                settingsStore.currentWeekNumber,
                settingsStore.showWeekend,
                settingsStore.showNoon,
                settingsStore.semesterStartMonday,
                settingsStore.semesterEndDate
            ) { weekNumber, showWeekend, showNoon, semesterStartMonday, semesterEndDate ->
                ScheduleCalendarSettings(
                    weekNumber = weekNumber,
                    showWeekend = showWeekend,
                    showNoon = showNoon,
                    semesterStartMonday = semesterStartMonday,
                    semesterEndDate = semesterEndDate
                )
            },
            settingsStore.customBackgroundUri
        ) { base, customBackgroundUri ->
            ScheduleSettingsUiState(
                weekNumber = base.weekNumber,
                showWeekend = base.showWeekend,
                showNoon = base.showNoon,
                semesterStartMonday = base.semesterStartMonday,
                semesterEndDate = base.semesterEndDate,
                customBackgroundUri = customBackgroundUri
            )
        }

        val coloredCoursesState = combine(
            repository.courses,
            settingsStore.courseColorOverrides
        ) { courses, overrides ->
            ColoredCoursesState(
                courses = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    CourseColorMapper.assignColors(courses, overrides)
                },
                overrides = overrides
            )
        }

        val scheduleState = combine(
            settingsState,
            repository.classPeriods,
            coloredCoursesState,
            repository.viewedSemester,
            repository.semesters
        ) { settings, periods, coloredState, viewedSemester, semesters ->
            val isHistorical = viewedSemester != null && !viewedSemester.isCurrent
            val hasAuthoritativeCalendar = !isHistorical ||
                (viewedSemester?.semesterStartDate != null && viewedSemester.semesterEndDate != null)
            val fallbackStart = viewedSemester?.let(::estimatedSemesterStart) ?: settings.semesterStartMonday
            val normalizedStart = normalizeSemesterStartMonday(
                viewedSemester?.semesterStartDate ?: if (isHistorical) fallbackStart else settings.semesterStartMonday
            )
            val resolvedEnd = viewedSemester?.semesterEndDate
                ?: if (isHistorical) normalizedStart.plusWeeks(21).plusDays(6) else settings.semesterEndDate
            val maxAcademicWeek = if (isHistorical && !hasAuthoritativeCalendar) {
                historicalMaxWeek(coloredState.courses)
            } else academicMaxWeekForCalendar(normalizedStart, resolvedEnd)
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
            val coloredCourses = coloredState.courses
            ScheduleUiState(
                week = scheduleWeekForNumber(clampedWeekNumber, normalizedStart, maxAcademicWeek),
                today = today,
                currentWeekNumber = academicWeekForDate(today, normalizedStart, maxAcademicWeek),
                semesterStartMonday = normalizedStart,
                semesterEndDate = resolvedEnd,
                maxAcademicWeek = maxAcademicWeek,
                classPeriods = periods,
                courses = coloredCourses,
                courseBlocks = coloredCourses.flatMap { course ->
                    course.occurrences
                        .filter { occurrence -> occurrence.isActiveInWeek(clampedWeekNumber) }
                        .map { occurrence -> CourseBlock(course, occurrence) }
                },
                showWeekend = settings.showWeekend,
                showNoon = settings.showNoon,
                customBackgroundUri = settings.customBackgroundUri,
                courseColorOverrides = coloredState.overrides,
                semesters = semesters,
                viewedSemester = viewedSemester,
                isHistoricalSemester = isHistorical,
                hasAuthoritativeCalendar = hasAuthoritativeCalendar
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
                courseColorOverrides = coldOverrides,
                showWeekend = coldShowWeekend,
                showNoon = coldShowNoon,
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

    fun setShowNoon(showNoon: Boolean) {
        viewModelScope.launch { settingsStore.setShowNoon(showNoon) }
    }

    fun returnToCurrentWeek() {
        val currentWeekNumber = academicWeekForDate(
            LocalDate.now(),
            uiState.value.semesterStartMonday,
            uiState.value.maxAcademicWeek
        )
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(currentWeekNumber) }
    }

    fun selectSemester(semesterId: String) {
        val semester = uiState.value.semesters.firstOrNull { it.id == semesterId } ?: return
        if (semester.cacheStatus != SemesterCacheStatus.CACHED) return
        repository.selectSemester(semesterId)
        viewModelScope.launch {
            val week = if (semester.isCurrent) {
                academicWeekForDate(LocalDate.now(), uiState.value.semesterStartMonday, uiState.value.maxAcademicWeek)
            } else 1
            settingsStore.setCurrentWeekNumber(week)
        }
    }

    fun returnToCurrentSemester() {
        viewModelScope.launch {
            repository.resetViewedSemesterToCurrent()
            val current = repository.currentSemester.first()
            val start = current?.semesterStartDate ?: settingsStore.semesterStartMonday.first()
            val end = current?.semesterEndDate ?: settingsStore.semesterEndDate.first()
            settingsStore.setCurrentWeekNumber(
                academicWeekForDate(LocalDate.now(), start, academicMaxWeekForCalendar(start, end))
            )
        }
    }

    fun setCustomBackgroundUri(uri: String) {
        viewModelScope.launch { settingsStore.setCustomBackgroundUri(uri) }
    }

    fun clearCustomBackground() {
        viewModelScope.launch { settingsStore.setCustomBackgroundUri("") }
    }

    fun setCourseColorOverride(courseKey: String, colorHex: String) {
        viewModelScope.launch { settingsStore.setCourseColorOverride(courseKey, colorHex) }
    }

    fun removeCourseColorOverride(courseKey: String) {
        viewModelScope.launch { settingsStore.removeCourseColorOverride(courseKey) }
    }

    fun clearCourseColorOverrides() {
        viewModelScope.launch { settingsStore.clearCourseColorOverrides() }
    }

    fun refreshSchedule() {
        if (uiState.value.isHistoricalSemester) {
            message.value = "历史学期为只读缓存，无需刷新"
            return
        }
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
        val campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }
        val results = apiProbeService.probeScheduleEndpoints(
            cookie = cookie,
            storedTimetableUrl = sessionStore.timetableUrl.first(),
            baseUrl = campusBaseUrl
        )
        val calendar = ApiProbeService.extractAcademicCalendar(results)
        if (calendar != null) {
            settingsStore.setSemesterStartMonday(calendar.semesterStartMonday)
            calendar.semesterEndDate?.let { settingsStore.setSemesterEndDate(it) }
            settingsStore.setCurrentWeekNumber(calendar.currentWeekNumber)
        }

        val courses = parseScheduleFromProbeResults(results)
        if (courses.isEmpty()) return false

        apiProbeService.findTimetableHtmlResult(results)?.let { result ->
            sessionStore.saveTimetableUrl(result.url)
        }
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

    private fun estimatedSemesterStart(semester: AcademicSemester): LocalDate {
        val month = if (semester.season == SemesterSeason.AUTUMN) 9 else 3
        return LocalDate.of(semester.portalYear, month, 1)
            .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY))
    }

    private fun historicalMaxWeek(courses: List<ScheduleCourse>): Int {
        val max = courses.asSequence()
            .flatMap { it.occurrences.asSequence() }
            .flatMap { Regex("""\d{1,2}""").findAll(it.weekText).map { match -> match.value.toIntOrNull() ?: 1 } }
            .maxOrNull() ?: 20
        return max.coerceIn(1, com.glut.schedule.data.model.MAX_ACADEMIC_WEEK)
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

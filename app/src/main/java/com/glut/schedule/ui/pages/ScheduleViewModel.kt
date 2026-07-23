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
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
import com.glut.schedule.data.model.DEFAULT_SEMESTER_END_DATE
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicMaxWeekForSemester
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.normalizeSemesterStartMonday
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.AcademicSemesterImportPayload
import com.glut.schedule.service.academic.AcademicSemesterImportService
import com.glut.schedule.service.academic.AcademicSemesterViewPlanner
import com.glut.schedule.service.academic.shouldUseExistingAcademicCookie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val campusType: CampusType = CampusType.GUILIN,
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
    val customBackgroundUri: String,
    val campusType: CampusType
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
    private val semesterImportService: AcademicSemesterImportService
) : ViewModel() {
    val uiState: StateFlow<ScheduleUiState>
    private var initialWeekSet = false
    private val isRefreshing = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val needsInteractiveLogin = MutableStateFlow(false)

    init {
        val initialWeek = scheduleWeekForNumber(
            academicWeekForDate(LocalDate.now(), DEFAULT_SEMESTER_START_MONDAY),
            DEFAULT_SEMESTER_START_MONDAY
        )

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
            settingsStore.customBackgroundUri,
            settingsStore.campusType
        ) { base, customBackgroundUri, campusType ->
            ScheduleSettingsUiState(
                weekNumber = base.weekNumber,
                showWeekend = base.showWeekend,
                showNoon = base.showNoon,
                semesterStartMonday = base.semesterStartMonday,
                semesterEndDate = base.semesterEndDate,
                customBackgroundUri = customBackgroundUri,
                campusType = campusType
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
            val maxAcademicWeek = academicMaxWeekForSemester(
                isCurrentSemester = !isHistorical,
                portalMaxWeek = viewedSemester?.portalMaxWeek,
                courses = coloredState.courses,
                semesterStartMonday = normalizedStart,
                semesterEndDate = resolvedEnd
            )
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
                campusType = settings.campusType,
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
        viewModelScope.launch {
            val week = AcademicSemesterViewPlanner.weekFor(
                semester = semester,
                today = LocalDate.now(),
                fallbackStart = settingsStore.semesterStartMonday.first(),
                fallbackEnd = settingsStore.semesterEndDate.first()
            )
            repository.selectSemester(semesterId)
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

    fun setClassPeriods(periods: List<ClassPeriod>) {
        viewModelScope.launch {
            settingsStore.setClassPeriods(uiState.value.campusType, periods)
        }
    }

    fun resetClassPeriods() {
        viewModelScope.launch {
            settingsStore.resetClassPeriods(uiState.value.campusType)
        }
    }

    fun refreshSchedule() {
        val targetSemester = uiState.value.viewedSemester
        if (targetSemester == null) {
            message.value = "当前学期尚未就绪，请稍后重试"
            return
        }
        if (!targetSemester.isCurrent) {
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
                var importResult: Result<AcademicSemesterImportPayload>? = null
                if (shouldUseExistingAcademicCookie(existingCookie)) {
                    importResult = importExactSemester(existingCookie, targetSemester)
                    if (importResult.isSuccess) {
                        saveExactSemester(targetSemester, importResult.getOrThrow(), oldCourseCount)
                        return@launch
                    }
                    if (!isAuthenticationFailure(importResult.exceptionOrNull())) {
                        message.value = refreshFailureMessage(importResult.exceptionOrNull(), targetSemester)
                        return@launch
                    }
                }

                when (val loginResult = loginService.silentLogin()) {
                    is AcademicLoginResult.Success -> {
                        importResult = importExactSemester(loginResult.cookie, targetSemester)
                    }
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

                val payload = importResult?.getOrNull()
                if (payload == null) {
                    val failure = importResult?.exceptionOrNull()
                    message.value = refreshFailureMessage(failure, targetSemester)
                    needsInteractiveLogin.value = isAuthenticationFailure(failure)
                    return@launch
                }
                saveExactSemester(targetSemester, payload, oldCourseCount)
            } catch (e: Exception) {
                message.value = refreshFailureMessage(e, targetSemester)
            } finally {
                isRefreshing.value = false
            }
        }
    }

    private suspend fun importExactSemester(
        cookie: String,
        targetSemester: AcademicSemester
    ): Result<AcademicSemesterImportPayload> {
        val campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }
        val studentId = sessionStore.authenticatedStudentNumber.first()
        return semesterImportService.importSemester(
            cookie = cookie,
            baseUrl = campusBaseUrl,
            semester = targetSemester,
            studentIdFallback = studentId,
            useWeeklyTimetable = true,
            onProgress = { completed, total ->
                message.value = "正在刷新${targetSemester.displayName}（第${completed}/${total}周）..."
            }
        )
    }

    private suspend fun saveExactSemester(
        targetSemester: AcademicSemester,
        payload: AcademicSemesterImportPayload,
        oldCourseCount: Int
    ) {
        require(payload.courses.isNotEmpty()) {
            "${targetSemester.displayName}未获取到课程，已保留现有缓存"
        }
        repository.replaceSemesterSchedule(
            semester = targetSemester,
            courses = payload.courses,
            adjustments = payload.adjustments,
            semesterStartDate = targetSemester.semesterStartDate,
            semesterEndDate = targetSemester.semesterEndDate,
            portalMaxWeek = payload.portalMaxWeek
        )
        val newCourseCount = payload.courses.size
        message.value = if (newCourseCount != oldCourseCount) {
            "${targetSemester.displayName}课表已更新：$oldCourseCount → $newCourseCount 门课程"
        } else {
            "${targetSemester.displayName}课表未发生变化（$newCourseCount 门课程）"
        }
    }

    private fun isAuthenticationFailure(error: Throwable?): Boolean =
        error?.message.orEmpty().contains("登录状态已失效")

    private fun refreshFailureMessage(error: Throwable?, semester: AcademicSemester): String {
        val detail = error?.message.orEmpty().ifBlank { "未获取到课表数据" }
        return "${semester.displayName}刷新失败：$detail"
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

}

class ScheduleViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val semesterImportService: AcademicSemesterImportService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScheduleViewModel(repository, settingsStore, sessionStore, loginService, semesterImportService) as T
    }
}

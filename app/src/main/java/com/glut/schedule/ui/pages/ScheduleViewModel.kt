package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.normalizeSemesterStartMonday
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class ScheduleUiState(
    val week: ScheduleWeek = scheduleWeekForNumber(9, DEFAULT_SEMESTER_START_MONDAY),
    val today: LocalDate = LocalDate.now(),
    val currentWeekNumber: Int = academicWeekForDate(LocalDate.now(), DEFAULT_SEMESTER_START_MONDAY),
    val semesterStartMonday: LocalDate = DEFAULT_SEMESTER_START_MONDAY,
    val classPeriods: List<ClassPeriod> = emptyList(),
    val courses: List<ScheduleCourse> = emptyList(),
    val courseBlocks: List<CourseBlock> = emptyList(),
    val showWeekend: Boolean = false,
    val customBackgroundUri: String = ""
)

private data class ScheduleSettingsUiState(
    val weekNumber: Int,
    val showWeekend: Boolean,
    val semesterStartMonday: LocalDate,
    val customBackgroundUri: String
)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore
) : ViewModel() {
    val uiState: StateFlow<ScheduleUiState>

    init {
        viewModelScope.launch {
            repository.seedIfEmpty()
        }

        val settingsState = combine(
            settingsStore.currentWeekNumber,
            settingsStore.showWeekend,
            settingsStore.semesterStartMonday,
            settingsStore.customBackgroundUri
        ) { weekNumber, showWeekend, semesterStartMonday, customBackgroundUri ->
            ScheduleSettingsUiState(
                weekNumber = weekNumber,
                showWeekend = showWeekend,
                semesterStartMonday = semesterStartMonday,
                customBackgroundUri = customBackgroundUri
            )
        }

        uiState = combine(
            settingsState,
            repository.classPeriods,
            repository.courses
        ) { settings, periods, courses ->
            val clampedWeekNumber = clampAcademicWeek(settings.weekNumber)
            val today = LocalDate.now()
            val coloredCourses = CourseColorMapper.assignColors(courses)
            ScheduleUiState(
                week = scheduleWeekForNumber(clampedWeekNumber, settings.semesterStartMonday),
                today = today,
                currentWeekNumber = academicWeekForDate(today, settings.semesterStartMonday),
                semesterStartMonday = normalizeSemesterStartMonday(settings.semesterStartMonday),
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
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ScheduleUiState()
        )
    }

    fun previousWeek() {
        val nextWeek = uiState.value.week.previous().number
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(nextWeek) }
    }

    fun nextWeek() {
        val nextWeek = uiState.value.week.next().number
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(nextWeek) }
    }

    fun setWeekNumber(weekNumber: Int) {
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(clampAcademicWeek(weekNumber)) }
    }

    fun setShowWeekend(showWeekend: Boolean) {
        viewModelScope.launch { settingsStore.setShowWeekend(showWeekend) }
    }

    fun returnToCurrentWeek() {
        val currentWeekNumber = academicWeekForDate(LocalDate.now(), uiState.value.semesterStartMonday)
        viewModelScope.launch { settingsStore.setCurrentWeekNumber(currentWeekNumber) }
    }

    fun moveSemesterStartByWeeks(deltaWeeks: Int) {
        val nextStart = uiState.value.semesterStartMonday.plusWeeks(deltaWeeks.toLong())
        viewModelScope.launch { settingsStore.setSemesterStartMonday(nextStart) }
    }

    fun setSemesterStartToThisWeekMonday() {
        val today = LocalDate.now()
        val currentWeekMonday = normalizeSemesterStartMonday(today)
        viewModelScope.launch {
            settingsStore.setSemesterStartMonday(currentWeekMonday)
            settingsStore.setCurrentWeekNumber(1)
        }
    }

    fun setSemesterStartDate(date: LocalDate) {
        val normalizedStart = normalizeSemesterStartMonday(date)
        val currentWeekNumber = academicWeekForDate(LocalDate.now(), normalizedStart)
        viewModelScope.launch {
            settingsStore.setSemesterStartMonday(normalizedStart)
            settingsStore.setCurrentWeekNumber(currentWeekNumber)
        }
    }

    fun setCustomBackgroundUri(uri: String) {
        viewModelScope.launch { settingsStore.setCustomBackgroundUri(uri) }
    }

    fun clearCustomBackground() {
        viewModelScope.launch { settingsStore.setCustomBackgroundUri("") }
    }
}

class ScheduleViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScheduleViewModel(repository, settingsStore) as T
    }
}

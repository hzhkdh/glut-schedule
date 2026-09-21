package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.countManualCopySourceBlocks
import com.glut.schedule.data.model.validateManualDayCopy
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.holiday.TimorHolidayCalendarParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 「调休调课」编辑页状态。
 *
 * 这里用**当前学期**而不是「正在查看的学期」：调休规则只对当前学期生效，
 * 用户在看历史学期时进来编辑的仍然是当前学期的规则（与小程序同语义）。
 */
data class HolidayAdjustmentsUiState(
    val isReady: Boolean = false,
    val semesterId: String = "",
    val semesterLabel: String = "",
    val semesterStartDate: LocalDate? = null,
    val semesterEndDate: LocalDate? = null,
    val courses: List<ScheduleCourse> = emptyList(),
    val rules: List<ManualDayCopyRule> = emptyList(),
    /** 法定放假日：目标日落在其中时需要二次确认。 */
    val holidayDates: Set<LocalDate> = emptySet(),
    /** 首页是否显示周末列：目标日是周末而首页未开启周末时，追加的课程在首页看不到。 */
    val showWeekend: Boolean = false
) {
    /** 学期起止日期缺失时无法确定可选范围，整页退化为空状态。 */
    val canEdit: Boolean
        get() = isReady && semesterStartDate != null && semesterEndDate != null
}

/** 规则列表行：复制节数一并算好，避免渲染时重复计算。 */
data class ManualDayCopyRow(
    val rule: ManualDayCopyRule,
    val courseCount: Int
)

class HolidayAdjustmentsViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore
) : ViewModel() {

    val uiState: StateFlow<HolidayAdjustmentsUiState>

    init {
        val semesterBase = combine(
            repository.currentSemester,
            repository.currentCourses,
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate
        ) { semester, courses, fallbackStart, fallbackEnd ->
            SemesterBase(
                semester = semester,
                courses = courses,
                // 学期记录里缺少校历时退回设置值——两者是同一次导入写入的，口径一致。
                startDate = semester?.semesterStartDate ?: fallbackStart,
                endDate = semester?.semesterEndDate ?: fallbackEnd
            )
        }

        val holidayDates = settingsStore.holidayCacheByYear.map { cache ->
            cache.entries.flatMapTo(mutableSetOf()) { (year, json) ->
                TimorHolidayCalendarParser.parse(json, year)?.holidayDates.orEmpty()
            }
        }

        uiState = combine(
            semesterBase,
            settingsStore.manualDayCopies,
            settingsStore.showWeekend,
            holidayDates
        ) { base, rulesBySemester, showWeekend, holidays ->
            val semesterId = base.semester?.id.orEmpty()
            HolidayAdjustmentsUiState(
                // 没有课程就没有可复制的对象；与小程序一致，此时整页进入空状态。
                isReady = base.courses.isNotEmpty(),
                semesterId = semesterId,
                semesterLabel = base.semester?.displayName.orEmpty(),
                semesterStartDate = base.startDate,
                semesterEndDate = base.endDate,
                courses = base.courses,
                rules = rulesBySemester[semesterId].orEmpty(),
                holidayDates = holidays,
                showWeekend = showWeekend
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = HolidayAdjustmentsUiState()
        )
    }

    /** 界面展示用的规则行（含每行复制节数）。 */
    fun rowsFor(state: HolidayAdjustmentsUiState): List<ManualDayCopyRow> =
        state.rules.map { rule ->
            ManualDayCopyRow(
                rule = rule,
                courseCount = countManualCopySourceBlocks(
                    state.courses,
                    rule.sourceDate,
                    state.semesterStartDate
                )
            )
        }

    fun sourceCourseCount(state: HolidayAdjustmentsUiState, sourceDate: LocalDate?): Int =
        countManualCopySourceBlocks(state.courses, sourceDate, state.semesterStartDate)

    fun isHoliday(state: HolidayAdjustmentsUiState, date: LocalDate?): Boolean =
        date != null && date in state.holidayDates

    /**
     * 添加规则。返回失败原因，成功返回 null。
     *
     * 拒绝保存的四种情况与小程序一致：日期非法或相同、原日期无课、规则已存在。
     * 目标日是法定假日 **不** 拒绝保存，只由界面先做一次二次确认。
     */
    fun addRule(
        state: HolidayAdjustmentsUiState,
        sourceDate: LocalDate?,
        targetDate: LocalDate?
    ): String? {
        if (!state.canEdit) return "当前学期课表尚未就绪"
        validateManualDayCopy(sourceDate, targetDate)?.let { return it }
        if (sourceDate == null || targetDate == null) return "请选择有效日期"
        if (sourceCourseCount(state, sourceDate) == 0) return "原日期没有可复制课程"
        val rule = ManualDayCopyRule(sourceDate = sourceDate, targetDate = targetDate)
        if (state.rules.any { it.id == rule.id }) return "该调课已存在"
        viewModelScope.launch {
            // 写入前重新读取最新状态，避免连续快速添加时基于旧列表互相覆盖。
            val latest = uiState.value
            if (latest.rules.any { it.id == rule.id }) return@launch
            settingsStore.setManualDayCopies(latest.semesterId, latest.rules + rule)
        }
        return null
    }

    fun deleteRule(rule: ManualDayCopyRule) {
        viewModelScope.launch {
            val latest = uiState.value
            settingsStore.setManualDayCopies(
                latest.semesterId,
                latest.rules.filterNot { it.id == rule.id }
            )
        }
    }

    private data class SemesterBase(
        val semester: AcademicSemester?,
        val courses: List<ScheduleCourse>,
        val startDate: LocalDate?,
        val endDate: LocalDate?
    )
}

class HolidayAdjustmentsViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HolidayAdjustmentsViewModel(repository, settingsStore) as T
    }
}

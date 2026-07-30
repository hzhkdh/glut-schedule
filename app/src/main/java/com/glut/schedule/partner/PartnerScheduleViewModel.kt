package com.glut.schedule.partner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_PINGFENG
import com.glut.schedule.data.settings.PartnerScheduleViewMode
import com.glut.schedule.data.settings.ScheduleSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.LocalDate

data class PartnerScheduleUiState(
    val week: Int = 1,
    val maxWeek: Int = 20,
    val semesterStartMonday: LocalDate = LocalDate.now(),
    val semesterEndDate: LocalDate = LocalDate.now(),
    val classPeriods: List<ClassPeriod> = emptyList(),
    val campusKey: String = "guilin-yanshan",
    val today: LocalDate = LocalDate.now(),
    val currentWeekNumber: Int = 1,
    val showWeekend: Boolean = false,
    val showNoon: Boolean = false,
    val viewMode: PartnerScheduleViewMode = PartnerScheduleViewMode.COMBINED,
    val ownCourses: List<PartnerCourse> = emptyList(),
    val partnerSnapshot: PartnerScheduleSnapshot? = null,
    val myColor: PartnerIdentityColor = PartnerIdentityColor.BLUE,
    val activeInvite: StoredPartnerInvite? = null,
    val isBusy: Boolean = false,
    val message: String = ""
) {
    val combinedCourses: List<PartnerCourse>
        get() = ownCourses + partnerSnapshot.orEmptyCourses()

    val displayedCourses: List<PartnerCourse>
        get() = partnerCoursesForMode(viewMode, ownCourses, partnerSnapshot.orEmptyCourses())
}

private fun PartnerScheduleSnapshot?.orEmptyCourses(): List<PartnerCourse> = this?.courses.orEmpty()

private data class LocalScheduleData(
    val courses: List<ScheduleCourse>,
    val classPeriods: List<ClassPeriod>,
    val semester: AcademicSemester?
)

private data class CalendarBase(
    val week: Int,
    val start: LocalDate,
    val end: LocalDate,
    val campus: CampusType,
    val subCampus: String
)

private data class CalendarData(
    val base: CalendarBase,
    val showWeekend: Boolean,
    val showNoon: Boolean,
    val viewMode: PartnerScheduleViewMode
)

private data class StoredPartnerData(
    val snapshot: PartnerScheduleSnapshot?,
    val invite: StoredPartnerInvite?,
    val myColor: PartnerIdentityColor
)

class PartnerScheduleViewModel(
    repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val storage: PartnerScheduleStorage,
    private val gateway: PartnerScheduleGateway
) : ViewModel() {
    private val selectedWeek = MutableStateFlow<Int?>(null)
    private val isBusy = MutableStateFlow(false)
    private val message = MutableStateFlow("")
    private val operationMutex = Mutex()

    val uiState: StateFlow<PartnerScheduleUiState>

    init {
        val localData = combine(
            repository.currentCourses,
            repository.currentClassPeriods,
            repository.currentSemester
        ) { courses, classPeriods, semester ->
            LocalScheduleData(courses, classPeriods, semester)
        }
        val calendarBase = combine(
            settingsStore.currentWeekNumber,
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate,
            settingsStore.campusType,
            settingsStore.guilinSubCampus
        ) { week, start, end, campus, subCampus ->
            CalendarBase(week, start, end, campus, subCampus)
        }
        val calendarData = combine(
            calendarBase,
            settingsStore.partnerShowWeekend,
            settingsStore.partnerShowNoon,
            settingsStore.partnerViewMode
        ) { base, showWeekend, showNoon, viewMode ->
            CalendarData(base, showWeekend, showNoon, viewMode)
        }
        val storedData = combine(
            storage.partnerSnapshot,
            storage.activeInvite,
            storage.myColor
        ) { snapshot, invite, color ->
            StoredPartnerData(snapshot, invite, color)
        }
        val baseState = combine(localData, calendarData, storedData, selectedWeek) {
                local, calendar, stored, selected ->
            val start = local.semester?.semesterStartDate ?: calendar.base.start
            val end = local.semester?.semesterEndDate ?: calendar.base.end
            val maxWeek = academicMaxWeekForCalendar(start, end)
            val week = clampAcademicWeek(selected ?: calendar.base.week, maxWeek)
            val campusKey = when (calendar.base.campus) {
                CampusType.NANNING -> "nanning"
                CampusType.GUILIN -> if (calendar.base.subCampus == GUILIN_SUB_CAMPUS_PINGFENG) {
                    "guilin-pingfeng"
                } else {
                    "guilin-yanshan"
                }
            }
            val ownSnapshot = createPartnerScheduleSnapshot(
                identityColor = stored.myColor,
                campus = campusKey,
                semesterStartMonday = start,
                semesterEndDate = end,
                courses = local.courses,
                classPeriods = local.classPeriods,
                shareRoom = true,
                shareTeacher = true
            )
            PartnerScheduleUiState(
                week = week,
                maxWeek = maxWeek,
                semesterStartMonday = start,
                semesterEndDate = end,
                classPeriods = local.classPeriods,
                campusKey = campusKey,
                today = LocalDate.now(),
                currentWeekNumber = academicWeekForDate(LocalDate.now(), start, maxWeek),
                showWeekend = calendar.showWeekend,
                showNoon = calendar.showNoon,
                viewMode = calendar.viewMode,
                ownCourses = ownSnapshot.courses,
                partnerSnapshot = stored.snapshot,
                myColor = stored.myColor,
                activeInvite = stored.invite
            )
        }
        uiState = combine(baseState, isBusy, message) { base, busy, currentMessage ->
            base.copy(isBusy = busy, message = currentMessage)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PartnerScheduleUiState()
        )
    }

    fun previousWeek() {
        selectedWeek.value = clampAcademicWeek(uiState.value.week - 1, uiState.value.maxWeek)
    }

    fun nextWeek() {
        selectedWeek.value = clampAcademicWeek(uiState.value.week + 1, uiState.value.maxWeek)
    }

    fun setWeek(week: Int) {
        selectedWeek.value = clampAcademicWeek(week, uiState.value.maxWeek)
    }

    fun setMyColor(color: PartnerIdentityColor) {
        if (isBusy.value || storage.activeInvite.value != null) {
            message.value = "请先完成当前操作并撤销邀请码，再修改身份色"
            return
        }
        if (color == uiState.value.partnerSnapshot?.identityColor) {
            message.value = "这是TA的颜色，请选择其他颜色"
            return
        }
        storage.setMyColor(color)
    }

    fun setShowWeekend(showWeekend: Boolean) {
        viewModelScope.launch { settingsStore.setPartnerShowWeekend(showWeekend) }
    }

    fun setShowNoon(showNoon: Boolean) {
        viewModelScope.launch { settingsStore.setPartnerShowNoon(showNoon) }
    }

    fun setViewMode(mode: PartnerScheduleViewMode) {
        viewModelScope.launch { settingsStore.setPartnerViewMode(mode) }
    }

    fun returnToCurrentWeek() {
        val state = uiState.value
        selectedWeek.value = academicWeekForDate(
            LocalDate.now(),
            state.semesterStartMonday,
            state.maxWeek
        )
    }

    fun generateInvite(shareRoom: Boolean, shareTeacher: Boolean) {
        val state = uiState.value
        if (!canGeneratePartnerInvite(
                hasCourses = state.ownCourses.isNotEmpty(),
                isBusy = state.isBusy,
                hasActiveInvite = state.activeInvite != null
            )
        ) return
        launchOperation("邀请码已生成") {
            val snapshot = PartnerScheduleSnapshot(
                identityColor = state.myColor,
                campus = state.campusKey,
                semesterStartMonday = state.semesterStartMonday,
                semesterEndDate = state.semesterEndDate,
                courses = state.ownCourses.map { course ->
                    course.copy(
                        room = course.room.takeIf { shareRoom },
                        teacher = course.teacher.takeIf { shareTeacher },
                        ownerColor = state.myColor
                    )
                }
            )
            storage.saveActiveInvite(gateway.createInvite(snapshot))
        }
    }

    fun importInvite(input: String) {
        if (isBusy.value) return
        launchOperation("TA的课表已导入") {
            val snapshot = gateway.fetchInvite(input)
            storage.setMyColor(
                partnerImportLocalColor(
                    current = storage.myColor.value,
                    partner = snapshot.identityColor,
                    hasActiveInvite = storage.activeInvite.value != null
                )
            )
            storage.savePartnerSnapshot(snapshot)
        }
    }

    fun revokeInvite() {
        val invite = storage.activeInvite.value ?: return
        if (isBusy.value) return
        launchOperation("邀请码已撤销") {
            gateway.revokeInvite(invite.code, invite.revokeToken)
            storage.clearActiveInvite()
        }
    }

    fun deletePartnerSnapshot() {
        storage.clearPartnerSnapshot()
        message.value = "已删除本地TA的课表"
    }

    fun clearMessage() {
        message.value = ""
    }

    private fun launchOperation(successMessage: String, block: suspend () -> Unit) {
        // 在 UI 事件线程同步抢占，避免快速双击在协程启动前穿透 isBusy 检查。
        if (!operationMutex.tryLock()) return
        isBusy.value = true
        message.value = ""
        viewModelScope.launch {
            try {
                runCatching { block() }
                    .onSuccess { message.value = successMessage }
                    .onFailure { error -> message.value = error.message ?: "操作失败，请稍后重试" }
            } finally {
                isBusy.value = false
                operationMutex.unlock()
            }
        }
    }

}

class PartnerScheduleViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val storage: PartnerScheduleStorage,
    private val gateway: PartnerScheduleGateway
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(PartnerScheduleViewModel::class.java))
        return PartnerScheduleViewModel(repository, settingsStore, storage, gateway) as T
    }
}

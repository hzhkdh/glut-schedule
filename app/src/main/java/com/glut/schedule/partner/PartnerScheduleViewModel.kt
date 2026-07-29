package com.glut.schedule.partner

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_PINGFENG
import com.glut.schedule.data.settings.ScheduleSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class PartnerScheduleUiState(
    val week: Int = 1,
    val maxWeek: Int = 20,
    val semesterStartMonday: LocalDate = LocalDate.now(),
    val semesterEndDate: LocalDate = LocalDate.now(),
    val classPeriods: List<ClassPeriod> = emptyList(),
    val campusKey: String = "guilin-yanshan",
    val ownCourses: List<PartnerCourse> = emptyList(),
    val partnerSnapshot: PartnerScheduleSnapshot? = null,
    val myColor: PartnerIdentityColor = PartnerIdentityColor.BLUE,
    val activeInvite: StoredPartnerInvite? = null,
    val isBusy: Boolean = false,
    val message: String = ""
) {
    val combinedCourses: List<PartnerCourse>
        get() = ownCourses + partnerSnapshot.orEmptyCourses()

    val commonFreeCountToday: Int
        get() {
            if (partnerSnapshot == null || classPeriods.isEmpty()) return 0
            val day = LocalDate.now().dayOfWeek.value
            return commonFreeSegments(
                dayOfWeek = day,
                week = week,
                dayStart = classPeriods.first().startsAt,
                dayEnd = classPeriods.last().endsAt,
                courses = combinedCourses
            ).size
        }
}

private fun PartnerScheduleSnapshot?.orEmptyCourses(): List<PartnerCourse> = this?.courses.orEmpty()

private data class LocalScheduleData(
    val courses: List<ScheduleCourse>,
    val classPeriods: List<ClassPeriod>,
    val semester: AcademicSemester?
)

private data class CalendarData(
    val week: Int,
    val start: LocalDate,
    val end: LocalDate,
    val campus: CampusType,
    val subCampus: String
)

private data class StoredPartnerData(
    val snapshot: PartnerScheduleSnapshot?,
    val invite: StoredPartnerInvite?,
    val myColor: PartnerIdentityColor
)

class PartnerScheduleViewModel(
    repository: ScheduleRepository,
    settingsStore: ScheduleSettingsStore,
    private val storage: PartnerScheduleStorage,
    private val gateway: PartnerScheduleGateway
) : ViewModel() {
    private val selectedWeek = MutableStateFlow<Int?>(null)
    private val isBusy = MutableStateFlow(false)
    private val message = MutableStateFlow("")

    val uiState: StateFlow<PartnerScheduleUiState>

    init {
        val localData = combine(
            repository.currentCourses,
            repository.currentClassPeriods,
            repository.currentSemester
        ) { courses, classPeriods, semester ->
            LocalScheduleData(courses, classPeriods, semester)
        }
        val calendarData = combine(
            settingsStore.currentWeekNumber,
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate,
            settingsStore.campusType,
            settingsStore.guilinSubCampus
        ) { week, start, end, campus, subCampus ->
            CalendarData(week, start, end, campus, subCampus)
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
            val start = local.semester?.semesterStartDate ?: calendar.start
            val end = local.semester?.semesterEndDate ?: calendar.end
            val maxWeek = academicMaxWeekForCalendar(start, end)
            val week = clampAcademicWeek(selected ?: calendar.week, maxWeek)
            val campusKey = when (calendar.campus) {
                CampusType.NANNING -> "nanning"
                CampusType.GUILIN -> if (calendar.subCampus == GUILIN_SUB_CAMPUS_PINGFENG) {
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
        storage.setMyColor(color)
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
        launchOperation("TA课表已导入") {
            val snapshot = gateway.fetchInvite(input)
            if (snapshot.identityColor == storage.myColor.value) {
                storage.setMyColor(snapshot.identityColor.opposite())
            }
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
        message.value = "已删除本地TA课表"
    }

    fun clearMessage() {
        message.value = ""
    }

    private fun launchOperation(successMessage: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            isBusy.value = true
            message.value = ""
            runCatching { block() }
                .onSuccess { message.value = successMessage }
                .onFailure { error -> message.value = error.message ?: "操作失败，请稍后重试" }
            isBusy.value = false
        }
    }

}

private fun PartnerIdentityColor.opposite(): PartnerIdentityColor =
    if (this == PartnerIdentityColor.PINK) PartnerIdentityColor.BLUE else PartnerIdentityColor.PINK

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

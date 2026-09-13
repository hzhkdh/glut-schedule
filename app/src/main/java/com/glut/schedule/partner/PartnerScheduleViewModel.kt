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
    val profiles: List<ImportedPartnerProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val myColor: PartnerIdentityColor = PartnerIdentityColor.BLUE,
    val activeInvite: StoredPartnerInvite? = null,
    val isBusy: Boolean = false,
    val message: String = ""
) {
    val selectedProfile: ImportedPartnerProfile?
        get() = profiles.firstOrNull { it.id == selectedProfileId } ?: profiles.firstOrNull()

    val displayedCourses: List<PartnerCourse>
        get() = partnerCoursesForMode(
            mode = viewMode,
            ownCourses = ownCourses,
            // 两个档案只是两个可切换槽位，任何时候都只与当前 TA 进行双人对比。
            partnerCourses = selectedProfile?.displayCourses().orEmpty()
        )
}

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
    val profiles: List<ImportedPartnerProfile>,
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
    private val selectedProfileId = MutableStateFlow<String?>(null)
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
            storage.profiles,
            storage.activeInvite,
            storage.myColor
        ) { snapshot, invite, color ->
            StoredPartnerData(snapshot, invite, color)
        }
        val baseState = combine(localData, calendarData, storedData, selectedWeek, selectedProfileId) {
                local, calendar, stored, selected, selectedProfile ->
            // 双人页以第一份已导入课表为日历基准，不将本机主课表混入展示数据。
            val start = stored.profiles.firstOrNull()?.snapshot?.semesterStartMonday
                ?: local.semester?.semesterStartDate ?: calendar.base.start
            val end = stored.profiles.firstOrNull()?.snapshot?.semesterEndDate
                ?: local.semester?.semesterEndDate ?: calendar.base.end
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
                profiles = stored.profiles,
                selectedProfileId = selectedProfile,
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

    fun selectProfile(id: String) {
        selectedProfileId.value = id
        if (uiState.value.viewMode != PartnerScheduleViewMode.PARTNER) {
            setViewMode(PartnerScheduleViewMode.PARTNER)
        }
    }

    /** 按导入顺序切换当前 TA；一起模式和只看 TA 模式共用同一选择。 */
    fun cycleProfile() {
        val profiles = uiState.value.profiles
        if (profiles.size < 2) return
        val currentIndex = profiles.indexOfFirst { it.id == uiState.value.selectedProfile?.id }
        val nextIndex = if (currentIndex in profiles.indices) {
            (currentIndex + 1) % profiles.size
        } else {
            0
        }
        val nextProfile = profiles[nextIndex]
        selectedProfileId.value = nextProfile.id
        message.value = "已切换到${nextProfile.name}"
    }

    fun importInvite(input: String, name: String, replaceProfileId: String? = null) {
        if (isBusy.value) return
        launchOperation("课表已导入") {
            val snapshot = gateway.fetchInvite(input)
            val currentState = uiState.value
            val profiles = storage.profiles.value
            val replacing = profiles.firstOrNull { it.id == replaceProfileId }
            require(replacing != null || profiles.size < 2) { "最多只能导入两份课表，请先更新或删除已有课表" }
            profiles.firstOrNull { it.id != replaceProfileId }?.snapshot?.let { first ->
                requirePartnerSemesterCompatible(first.semesterStartMonday, first.semesterEndDate, snapshot)
            }
            val usedColors = profiles
                .filterNot { it.id == replaceProfileId }
                .mapTo(mutableSetOf(currentState.myColor)) { it.displayColor }
            val displayColor = resolveImportedProfileColor(snapshot.identityColor, usedColors)
            val profile = ImportedPartnerProfile(
                id = replacing?.id ?: "profile-${System.currentTimeMillis()}",
                name = name.trim().take(20).ifBlank { partnerProfileDefaultName(profiles.size) },
                snapshot = snapshot,
                displayColor = displayColor
            )
            storage.saveProfiles(
                (profiles.filterNot { it.id == replaceProfileId } + profile).take(2)
            )
            selectedProfileId.value = profile.id
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

    fun renameProfile(id: String, name: String) {
        val normalized = name.trim().take(20)
        if (normalized.isBlank()) {
            message.value = "昵称不能为空"
            return
        }
        storage.saveProfiles(storage.profiles.value.map { if (it.id == id) it.copy(name = normalized) else it })
    }

    fun deleteProfile(id: String) {
        storage.saveProfiles(storage.profiles.value.filterNot { it.id == id })
        if (selectedProfileId.value == id) selectedProfileId.value = storage.profiles.value.firstOrNull()?.id
        message.value = "已删除本地课表"
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

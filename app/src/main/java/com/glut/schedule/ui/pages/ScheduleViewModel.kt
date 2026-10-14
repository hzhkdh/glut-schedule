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
import com.glut.schedule.data.model.HiddenCardScope
import com.glut.schedule.data.model.HiddenCourseRule
import com.glut.schedule.data.model.AutoDayCopyRule
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.ScheduleRefreshDiff
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.buildScheduleRefreshDiff
import com.glut.schedule.data.model.applyHiddenCourseRules
import com.glut.schedule.data.model.hiddenCardCount
import com.glut.schedule.data.model.hiddenCourseKey
import com.glut.schedule.data.model.ScheduleBackgroundPreferences
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.DEFAULT_SEMESTER_START_MONDAY
import com.glut.schedule.data.model.DEFAULT_SEMESTER_END_DATE
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.DEFAULT_BACKGROUND_DIM_AMOUNT
import com.glut.schedule.data.model.NormalizedCropRect
import com.glut.schedule.data.model.manualCopyBlocksForWeek
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicMaxWeekForSemester
import com.glut.schedule.data.model.clampAcademicWeek
import com.glut.schedule.data.model.countDistinctCourseTitles
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.data.model.normalizeSemesterStartMonday
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.capabilitiesFor
import com.glut.schedule.data.settings.ClassPeriodProfile
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_DEFAULT
import com.glut.schedule.data.settings.GUILIN_SUB_CAMPUS_PINGFENG
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import com.glut.schedule.service.academic.AcademicSemesterImportPayload
import com.glut.schedule.service.academic.AcademicSemesterImportService
import com.glut.schedule.service.academic.AcademicSemesterCalendarResolver
import com.glut.schedule.service.academic.AcademicSemesterViewPlanner
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.holiday.AutoDayCopyCoordinator
import com.glut.schedule.service.holiday.MakeupTableClient
import com.glut.schedule.service.holiday.TimorHolidayCalendarParser
import com.glut.schedule.service.holiday.TimorHolidayClient
import com.glut.schedule.service.holiday.refreshMakeupTable
import com.glut.schedule.service.holiday.refreshMissingHolidayYears
import com.glut.schedule.ui.SingleFlightGuard
import com.glut.schedule.service.academic.shouldUseExistingAcademicCookie
import com.glut.schedule.service.graduate.GraduateImportService
import com.glut.schedule.service.graduate.GraduateSessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    val guilinSubCampus: String = GUILIN_SUB_CAMPUS_DEFAULT,
    val classPeriods: List<ClassPeriod> = emptyList(),
    val classPeriodProfileOverrides: Map<ClassPeriodProfile, List<ClassPeriod>> = emptyMap(),
    val courses: List<ScheduleCourse> = emptyList(),
    /** 完整课表，**未**剔除用户手动隐藏的卡片。判空态与周次推导用它，渲染用 [courses]。 */
    val allCourses: List<ScheduleCourse> = emptyList(),
    /** 当前学期的手动隐藏记录；查看历史学期时恒为空，历史快照不被污染。 */
    val hiddenCardRules: List<HiddenCourseRule> = emptyList(),
    /** 真正命中现有课次的隐藏规则数，用于刷新弹窗与设置页上的「N 张」。 */
    val hiddenCardCount: Int = 0,
    /** 当前学期 id。隐藏记录按它写入。 */
    val currentSemesterId: String = "",
    val courseBlocks: List<CourseBlock> = emptyList(),
    val showWeekend: Boolean = false,
    val showNoon: Boolean = false,
    /** 课表是否绘制网格线；默认关闭，只在设置页开启后才有线。 */
    val showGridLines: Boolean = false,
    val customBackgroundUri: String = "",
    val customBackgroundCrop: NormalizedCropRect? = null,
    val remoteBackgroundId: String = "",
    val remoteBackgroundSha256: String = "",
    val remoteBackgroundDisplayName: String = "",
    val backgroundDimAmount: Float = DEFAULT_BACKGROUND_DIM_AMOUNT,
    val courseColorOverrides: Map<String, String> = emptyMap(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val semesters: List<AcademicSemester> = emptyList(),
    val viewedSemester: AcademicSemester? = null,
    val isHistoricalSemester: Boolean = false,
    val hasAuthoritativeCalendar: Boolean = true,
    /** 法定放假日，供日期栏显示「休」。历史学期不取用，角标依附于当前学期。 */
    val holidayDates: Set<LocalDate> = emptySet(),
    /** 当前学期的手动调休调课规则；查看历史学期时恒为空，历史快照不被污染。 */
    val manualDayCopies: List<ManualDayCopyRule> = emptyList(),
    val isInitialized: Boolean = false
)

/**
 * 删除卡片后的一次性撤销提示。
 *
 * [previousRules] 是删除前该学期的完整规则列表——撤销就是把它整份写回去，
 * 比「删掉刚加的那一条」更稳：期间若有别的写入，整份还原也不会留下半截状态。
 * [id] 用于让界面区分「这是一条新的提示」，避免同一条被重复弹。
 */
data class HiddenUndo(
    val id: Int,
    val semesterId: String,
    val previousRules: List<HiddenCourseRule>
)

/** 刷新前确认弹窗：[count] 是当前真正还藏着的卡片数，勾选框默认保留。 */
data class RefreshConfirmState(val count: Int)

/**
 * 设置页「已隐藏的卡片」列表的一行。
 *
 * [existsInSchedule] 为 false 表示这条记录对应的课次已经不在当前课表里了（教务改了排课）。
 * 这类记录我们**有意保留**——课再排回来依然是隐藏的——但界面上要标注出来，
 * 免得用户看着一条「对不上任何课程」的记录犯迷糊。
 */
data class HiddenCardItem(
    val id: String,
    val label: String,
    val existsInSchedule: Boolean
)

internal fun selectedWeekAfterCalendarRefresh(
    selectedWeek: Int,
    semesterStartMonday: LocalDate,
    semesterEndDate: LocalDate
): Int = clampAcademicWeek(
    selectedWeek,
    academicMaxWeekForCalendar(semesterStartMonday, semesterEndDate)
)

private data class ScheduleAppearanceSettings(
    val backgroundPreferences: ScheduleBackgroundPreferences,
    val campusType: CampusType,
    val guilinSubCampus: String,
    val classPeriodProfileOverrides: Map<ClassPeriodProfile, List<ClassPeriod>>
)

private data class ScheduleSettingsUiState(
    val weekNumber: Int,
    val showWeekend: Boolean,
    val showNoon: Boolean,
    val showGridLines: Boolean,
    val semesterStartMonday: LocalDate,
    val semesterEndDate: LocalDate,
    val customBackgroundUri: String,
    val customBackgroundCrop: NormalizedCropRect?,
    val remoteBackgroundId: String,
    val remoteBackgroundSha256: String,
    val remoteBackgroundDisplayName: String,
    val backgroundDimAmount: Float,
    val campusType: CampusType,
    val guilinSubCampus: String = GUILIN_SUB_CAMPUS_DEFAULT,
    val classPeriodProfileOverrides: Map<ClassPeriodProfile, List<ClassPeriod>> = emptyMap(),
    val holidayDates: Set<LocalDate> = emptySet(),
    val manualDayCopiesBySemester: Map<String, List<ManualDayCopyRule>> = emptyMap(),
    val autoDayCopiesBySemester: Map<String, List<AutoDayCopyRule>> = emptyMap()
)

private data class ScheduleCalendarSettings(
    val weekNumber: Int,
    val showWeekend: Boolean,
    val showNoon: Boolean,
    val semesterStartMonday: LocalDate,
    val semesterEndDate: LocalDate
)

/**
 * 配色与过滤的结果。
 *
 * [allCourses] 是完整课表，[visibleCourses] 是剔除用户手动隐藏之后的展示口径，两者必须分开：
 * 判空态、推导最大周次、算「隐藏了几张」都要用完整列表，否则用户把某周的课全隐藏之后，
 * 首页会从「有课表」变成「还没有课表」。
 */
private data class ColoredCoursesState(
    val allCourses: List<ScheduleCourse>,
    val visibleCourses: List<ScheduleCourse>,
    val overrides: Map<String, String>,
    val hiddenRules: List<HiddenCourseRule>
)

/**
 * 日期栏角标所需的附加数据。
 *
 * 与课程数据分开聚合：节假日缓存的更新频率远低于课表，混进主 combine 会让
 * 每次节假日落盘都重建一遍课程块。
 */
private data class ScheduleCalendarExtras(
    val holidayDates: Set<LocalDate>,
    val manualDayCopiesBySemester: Map<String, List<ManualDayCopyRule>>,
    val autoDayCopiesBySemester: Map<String, List<AutoDayCopyRule>>
)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val semesterImportService: AcademicSemesterImportService,
    private val apiProbeService: ApiProbeService,
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator,
    private val timorHolidayClient: TimorHolidayClient = TimorHolidayClient(),
    private val makeupTableClient: MakeupTableClient = MakeupTableClient()
) : ViewModel() {
    val uiState: StateFlow<ScheduleUiState>
    private var initialWeekSet = false
    private val isRefreshing = MutableStateFlow(false)
    private val refreshGuard = SingleFlightGuard()
    private val holidayFetchGuard = SingleFlightGuard()
    private val makeupTableFetchGuard = SingleFlightGuard()
    private val message = MutableStateFlow("")

    /**
     * 删除卡片后的一次性撤销提示。
     *
     * 单独一个 Flow 而不是塞进 `ScheduleUiState`：那个 state 已经是一个 5 路 combine，
     * 再加一路会牵动整个 copy 链路；而且撤销提示本来就该是「一次性事件」，不适合常驻状态。
     */
    private val _hiddenUndo = MutableStateFlow<HiddenUndo?>(null)
    val hiddenUndo: StateFlow<HiddenUndo?> = _hiddenUndo.asStateFlow()
    private var hiddenUndoSequence = 0

    /** 刷新前确认弹窗的状态；为空表示不需要弹（没藏着卡片，或用户已处理）。 */
    private val _refreshConfirm = MutableStateFlow<RefreshConfirmState?>(null)
    val refreshConfirm: StateFlow<RefreshConfirmState?> = _refreshConfirm.asStateFlow()

    /**
     * 本次刷新的变化明细；为空表示没有变化（或提示已被消除）。
     *
     * 与 `_refreshConfirm` 一样单独开一个 Flow：`ScheduleUiState` 的 combine 已经排满，
     * 而这批数据的消费方只有首页一个。
     */
    private val _refreshDiff = MutableStateFlow<ScheduleRefreshDiff?>(null)
    val refreshDiff: StateFlow<ScheduleRefreshDiff?> = _refreshDiff.asStateFlow()

    /** 刷新开始时记下的旧课表，供保存成功后比对。 */
    private var refreshBaseCourses: List<ScheduleCourse> = emptyList()

    /**
     * 正在查看的学期的教务调课记录，用于给卡片打「调」/「补」角标。
     *
     * 单独一个 Flow 而不是并进 `ScheduleUiState`：那个 state 的 combine 已经排满五路，
     * 再加一路要牵动整条 copy 链路，而这批数据的消费方只有课表网格一个。
     * 历史学期同样有记录（快照里存着），所以这里不做学期过滤——网格自己会按周次匹配。
     */
    val semesterAdjustments: StateFlow<List<SemesterAdjustment>> = repository.semesterAdjustments
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        val initialWeek = scheduleWeekForNumber(
            academicWeekForDate(LocalDate.now(), DEFAULT_SEMESTER_START_MONDAY),
            DEFAULT_SEMESTER_START_MONDAY
        )

        viewModelScope.launch {
            repository.seedIfEmpty()
        }

        // 冷启动同步一次对照表：学校通知发布不定时，用户不该为了拿到它先手动下拉刷新一次。
        // 拿到新表就立刻重算（重算是纯函数、很便宜）——表里收录了本学期时推导会整学期退场，
        // 所以这一步不重算的话，「以学校通知为准」要等用户下次刷新才生效。
        viewModelScope.launch {
            if (!fetchMakeupTable()) return@launch
            val semesterId = repository.currentSemester.first()?.id.orEmpty()
            if (semesterId.isNotBlank()) autoDayCopyCoordinator.recompose(semesterId)
        }

        val calendarExtrasState = combine(
            holidayDatesFlow(),
            settingsStore.manualDayCopies,
            // 自动调课规则（开关关闭时这个 Flow 恒为空表，闸门在 ScheduleSettingsStore 里）。
            settingsStore.autoDayCopies
        ) { holidayDates, manualDayCopies, autoDayCopies ->
            ScheduleCalendarExtras(
                holidayDates = holidayDates,
                manualDayCopiesBySemester = manualDayCopies,
                autoDayCopiesBySemester = autoDayCopies
            )
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
            combine(
                settingsStore.backgroundPreferences,
                settingsStore.campusType,
                settingsStore.guilinSubCampus,
                settingsStore.classPeriodProfileOverrides
            ) { backgroundPreferences, campusType, guilinSubCampus, profileOverrides ->
                ScheduleAppearanceSettings(
                    backgroundPreferences = backgroundPreferences,
                    campusType = campusType,
                    guilinSubCampus = guilinSubCampus,
                    classPeriodProfileOverrides = profileOverrides
                )
            },
            calendarExtrasState,
            // 网格线单独作为第四个流接进来：内层 combine 已经满了 5 个流，
            // 而 kotlinx 只提供到 5 流的带类型重载，硬塞进去会退化成 Array 版本。
            settingsStore.showGridLines
        ) { base, appearance, extras, showGridLines ->
            ScheduleSettingsUiState(
                weekNumber = base.weekNumber,
                showWeekend = base.showWeekend,
                showNoon = base.showNoon,
                showGridLines = showGridLines,
                semesterStartMonday = base.semesterStartMonday,
                semesterEndDate = base.semesterEndDate,
                customBackgroundUri = appearance.backgroundPreferences.uri,
                customBackgroundCrop = appearance.backgroundPreferences.crop,
                remoteBackgroundId = appearance.backgroundPreferences.remoteId,
                remoteBackgroundSha256 = appearance.backgroundPreferences.remoteSha256,
                remoteBackgroundDisplayName = appearance.backgroundPreferences.remoteDisplayName,
                backgroundDimAmount = appearance.backgroundPreferences.dimAmount,
                campusType = appearance.campusType,
                guilinSubCampus = appearance.guilinSubCampus,
                classPeriodProfileOverrides = appearance.classPeriodProfileOverrides,
                holidayDates = extras.holidayDates,
                manualDayCopiesBySemester = extras.manualDayCopiesBySemester,
                autoDayCopiesBySemester = extras.autoDayCopiesBySemester
            )
        }

        val coloredCoursesState = combine(
            repository.courses,
            settingsStore.courseColorOverrides,
            settingsStore.hiddenCourseRules,
            repository.viewedSemester,
            repository.semesters
        ) { courses, overrides, hiddenRulesBySemester, viewedSemester, semesters ->
            // 调休规则与隐藏记录都只作用于当前学期：历史学期是只读快照，
            // 拿当前学期的记录去过滤历史课表会把不相关的卡片整片抹掉。
            val isHistorical = viewedSemester != null && !viewedSemester.isCurrent
            val currentSemesterId = semesters.firstOrNull { it.isCurrent }?.id.orEmpty()
            val hiddenRules = if (isHistorical) {
                emptyList()
            } else {
                hiddenRulesBySemester[currentSemesterId].orEmpty()
            }
            // 顺序是「先配色、后过滤」，不能颠倒：assignColors 按输入顺序占位并做相邻颜色避让，
            // 先过滤会释放调色板索引、让其余可见课程跟着换色 —— 用户删一张卡却看到别的卡变色。
            val colored = kotlinx.coroutines.withContext(Dispatchers.Default) {
                CourseColorMapper.assignColors(courses, overrides)
            }
            ColoredCoursesState(
                allCourses = colored,
                visibleCourses = applyHiddenCourseRules(colored, hiddenRules),
                overrides = overrides,
                hiddenRules = hiddenRules
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
                // 推导最大周次必须用完整课表：过滤后的列表会让历史学期被截短。
                courses = coloredState.allCourses,
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
            val visibleCourses = coloredState.visibleCourses
            // 调休调课只作用于当前学期：历史学期是只读快照，当前学期的规则不得渗进去。
            val currentSemesterId = semesters.firstOrNull { it.isCurrent }?.id.orEmpty()
            //
            // 自动与手动规则在这里合并成同一种：两者语义完全一致（manualCopyBlocksForWeek
            // 只认 source/target），副本 id 都带 -manual-copy- 后缀，于是日期角标、冲突分组、
            // 「长按不改副本」的排除逻辑全部自动生效，ScheduleScreen 与小组件一行都不用改。
            val dayCopyRules = if (isHistorical) {
                emptyList()
            } else {
                settings.manualDayCopiesBySemester[currentSemesterId].orEmpty() +
                    settings.autoDayCopiesBySemester[currentSemesterId].orEmpty().map { it.asManualRule() }
            }
            ScheduleUiState(
                week = scheduleWeekForNumber(clampedWeekNumber, normalizedStart, maxAcademicWeek),
                today = today,
                currentWeekNumber = academicWeekForDate(today, normalizedStart, maxAcademicWeek),
                semesterStartMonday = normalizedStart,
                semesterEndDate = resolvedEnd,
                maxAcademicWeek = maxAcademicWeek,
                campusType = settings.campusType,
                guilinSubCampus = settings.guilinSubCampus,
                classPeriods = periods,
                classPeriodProfileOverrides = settings.classPeriodProfileOverrides,
                courses = visibleCourses,
                allCourses = coloredState.allCourses,
                hiddenCardRules = coloredState.hiddenRules,
                // 只统计「确实命中现有课次」的规则，避免弹窗写「保留了 8 张」却一张都看不见。
                hiddenCardCount = hiddenCardCount(coloredState.allCourses, coloredState.hiddenRules),
                currentSemesterId = currentSemesterId,
                courseBlocks = visibleCourses.flatMap { course ->
                    course.occurrences
                        .filter { occurrence -> occurrence.isActiveInWeek(clampedWeekNumber) }
                        .map { occurrence ->
                            CourseBlock(course = course, occurrence = occurrence)
                        }
                } + manualCopyBlocksForWeek(
                    courses = visibleCourses,
                    rules = dayCopyRules,
                    weekNumber = clampedWeekNumber,
                    weekMonday = scheduleWeekForNumber(clampedWeekNumber, normalizedStart, maxAcademicWeek).monday
                ),
                holidayDates = if (isHistorical) emptySet() else settings.holidayDates,
                manualDayCopies = dayCopyRules,
                showWeekend = settings.showWeekend,
                showNoon = settings.showNoon,
                showGridLines = settings.showGridLines,
                customBackgroundUri = settings.customBackgroundUri,
                customBackgroundCrop = settings.customBackgroundCrop,
                remoteBackgroundId = settings.remoteBackgroundId,
                remoteBackgroundSha256 = settings.remoteBackgroundSha256,
                remoteBackgroundDisplayName = settings.remoteBackgroundDisplayName,
                backgroundDimAmount = settings.backgroundDimAmount,
                courseColorOverrides = coloredState.overrides,
                semesters = semesters,
                viewedSemester = viewedSemester,
                isHistoricalSemester = isHistorical,
                hasAuthoritativeCalendar = hasAuthoritativeCalendar,
                isInitialized = true
            )
        }

        uiState = combine(
            scheduleState,
            isRefreshing,
            message
        ) { state, refreshing, currentMessage ->
            state.copy(
                isRefreshing = refreshing,
                message = currentMessage
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = ScheduleUiState(
                week = initialWeek,
                isRefreshing = isRefreshing.value,
                message = message.value
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

    fun setShowGridLines(showGridLines: Boolean) {
        viewModelScope.launch { settingsStore.setShowGridLines(showGridLines) }
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
            // 学期切换也属于当前课表数据源变化，按该学期完整课程恢复应展示的区域。
            settingsStore.setAutomaticScheduleDisplay(semester.campus, repository.courses.first())
            settingsStore.setCurrentWeekNumber(week)
        }
    }

    fun returnToCurrentSemester() {
        viewModelScope.launch {
            repository.resetViewedSemesterToCurrent()
            val current = repository.currentSemester.first()
            settingsStore.setAutomaticScheduleDisplay(
                current?.campus ?: CampusType.GUILIN,
                repository.courses.first()
            )
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

    fun setCustomBackground(uri: String, crop: NormalizedCropRect) {
        viewModelScope.launch { settingsStore.setCustomBackground(uri, crop) }
    }

    fun setBackgroundDimAmount(value: Float) {
        viewModelScope.launch { settingsStore.setBackgroundDimAmount(value) }
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

    // ---- 手动隐藏卡片 ----

    /**
     * 隐藏一张卡片。
     *
     * 三档都落成同一种记录，只是键的精度不同：整门课只看课程名，这个课次带 (星期, 起止节)，
     * 本周这次再带上周次。写入前重新读一次最新列表，避免连续快速删除时基于旧列表互相覆盖
     * （与 [HolidayAdjustmentsViewModel.addRule] 同一套做法）。
     */
    fun hideCard(block: CourseBlock, scope: HiddenCardScope) {
        val state = uiState.value
        if (state.isHistoricalSemester) return
        val semesterId = state.semesters.firstOrNull { it.isCurrent }?.id ?: return
        val rule = HiddenCourseRule(
            scope = scope,
            courseKey = hiddenCourseKey(block.course.id, block.course.title),
            dayOfWeek = block.occurrence.dayOfWeek,
            startSection = block.occurrence.startSection,
            endSection = block.occurrence.endSection,
            week = if (scope == HiddenCardScope.WEEK) state.week.number else 0
        )
        viewModelScope.launch {
            val latest = settingsStore.hiddenCourseRules.first()[semesterId].orEmpty()
            if (latest.any { it.id == rule.id }) return@launch
            settingsStore.setHiddenCourseRules(semesterId, latest + rule)
            _hiddenUndo.value = HiddenUndo(
                id = ++hiddenUndoSequence,
                semesterId = semesterId,
                previousRules = latest
            )
        }
    }

    /** 撤销上一次隐藏：把该学期的规则整份还原成删除前的样子。 */
    fun undoHideCards() {
        val undo = _hiddenUndo.value ?: return
        _hiddenUndo.value = null
        viewModelScope.launch {
            settingsStore.setHiddenCourseRules(undo.semesterId, undo.previousRules)
        }
    }

    fun clearHiddenUndo() {
        _hiddenUndo.value = null
    }

    // ---- 刷新确认 ----

    /**
     * 刷新入口。
     *
     * 只有「当前学期确实还藏着卡片」时才先问一句；否则原样走 [refreshSchedule]，
     * 刷新流程与之前完全一致，不用为没用过删除功能的人平白加一步。
     */
    fun requestRefresh() {
        val state = uiState.value
        if (state.isHistoricalSemester || state.hiddenCardCount <= 0) {
            refreshSchedule()
            return
        }
        _refreshConfirm.value = RefreshConfirmState(count = state.hiddenCardCount)
    }

    /**
     * 确认刷新。`keepHidden = false` 表示这次刷新要把隐藏记录一并清掉（等于全部恢复）：
     * 先落库再刷新，隐藏的卡片会立刻回到课表上。
     */
    fun confirmRefresh(keepHidden: Boolean) {
        _refreshConfirm.value = null
        val semesterId = uiState.value.semesters.firstOrNull { it.isCurrent }?.id.orEmpty()
        if (!keepHidden && semesterId.isNotBlank()) {
            viewModelScope.launch { settingsStore.clearHiddenCourseRules(semesterId) }
        }
        refreshSchedule()
    }

    fun dismissRefreshConfirm() {
        _refreshConfirm.value = null
    }

    fun setClassPeriods(profile: ClassPeriodProfile, periods: List<ClassPeriod>) {
        viewModelScope.launch {
            settingsStore.setClassPeriods(profile, periods)
        }
    }

    fun resetClassPeriods(profile: ClassPeriodProfile) {
        viewModelScope.launch {
            settingsStore.resetClassPeriods(profile)
        }
    }

    fun setGuilinSubCampus(subCampus: String) {
        viewModelScope.launch {
            settingsStore.setGuilinSubCampus(subCampus)
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
        if (!refreshGuard.tryStart()) return
        isRefreshing.value = true
        // 研究生走完全独立的取数链路：上面那套「个人课表页 + 周次落地页 + 校历探测」
        // 对它一个都不适用，硬套只会请求到无关页面并报出误导性的错误。
        if (targetSemester.campus == CampusType.GRADUATE) {
            viewModelScope.launch { refreshGraduateSchedule(targetSemester) }
            return
        }
        viewModelScope.launch {
            message.value = "正在刷新课表..."
            // 用户主动刷新时顺带补齐节假日数据。与教务导入互不依赖：
            // 即使下面登录 / 抓取失败，节假日也已经更新过了。
            refreshHolidayYears()
            // 调休补课对照表同属「用户主动刷新时顺带补齐」的旁路数据，两者互不依赖。
            fetchMakeupTable()
            try {
                // 除了门数，还要把完整旧课表留下来算差异明细（对齐小程序的刷新反馈）。
                val oldCourses = repository.courses.first()
                refreshBaseCourses = oldCourses
                val oldCourseCount = oldCourses.countDistinctCourseTitles()
                val existingCookie = sessionStore.academicCookie.first()
                var refreshCookie = existingCookie
                var importResult: Result<AcademicSemesterImportPayload>? = null
                if (shouldUseExistingAcademicCookie(existingCookie)) {
                    importResult = importExactSemester(existingCookie, targetSemester)
                    if (importResult.isSuccess) {
                        saveExactSemester(
                            targetSemester,
                            importResult.getOrThrow(),
                            oldCourseCount,
                            existingCookie
                        )
                        return@launch
                    }
                    if (!isAuthenticationFailure(importResult.exceptionOrNull())) {
                        message.value = refreshFailureMessage(importResult.exceptionOrNull(), targetSemester)
                        return@launch
                    }
                }

                // 会话失效：原地续期——南宁/研究生弹验证码，桂林静默换票。
                // 用户在弹窗里取消（或续期失败）就**保留已有缓存**，只提示、不重取。
                if (!renewal.ensureFreshSession()) {
                    message.value = "会话已过期，已取消登录"
                    return@launch
                }
                refreshCookie = sessionStore.academicCookie.first()
                importResult = importExactSemester(refreshCookie, targetSemester)

                val payload = importResult?.getOrNull()
                if (payload == null) {
                    val failure = importResult?.exceptionOrNull()
                    message.value = refreshFailureMessage(failure, targetSemester)
                    return@launch
                }
                saveExactSemester(targetSemester, payload, oldCourseCount, refreshCookie)
            } catch (e: Exception) {
                message.value = refreshFailureMessage(e, targetSemester)
            } finally {
                isRefreshing.value = false
                refreshGuard.finish()
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
            studentIdFallback = studentId
        )
    }

    private suspend fun saveExactSemester(
        targetSemester: AcademicSemester,
        payload: AcademicSemesterImportPayload,
        oldCourseCount: Int,
        cookie: String
    ) {
        require(payload.courses.isNotEmpty()) {
            "${targetSemester.displayName}未获取到课程，已保留现有缓存"
        }
        val campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }
        // 普通刷新也读取门户当前校历；正式切换学期后，它会覆盖提前晋升阶段的推导或估算日期。
        // 若门户仍停留在上一学期，统一解析器会拒绝跨学期日期，并整对回退到周次数据。
        val directCalendar = runCatching {
            ApiProbeService.extractAcademicCalendar(
                apiProbeService.probeScheduleEndpoints(
                    cookie = cookie,
                    baseUrl = campusBaseUrl
                )
            )
        }.getOrNull()
        val resolvedCalendar = AcademicSemesterCalendarResolver.resolve(
            semester = targetSemester,
            today = LocalDate.now(),
            directCalendar = directCalendar,
            weeklyStartMonday = payload.semesterStartMonday,
            portalMaxWeek = payload.portalMaxWeek
        )
        repository.replaceSemesterSchedule(
            semester = targetSemester,
            courses = payload.courses,
            adjustments = payload.adjustments,
            classPeriods = repository.currentClassPeriods.first(),
            semesterStartDate = resolvedCalendar.startMonday,
            semesterEndDate = resolvedCalendar.endDate,
            portalMaxWeek = payload.portalMaxWeek
        )
        // 重算必须排在落库之后：让位判定读的就是刚落库的 semester_adjustments。
        autoDayCopyCoordinator.recompose(targetSemester.id)
        settingsStore.setSemesterStartMonday(resolvedCalendar.startMonday)
        settingsStore.setSemesterEndDate(resolvedCalendar.endDate)
        // 刷新校历只校正有效范围，不把用户正在浏览的周次重置为今天所在周。
        settingsStore.setCurrentWeekNumber(
            selectedWeekAfterCalendarRefresh(
                selectedWeek = settingsStore.currentWeekNumber.first(),
                semesterStartMonday = resolvedCalendar.startMonday,
                semesterEndDate = resolvedCalendar.endDate
            )
        )
        val newCourseCount = payload.courses.countDistinctCourseTitles()
        val completionMessage = if (newCourseCount != oldCourseCount) {
            "${targetSemester.displayName}课表已更新：$oldCourseCount → $newCourseCount 门课程"
        } else {
            "${targetSemester.displayName}课表未发生变化（$newCourseCount 门课程）"
        }
        // 有变化时改用明细卡片说明（与小程序同构），Snackbar 只留下异常行提示，
        // 免得同一件事在两处各说一遍。没变化时保持原来那条简短提示。
        val diff = buildScheduleRefreshDiff(
            refreshBaseCourses,
            payload.courses,
            // 节次文案要按来源映射；南宁与研究生没有中午节次，直排内部号。
            hasNoonSections = capabilitiesFor(targetSemester.campus).hasNoonSections
        )
        refreshBaseCourses = emptyList()
        if (diff.hasChanges) {
            _refreshDiff.value = diff
            message.value = if (payload.skippedRowCount > 0) {
                "已跳过 ${payload.skippedRowCount} 条异常课程记录"
            } else {
                ""
            }
        } else {
            message.value = if (payload.skippedRowCount > 0) {
                "$completionMessage；已跳过 ${payload.skippedRowCount} 条异常课程记录"
            } else {
                completionMessage
            }
        }
    }

    fun clearRefreshDiff() {
        _refreshDiff.value = null
    }

    private fun isAuthenticationFailure(error: Throwable?): Boolean =
        error?.message.orEmpty().contains("登录状态已失效")

    /**
     * 研究生学期课表取数。
     *
     * 单独抽出来是因为刷新链路要**调用它两次**（续期前后各一次），
     * 内联会让那段控制流看不出「同一件事做了两遍」。
     */
    private suspend fun downloadGraduateSemester(
        cookie: String,
        semester: AcademicSemester,
        studentId: String
    ) = GraduateImportService.downloadSemester(
        cookie = cookie,
        semester = semester,
        studentId = studentId
    )

    /**
     * 研究生课表刷新。
     *
     * 与本科/南宁的取数完全不同：没有个人课表页、没有周次落地页、没有校历探测接口，
     * 只有一个 `GetTimeTableByStudent/{Year}/{Term}/{XH}` 网关调用。因此这里不共用
     * [importExactSemester]，而是走研究生的独立客户端；学年/学期参数直接取学期自身保存的
     * 门户值，学号取会话里记住的那个。
     *
     * **不做静默登录**：研究生门户还需要图形验证码，无法在后台补登录——会话失效时走 [renewal]
     * 原地弹验证码并重放这一次刷新，而不是在这里拿密码硬试。
     */
    private suspend fun refreshGraduateSchedule(targetSemester: AcademicSemester) {
        try {
            var studentId = sessionStore.authenticatedStudentNumber.first()
            var cookie = sessionStore.academicCookie.first()

            // cookie 缺失、学号缺失，或取数报「登录已失效」，都属于同一种情况：门户会话没了。
            // 研究生门户要图形验证码，无法静默补登录——原地续期（弹验证码）后重试一次。
            // 只对**会话失效**续期：网络抖动之类的失败照旧直接提示，不该为它弹一个验证码框。
            var attempt = if (cookie.isBlank() || studentId.isBlank()) {
                Result.failure(GraduateSessionExpiredException("研究生登录已失效，请重新登录"))
            } else {
                downloadGraduateSemester(cookie, targetSemester, studentId)
            }
            val sessionLooksDead = cookie.isBlank() || studentId.isBlank() ||
                attempt.exceptionOrNull() is GraduateSessionExpiredException
            if (attempt.isFailure && sessionLooksDead) {
                if (!renewal.ensureFreshSession()) {
                    message.value = "会话已过期，已取消登录"
                    return
                }
                // 学号也要重读：它可能是空的（那正是触发续期的原因之一），而本次登录
                // 已经把门户认到的学号写回会话了。只重读 cookie 的话，重放会拿着空学号
                // 再失败一次，用户白输一遍验证码。
                cookie = sessionStore.academicCookie.first()
                studentId = sessionStore.authenticatedStudentNumber.first()
                attempt = downloadGraduateSemester(cookie, targetSemester, studentId)
            }
            val payload = attempt.getOrElse { error ->
                message.value = refreshFailureMessage(error, targetSemester)
                return
            }
            require(payload.courses.isNotEmpty()) {
                "${targetSemester.displayName}未获取到课程，已保留现有缓存"
            }
            repository.replaceSemesterSchedule(
                semester = targetSemester,
                courses = payload.courses,
                // 研究生的调课不并入课表；刷新也不改变这条口径。
                adjustments = emptyList(),
                classPeriods = repository.currentClassPeriods.first(),
                semesterStartDate = targetSemester.semesterStartDate,
                semesterEndDate = targetSemester.semesterEndDate,
                portalMaxWeek = targetSemester.portalMaxWeek
            )
            // 研究生没有教务调课记录，但仍要重算：对照表与补班日推导对研究生来源同样适用
            // （门户不下发调课，正是自动调课最有价值的场景）。
            autoDayCopyCoordinator.recompose(targetSemester.id)
            message.value = if (payload.unparsedWeekTextCount > 0) {
                "课表已刷新；${payload.unparsedWeekTextCount} 条上课时间的周次无法识别"
            } else {
                "课表已刷新"
            }
        } catch (e: Exception) {
            message.value = refreshFailureMessage(e, targetSemester)
        } finally {
            isRefreshing.value = false
            refreshGuard.finish()
        }
    }

    private fun refreshFailureMessage(error: Throwable?, semester: AcademicSemester): String {
        val detail = error?.message.orEmpty().ifBlank { "未获取到课表数据" }
        return "${semester.displayName}刷新失败：$detail"
    }

    /**
     * 补齐当前学期跨越年份的节假日数据。
     *
     * **只在用户点「刷新」时调用**：没有定时、也没有启动时请求——用户没主动更新数据时，
     * App 一次都不该打 timor 接口。用户刷新后即使 2027 仍未公布，也只是这次白跑一趟，
     * 下次刷新会再问；缓存里的坏数据由读闸门剔除，不会让「已缓存」的假象挡住重试。
     */
    /**
     * 拉取调休补课对照表；返回是否拿到了能用的新表。
     *
     * 两条触发路径共用：**冷启动同步**（见 `init`）与**用户主动刷新课表**（见 `refreshSchedule`）。
     * 进入页面仍然只读缓存、不联网；失败沿用上一次同步成功的表（写闸门在 `ScheduleSettingsStore`）。
     * 用 [makeupTableFetchGuard] 串行化：冷启动那次还没结束时用户又下拉刷新，不会重复打接口。
     */
    private suspend fun fetchMakeupTable(): Boolean {
        if (!makeupTableFetchGuard.tryStart()) return false
        return try {
            refreshMakeupTable(makeupTableClient, settingsStore::setHolidayMakeupTableJson)
        } finally {
            makeupTableFetchGuard.finish()
        }
    }

    private suspend fun refreshHolidayYears() {
        if (!holidayFetchGuard.tryStart()) return
        try {
            val years = settingsStore.semesterStartMonday.first().year..
                settingsStore.semesterEndDate.first().year
            if (years.isEmpty()) return
            refreshMissingHolidayYears(
                client = timorHolidayClient,
                years = years,
                // holidayCacheByYear 已过读闸门：被污染的年份在这里就是「缺失」。
                cachedYears = settingsStore.holidayCacheByYear.first(),
                saveYear = settingsStore::setHolidayYearCache
            )
        } finally {
            holidayFetchGuard.finish()
        }
    }

    private fun holidayDatesFlow(): Flow<Set<LocalDate>> =
        settingsStore.holidayCacheByYear
            .map { cache ->
                cache.entries.flatMapTo(mutableSetOf()) { (year, json) ->
                    TimorHolidayCalendarParser.parse(json, year)?.holidayDates.orEmpty()
                }
            }
            .distinctUntilChanged()

    fun clearMessage() {
        message.value = ""
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
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val semesterImportService: AcademicSemesterImportService,
    private val apiProbeService: ApiProbeService,
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator,
    private val timorHolidayClient: TimorHolidayClient = TimorHolidayClient(),
    private val makeupTableClient: MakeupTableClient = MakeupTableClient()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScheduleViewModel(
            repository,
            settingsStore,
            sessionStore,
            renewal,
            semesterImportService,
            apiProbeService,
            autoDayCopyCoordinator,
            timorHolidayClient,
            makeupTableClient
        ) as T
    }
}

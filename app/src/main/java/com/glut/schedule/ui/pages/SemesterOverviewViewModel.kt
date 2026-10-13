package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.HolidayInfo
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicMaxWeekForSemester
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.countDistinctCourseTitles
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.data.settings.capabilitiesFor
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readStringLimited
import com.glut.schedule.service.holiday.AutoDayCopyCoordinator
import com.glut.schedule.service.holiday.MakeupTableClient
import com.glut.schedule.service.holiday.TimorHolidayCalendarParser
import com.glut.schedule.service.holiday.TimorHolidayClient
import com.glut.schedule.service.holiday.refreshMakeupTable
import com.glut.schedule.service.holiday.refreshMissingHolidayYears
import com.glut.schedule.ui.SingleFlightGuard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

data class SemesterOverviewUiState(
    val semesterLabel: String = "",
    val semesterStartDate: LocalDate = LocalDate.now(),
    val semesterEndDate: LocalDate = LocalDate.now(),
    val currentWeek: Int = 1,
    val totalWeeks: Int = MAX_ACADEMIC_WEEK,
    val progressPercent: Float = 0f,
    val elapsedDays: Long = 0,
    val remainingDays: Long = 0,
    val holidays: List<HolidayDisplay> = emptyList(),
    val adjustmentsByWeek: Map<Int, List<SemesterAdjustment>> = emptyMap(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val hasNoon: Boolean = true,
    val isArchiveMode: Boolean = false,
    val courseCount: Int = 0,
    val calendarAvailable: Boolean = true
)

data class HolidayDisplay(
    val name: String,
    val startDate: String,
    val endDate: String,
    val daysOff: Int,
    val isPast: Boolean,
    val isOngoing: Boolean,
    val isNext: Boolean,
    val daysUntil: Long
)

data class SemesterProgress(
    val elapsedDays: Long,
    val remainingDays: Long,
    val percent: Float
)

/**
 * 进度必须基于正在查看的学期日期计算，不能回退到当前学期的设置日期。
 */
fun calculateSemesterProgress(
    today: LocalDate,
    semesterStartDate: LocalDate,
    semesterEndDate: LocalDate
): SemesterProgress {
    val totalDays = (ChronoUnit.DAYS.between(semesterStartDate, semesterEndDate) + 1L)
        .coerceAtLeast(1L)
    val elapsedDays = ChronoUnit.DAYS.between(semesterStartDate, today)
        .coerceIn(0L, totalDays)
    return SemesterProgress(
        elapsedDays = elapsedDays,
        remainingDays = (totalDays - elapsedDays).coerceAtLeast(0L),
        percent = elapsedDays.toFloat() / totalDays.toFloat()
    )
}

private data class SemesterBase(
    val startMonday: LocalDate,
    val endDate: LocalDate,
    val adjustments: List<SemesterAdjustment>,
    val viewedSemester: AcademicSemester?,
    val courses: List<ScheduleCourse>
)

class SemesterOverviewViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    private val scheduleParser: AcademicScheduleParser,
    private val loginService: AcademicLoginService,
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator,
    private val timorHolidayClient: TimorHolidayClient = TimorHolidayClient(),
    private val makeupTableClient: MakeupTableClient = MakeupTableClient()
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val refreshGuard = SingleFlightGuard()
    private val _message = MutableStateFlow("")
    private val _holidays = MutableStateFlow<List<HolidayDisplay>>(emptyList())

    init {
        // 进入页面只读缓存，不联网——联网只在用户点「刷新」时发生。
        viewModelScope.launch { loadHolidays(fetchMissing = false) }
    }

    val uiState: StateFlow<SemesterOverviewUiState> = combine(
        combine(
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate,
            repository.semesterAdjustments,
            repository.viewedSemester,
            repository.courses
        ) { startMonday, endDate, adjustments, viewedSemester, courses ->
            SemesterBase(startMonday, endDate, adjustments, viewedSemester, courses)
        },
        _holidays,
        _isRefreshing,
        _message,
        // 这里原本消费的是会话 baseUrl（用来推断「是不是南宁」）。加入研究生后，
        // 来源判断改为直接读设置里的 CampusType，baseUrl 在这个 combine 里已无用。
        settingsStore.campusType
    ) { base, holidays, isRefreshing, message, currentCampusType ->
        val today = LocalDate.now()
        val isArchiveMode = base.viewedSemester?.isCurrent == false
        val startDate = base.viewedSemester?.semesterStartDate ?: base.startMonday
        val endDate = base.viewedSemester?.semesterEndDate ?: base.endDate
        val calendarAvailable = !isArchiveMode ||
            (base.viewedSemester?.semesterStartDate != null && base.viewedSemester.semesterEndDate != null)
        val maxWeek = academicMaxWeekForSemester(
            isCurrentSemester = !isArchiveMode,
            portalMaxWeek = base.viewedSemester?.portalMaxWeek,
            courses = base.courses,
            semesterStartMonday = startDate,
            semesterEndDate = endDate
        )
        val currentWeek = if (isArchiveMode) 0 else academicWeekForDate(today, startDate, maxWeek)
        val progress = calculateSemesterProgress(today, startDate, endDate)

        val year = startDate.year
        val month = startDate.monthValue
        val label = base.viewedSemester?.displayName ?: when {
            month in 2..7 -> "${year - 1}-${year} 春季学期"
            else -> "${year}-${year + 1} 秋季学期"
        }

        // Append semester-end vacation (暑假/寒假) reactively — uses latest semesterEndDate from settings,
        // unlike the baked-in _holidays which is set once at init and becomes stale after教务 import.
        val vacationName = if (startDate.monthValue in 2..7) "暑假" else "寒假"
        val vacationDays = ChronoUnit.DAYS.between(today, endDate)
        val holidaysWithVacation = if (!isArchiveMode && vacationDays > 0) {
            val hasNext = holidays.none { it.isNext }
            holidays + HolidayDisplay(
                name = vacationName,
                startDate = endDate.toString(),
                endDate = endDate.toString(),
                daysOff = 0,
                isPast = false,
                isOngoing = false,
                isNext = hasNext,
                daysUntil = vacationDays
            )
        } else holidays

        SemesterOverviewUiState(
            semesterLabel = label,
            semesterStartDate = startDate,
            semesterEndDate = endDate,
            currentWeek = currentWeek,
            totalWeeks = maxWeek,
            progressPercent = progress.percent,
            elapsedDays = progress.elapsedDays,
            remainingDays = progress.remainingDays,
            holidays = if (isArchiveMode) emptyList() else holidaysWithVacation,
            adjustmentsByWeek = base.adjustments.groupBy { adj ->
                if (adj.originalWeek > 0) adj.originalWeek else adj.makeupWeek
            },
            isRefreshing = isRefreshing,
            message = message,
            // 中午节次是「来源」属性，不是「是不是南宁」：研究生同样没有中午节次，
            // 沿用旧的二选一判断会让概览页把研究生的第 5、6 节当成午1/午2 并整体错位 2 节。
            hasNoon = base.viewedSemester?.campus?.let { capabilitiesFor(it).hasNoonSections }
                ?: capabilitiesFor(currentCampusType).hasNoonSections,
            isArchiveMode = isArchiveMode,
            courseCount = base.courses.countDistinctCourseTitles(),
            calendarAvailable = calendarAvailable
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SemesterOverviewUiState()
    )

    fun refresh() {
        if (uiState.value.isArchiveMode) {
            _message.value = "历史学期归档为只读，无需刷新"
            return
        }
        if (!refreshGuard.tryStart()) return
        _isRefreshing.value = true
        viewModelScope.launch {
            _message.value = "正在刷新..."
            try {
                loadHolidays(fetchMissing = true)
                _message.value = ""
            } catch (e: Exception) {
                _message.value = "刷新失败: ${e.message}"
            } finally {
                _isRefreshing.value = false
                refreshGuard.finish()
                delay(4000)
                _message.value = ""
            }
        }
    }

    fun refreshAdjustments() {
        if (uiState.value.isArchiveMode) {
            _message.value = "历史学期调课记录来自缓存，无法刷新"
            return
        }
        if (!refreshGuard.tryStart()) return
        val targetSemesterId = repository.viewedSemesterId.value
        _isRefreshing.value = true
        viewModelScope.launch {
            // 研究生没有可复用的调课接口（GetCheckedAdjustCourseList 样本为空、且不并入课表）。
            // 必须**早退**：下面那条链路会拿研究生 Cookie 打 jw.glut.edu.cn，
            // 还会在 silentLogin 成功后把 campusBaseUrl 覆盖成本科地址，反过来破坏研究生会话。
            if (settingsStore.campusType.first() == CampusType.GRADUATE) {
                _message.value = "研究生调课暂不支持刷新，请在研究生门户核对"
                _isRefreshing.value = false
                refreshGuard.finish()
                delay(4000)
                _message.value = ""
                return@launch
            }
            _message.value = "正在获取调课信息..."
            try {
                val campusBaseUrl = sessionStore.campusBaseUrl.first()
                    .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

                // Try existing cookie first
                var cookie = sessionStore.academicCookie.first()
                var html = if (cookie.isNotBlank()) {
                    fetchTimetableHtml(cookie, campusBaseUrl)
                } else ""

                // If cookie expired or missing, try silent login
                if (html.isBlank()) {
                    _message.value = "会话已过期，正在使用已保存的账号自动登录..."
                    when (val loginResult = loginService.silentLogin()) {
                        is AcademicLoginResult.Success -> {
                            cookie = sessionStore.academicCookie.first()
                            html = fetchTimetableHtml(cookie, campusBaseUrl)
                        }
                        AcademicLoginResult.MissingCredentials -> {
                            _message.value = "请先在导入课表页面登录教务系统以保存账号密码"
                            delay(4000)
                            _message.value = ""
                            return@launch
                        }
                        AcademicLoginResult.InvalidCredentials -> {
                            _message.value = "教务账号或密码错误，请重新登录"
                            delay(4000)
                            _message.value = ""
                            return@launch
                        }
                        is AcademicLoginResult.NetworkError -> {
                            _message.value = "自动登录失败: ${loginResult.message}"
                            delay(4000)
                            _message.value = ""
                            return@launch
                        }
                        AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                            val isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL
                            _message.value = if (isNanning)
                                "南宁登录需验证码，请到导入课表页面重新登录"
                            else
                                "教务需要手动验证，请到导入课表页面重新登录"
                            delay(4000)
                            _message.value = ""
                            return@launch
                        }
                    }
                }

                if (html.isBlank()) {
                    _message.value = "获取失败，请检查网络后重试"
                    delay(4000)
                    _message.value = ""
                    return@launch
                }
                val adjustments = scheduleParser.parseAdjustments(html)
                // 使用请求开始时捕获的学期，防止网络等待期间切换学期导致写错缓存。
                repository.replaceSemesterAdjustments(targetSemesterId, adjustments)
                // 调课记录刚变，自动调课的「让位教务」判定必须跟着重算：否则教务新下发的
                // 补课卡会与自动副本同时显示同一节课。非当前学期时 recompose 是 no-op。
                repository.currentSemester.first()?.id?.let { autoDayCopyCoordinator.recompose(it) }
                _message.value = if (adjustments.isNotEmpty()) "已更新 ${adjustments.size} 条调课记录"
                else "当前无调课记录"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh adjustments", e)
                _message.value = "获取失败，请检查网络后重试"
            } finally {
                _isRefreshing.value = false
                refreshGuard.finish()
                delay(4000)
                _message.value = ""
            }
        }
    }

    private suspend fun fetchTimetableHtml(cookie: String, fallbackBaseUrl: String): String = withContext(Dispatchers.IO) {
        try {
            // 优先根据当前查看学期动态构造 URL，避免使用 DataStore 中的旧 URL
            // （旧 URL 可能包含已切换学期的 yearid/termid 参数，导致跨学期调课数据污染）。
            val viewedSemester = repository.viewedSemester.first()
            val studentId = sessionStore.authenticatedStudentNumber.first().ifBlank { "" }
            val url = if (viewedSemester != null && studentId.isNotBlank()) {
                com.glut.schedule.service.academic.AcademicSemesterRequestBuilder.timetableUrl(
                    fallbackBaseUrl, studentId, viewedSemester
                )
            } else {
                "$fallbackBaseUrl/academic/manager/coursearrange/showTimetable.do?timetableType=STUDENT&sectionType=BASE"
            }
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cookie", cookie)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/json,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Referer", "$fallbackBaseUrl/academic/preGotoAffairFrame.do")
                .get()
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.readStringLimited(MAX_HTML_RESPONSE_BYTES).orEmpty()
                } else ""
            }
            Log.d(TAG, "Fetched timetable HTML: ${body.length} chars, adjustments found: ${body.contains("调课") || body.contains("补课")}")
            body
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch timetable for adjustments", e)
            ""
        }
    }

    /**
     * 载入节假日列表。
     *
     * [fetchMissing] 为 true 时才联网——只在用户点「刷新」时传 true；进入页面时只读缓存，
     * 不做任何自动请求。
     */
    private suspend fun loadHolidays(fetchMissing: Boolean) {
        val today = LocalDate.now()
        val semesterStart = settingsStore.semesterStartMonday.first()
        val semesterEnd = settingsStore.semesterEndDate.first()

        // 学期会跨越自然年（秋季学期到次年 1 月），必须逐年取；只取「今年」会漏掉元旦。
        // 缓存按数据所属年份存；holidayCacheByYear 已过读闸门，被污染 / 未公布的年份
        // 在这里就是「缺失」，用户刷新时会重新拉取。
        val years = semesterStart.year..semesterEnd.year
        if (fetchMissing) {
            refreshMissingHolidayYears(
                client = timorHolidayClient,
                years = years,
                cachedYears = settingsStore.holidayCacheByYear.first(),
                saveYear = settingsStore::setHolidayYearCache
            )
            // 调休补课对照表同属「用户主动刷新时才取」的旁路数据。fetchMissing = false 的
            // 进入页面路径一行都不会走到这里，继续满足「进入页面只读缓存」。
            refreshMakeupTable(makeupTableClient, settingsStore::setHolidayMakeupTableJson)
            // 两者都可能变了，刷新完重算一次自动调课（纯本地计算，不联网）。
            repository.currentSemester.first()?.id?.let { autoDayCopyCoordinator.recompose(it) }
        }
        val cached = settingsStore.holidayCacheByYear.first()
        val allHolidays = mutableListOf<HolidayInfo>()
        for (year in years) {
            Log.d(TAG, "Using cached holidays (year $year)")
            allHolidays += TimorHolidayCalendarParser.parse(
                cached[year].orEmpty(),
                year
            )?.holidays.orEmpty()
        }

        val displays = allHolidays
            .filter { it.endDate >= semesterStart.toString() && it.startDate <= semesterEnd.toString() }
            .map { h ->
                val start = LocalDate.parse(h.startDate)
                val end = LocalDate.parse(h.endDate)
                val isPast = end.isBefore(today)
                val isOngoing = !isPast && !start.isAfter(today) && !end.isBefore(today)
                val daysUntil = if (!isPast && !isOngoing) ChronoUnit.DAYS.between(today, start) else 0
                HolidayDisplay(
                    name = h.name,
                    startDate = h.startDate,
                    endDate = h.endDate,
                    daysOff = h.daysOff,
                    isPast = isPast,
                    isOngoing = isOngoing,
                    isNext = false,
                    daysUntil = daysUntil
                )
            }.toMutableList()

        val nextIdx = displays.indexOfFirst { !it.isPast && !it.isOngoing }
        if (nextIdx >= 0) {
            displays[nextIdx] = displays[nextIdx].copy(isNext = true)
        }

        _holidays.value = displays
    }

    companion object {
        private const val TAG = "SemesterOverviewVM"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}

class SemesterOverviewViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    private val scheduleParser: AcademicScheduleParser,
    private val loginService: AcademicLoginService,
    private val autoDayCopyCoordinator: AutoDayCopyCoordinator,
    private val timorHolidayClient: TimorHolidayClient = TimorHolidayClient(),
    private val makeupTableClient: MakeupTableClient = MakeupTableClient()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SemesterOverviewViewModel(
            repository,
            settingsStore,
            sessionStore,
            scheduleParser,
            loginService,
            autoDayCopyCoordinator,
            timorHolidayClient,
            makeupTableClient
        ) as T
    }
}

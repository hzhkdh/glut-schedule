package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.HolidayInfo
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.AcademicScheduleParser
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
import org.json.JSONObject
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
    val message: String = ""
)

data class HolidayDisplay(
    val name: String,
    val startDate: String,
    val endDate: String,
    val daysOff: Int,
    val isPast: Boolean,
    val isNext: Boolean,
    val daysUntil: Long
)

private data class SemesterBase(
    val startMonday: LocalDate,
    val endDate: LocalDate,
    val adjustments: List<SemesterAdjustment>
)

class SemesterOverviewViewModel(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    private val scheduleParser: AcademicScheduleParser,
    private val loginService: AcademicLoginService
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _holidays = MutableStateFlow<List<HolidayDisplay>>(emptyList())

    init {
        viewModelScope.launch { loadHolidays() }
    }

    val uiState: StateFlow<SemesterOverviewUiState> = combine(
        combine(
            settingsStore.semesterStartMonday,
            settingsStore.semesterEndDate,
            repository.semesterAdjustments
        ) { startMonday, endDate, adjustments ->
            SemesterBase(startMonday, endDate, adjustments)
        },
        _holidays,
        _isRefreshing,
        _message
    ) { base, holidays, isRefreshing, message ->
        val today = LocalDate.now()
        val maxWeek = academicMaxWeekForCalendar(base.startMonday, base.endDate)
        val currentWeek = academicWeekForDate(today, base.startMonday, maxWeek)
        val totalDays = ChronoUnit.DAYS.between(base.startMonday, base.endDate) + 1
        val elapsed = ChronoUnit.DAYS.between(base.startMonday, today).coerceIn(0, totalDays)
        val remaining = (totalDays - elapsed).coerceAtLeast(0)

        val year = base.startMonday.year
        val month = base.startMonday.monthValue
        val label = when {
            month in 2..7 -> "${year - 1}-${year} 春季学期"
            else -> "${year}-${year + 1} 秋季学期"
        }

        // Append semester-end vacation (暑假/寒假) reactively — uses latest semesterEndDate from settings,
        // unlike the baked-in _holidays which is set once at init and becomes stale after教务 import.
        val vacationName = if (base.startMonday.monthValue in 2..7) "暑假" else "寒假"
        val vacationDays = ChronoUnit.DAYS.between(today, base.endDate)
        val holidaysWithVacation = if (vacationDays > 0) {
            val hasNext = holidays.none { it.isNext }
            holidays + HolidayDisplay(
                name = vacationName,
                startDate = base.endDate.toString(),
                endDate = base.endDate.toString(),
                daysOff = 0,
                isPast = false,
                isNext = hasNext,
                daysUntil = vacationDays
            )
        } else holidays

        SemesterOverviewUiState(
            semesterLabel = label,
            semesterStartDate = base.startMonday,
            semesterEndDate = base.endDate,
            currentWeek = currentWeek,
            totalWeeks = maxWeek,
            progressPercent = if (totalDays > 0) (elapsed.toFloat() / totalDays.toFloat()) else 0f,
            elapsedDays = elapsed,
            remainingDays = remaining,
            holidays = holidaysWithVacation,
            adjustmentsByWeek = base.adjustments.groupBy { adj ->
                if (adj.makeupWeek > 0) adj.makeupWeek else adj.originalWeek
            },
            isRefreshing = isRefreshing,
            message = message
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SemesterOverviewUiState()
    )

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _message.value = "正在刷新..."
            try {
                loadHolidays()
                _message.value = ""
            } catch (e: Exception) {
                _message.value = "刷新失败: ${e.message}"
            } finally {
                _isRefreshing.value = false
                delay(4000)
                _message.value = ""
            }
        }
    }

    fun refreshAdjustments() {
        viewModelScope.launch {
            _isRefreshing.value = true
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
                repository.replaceSemesterAdjustments(adjustments)
                _message.value = if (adjustments.isNotEmpty()) "已更新 ${adjustments.size} 条调课记录"
                else "当前无调课记录"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to refresh adjustments", e)
                _message.value = "获取失败，请检查网络后重试"
            } finally {
                _isRefreshing.value = false
                delay(4000)
                _message.value = ""
            }
        }
    }

    private suspend fun fetchTimetableHtml(cookie: String, fallbackBaseUrl: String): String = withContext(Dispatchers.IO) {
        try {
            val storedUrl = sessionStore.timetableUrl.first()
            val url = if (storedUrl.isNotBlank()) storedUrl
            else "$fallbackBaseUrl/academic/manager/coursearrange/showTimetable.do?timetableType=STUDENT&sectionType=BASE"
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
                if (response.isSuccessful) response.body?.string().orEmpty() else ""
            }
            Log.d(TAG, "Fetched timetable HTML: ${body.length} chars, adjustments found: ${body.contains("调课") || body.contains("补课")}")
            body
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch timetable for adjustments", e)
            ""
        }
    }

    private suspend fun loadHolidays() {
        val today = LocalDate.now()
        val semesterStart = settingsStore.semesterStartMonday.first()
        val semesterEnd = settingsStore.semesterEndDate.first()

        // Use cache if fetched today, otherwise call API once and cache result
        val year = today.year
        val (cachedJson, cacheDate) = settingsStore.holidaysCache.first()
        val cacheYear = cacheDate.take(4).toIntOrNull() ?: 0
        val allHolidays = if (cacheYear == year && cachedJson.isNotBlank()) {
            Log.d(TAG, "Using cached holidays (year $year)")
            parseTimorHolidayJson(cachedJson, year)
        } else {
            Log.d(TAG, "Fetching holidays from API (cache year: $cacheYear, current: $year)")
            val (raw, parsed) = fetchHolidays(year)
            if (raw.isNotBlank() && parsed.isNotEmpty()) {
                settingsStore.setHolidaysCache(raw)
            }
            parsed
        }

        val displays = allHolidays
            .filter { it.endDate >= semesterStart.toString() && it.startDate <= semesterEnd.toString() }
            .map { h ->
                val start = LocalDate.parse(h.startDate)
                val isPast = start.isBefore(today)
                val daysUntil = if (!isPast) ChronoUnit.DAYS.between(today, start) else 0
                HolidayDisplay(
                    name = h.name,
                    startDate = h.startDate,
                    endDate = h.endDate,
                    daysOff = h.daysOff,
                    isPast = isPast,
                    isNext = false,
                    daysUntil = daysUntil
                )
            }.toMutableList()

        val nextIdx = displays.indexOfFirst { !it.isPast }
        if (nextIdx >= 0) {
            displays[nextIdx] = displays[nextIdx].copy(isNext = true)
        }

        _holidays.value = displays
    }

    private suspend fun fetchHolidays(year: Int): Pair<String, List<HolidayInfo>> = withContext(Dispatchers.IO) {
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://timor.tech/api/holiday/year/$year")
                .header("User-Agent", "GlutSchedule/1.0")
                .get()
                .build()
            val body = client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string().orEmpty() else ""
            }
            if (body.isBlank()) return@withContext "" to emptyList()
            body to parseTimorHolidayJson(body, year)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch holidays", e)
            "" to emptyList()
        }
    }

    private fun parseTimorHolidayJson(json: String, year: Int): List<HolidayInfo> {
        val raw = mutableListOf<Pair<String, String>>() // name -> date (MM-dd)
        try {
            val root = JSONObject(json)
            if (root.optInt("code") != 0) return emptyList()
            val holidayObj = root.optJSONObject("holiday") ?: return emptyList()
            val keys = holidayObj.keys()
            while (keys.hasNext()) {
                val dateKey = keys.next()
                val info = holidayObj.optJSONObject(dateKey) ?: continue
                if (!info.optBoolean("holiday", false)) continue
                var name = info.optString("name", "")
                if (name.isBlank()) continue
                if (name in springFestivalSubNames) name = "春节"
                raw.add(name to dateKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse timor holiday JSON", e)
            return emptyList()
        }

        val grouped = linkedMapOf<String, MutableList<String>>()
        for ((name, date) in raw) {
            grouped.getOrPut(name) { mutableListOf() }.add(date)
        }

        return grouped.map { (name, dates) ->
            dates.sort()
            val start = "$year-${dates.first()}"
            val end = "$year-${dates.last()}"
            HolidayInfo(name = name, startDate = start, endDate = end, daysOff = dates.size)
        }.sortedBy { it.startDate }
    }

    companion object {
        private const val TAG = "SemesterOverviewVM"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        private val springFestivalSubNames = setOf(
            "除夕", "初一", "初二", "初三", "初四", "初五", "初六", "初七",
            "春节前补班", "春节后补班"
        )
    }
}

class SemesterOverviewViewModelFactory(
    private val repository: ScheduleRepository,
    private val settingsStore: ScheduleSettingsStore,
    private val sessionStore: AcademicSessionStore,
    private val scheduleParser: AcademicScheduleParser,
    private val loginService: AcademicLoginService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SemesterOverviewViewModel(repository, settingsStore, sessionStore, scheduleParser, loginService) as T
    }
}

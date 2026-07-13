package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.StudyPlanParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit

data class StudyPlanUiState(
    val groupsWithCourses: List<StudyPlanGroupWithCourses> = emptyList(),
    val groups: List<StudyPlanGroup> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val hasCookie: Boolean = false,
    val cookieValue: String = "",
    val expandedGroupId: String? = null,
    val showLegend: Boolean = false
)

class StudyPlanViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _expandedGroupId = MutableStateFlow<String?>(null)
    private val _showLegend = MutableStateFlow(false)

    val uiState: StateFlow<StudyPlanUiState> = combine(
        combine(
            repository.studyPlanGroupsWithCourses,
            repository.studyPlanGroups,
            sessionStore.academicCookie
        ) { gwc, g, c -> Triple(gwc, g, c) },
        _isRefreshing,
        _message,
        _expandedGroupId,
        _showLegend
    ) { (gwc, g, c), refreshing, msg, expId, legend ->
        StudyPlanUiState(
            groupsWithCourses = gwc,
            groups = g,
            isRefreshing = refreshing,
            message = msg,
            hasCookie = c.isNotBlank(),
            cookieValue = c,
            expandedGroupId = expId,
            showLegend = legend
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StudyPlanUiState()
    )

    fun toggleExpanded(groupId: String) {
        _expandedGroupId.value = if (_expandedGroupId.value == groupId) null else groupId
    }

    fun toggleLegend() {
        _showLegend.value = !_showLegend.value
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _message.value = "正在获取培养计划..."
            try {
                val campusBaseUrl = sessionStore.campusBaseUrl.first()
                    .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

                // Try existing cookie first
                var cookie = sessionStore.academicCookie.first()
                var data = if (cookie.isNotBlank()) {
                    fetchStudyPlanData(cookie, campusBaseUrl)
                } else null

                // If cookie expired or missing, try silent login
                if (data == null || data.first.isEmpty()) {
                    _message.value = "会话已过期，正在使用已保存的账号自动登录..."
                    when (val loginResult = loginService.silentLogin()) {
                        is AcademicLoginResult.Success -> {
                            cookie = sessionStore.academicCookie.first()
                            data = fetchStudyPlanData(cookie, campusBaseUrl)
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

                val (groups, courses) = data!!
                if (groups.isNotEmpty()) {
                    repository.replaceStudyPlanData(groups, courses)
                    _message.value = "已获取 ${groups.size} 个课组，共 ${courses.size} 门课程"
                } else {
                    _message.value = "暂无培养计划数据"
                }
            } catch (e: Exception) {
                _message.value = "获取失败: ${e.message}"
                Log.e(TAG, "Study plan fetch failed", e)
            } finally {
                _isRefreshing.value = false
                delay(4000)
                _message.value = ""
            }
        }
    }

    internal suspend fun fetchStudyPlanData(
        cookie: String,
        campusBaseUrl: String
    ): Pair<List<StudyPlanGroup>, List<StudyPlanCourse>> {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        // Step 1: selfHtml (GBK)
        val selfScheduleUrl = "$campusBaseUrl/academic/manager/studyschedule/studentSelfSchedule.jsdo"
        val selfRequest = Request.Builder().url(selfScheduleUrl)
            .header("Cookie", cookie).header("User-Agent", UA).get().build()

        val (selfBody, selfContentType) = withContext(Dispatchers.IO) {
            client.newCall(selfRequest).execute().use { response ->
                Pair(response.body?.bytes() ?: ByteArray(0), response.header("Content-Type") ?: "")
            }
        }
        val gbkCharset = try { Charset.forName("GBK") } catch (_: Exception) { Charsets.UTF_8 }
        val selfHtml = String(selfBody, gbkCharset)
        Log.d(TAG, "Self: ${selfHtml.length}B")

        val ids = studyPlanParser.parseStudentIds(selfHtml)
        if (ids == null) { Log.e(TAG, "No student IDs"); return Pair(emptyList(), emptyList()) }
        val (studentId, classId) = ids

        // Step 2: lineHtml (平铺模式)
        val lineShowUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
        val lineRequest = Request.Builder().url(lineShowUrl)
            .header("Cookie", cookie).header("User-Agent", UA).get().build()

        val (lineBody, lineContentType) = withContext(Dispatchers.IO) {
            client.newCall(lineRequest).execute().use { response ->
                Pair(response.body?.bytes() ?: ByteArray(0), response.header("Content-Type") ?: "")
            }
        }
        val utfCharset = if (lineContentType.contains("charset=", ignoreCase = true))
            try { Charset.forName(lineContentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")) }
            catch (_: Exception) { Charsets.UTF_8 }
        else Charsets.UTF_8
        val lineHtml = String(lineBody, utfCharset)
        Log.d(TAG, "LineShow: ${lineHtml.length}B")

        val (groups, courses) = studyPlanParser.parseData(lineHtml)

        // Step 3: 框架模式 — 任选课组详情（best-effort，失败不影响主流程）
        try {
            val frameStudentId = studyPlanParser.parseFrameStudentId(selfHtml)
            Log.d(TAG, "Frame studentId: ${frameStudentId ?: "NOT FOUND"}")
            if (frameStudentId != null) {
                val frameHtml = fetchFrameHtml(cookie, campusBaseUrl, frameStudentId, classId, client)
                if (frameHtml != null) {
                    val freeGroupIds = studyPlanParser.extractFreeGroupIds(frameHtml)
                    Log.d(TAG, "Free groups: ${freeGroupIds.size}")
                    if (freeGroupIds.isNotEmpty()) {
                        val freeResults = freeGroupIds.map { (gid, gname) ->
                            fetchFreeGroup(cookie, campusBaseUrl, gid, gname, client)
                        }
                        val mg = groups.toMutableList()
                        val mc = courses.toMutableList()
                        for ((fg, fcs) in freeResults) {
                            if (fg != null) {
                                val idx = mg.indexOfFirst { it.groupName == fg.groupName }
                                if (idx >= 0) mg[idx] = fg else mg.add(fg)
                                mc.addAll(fcs)
                            }
                        }
                        return Pair(mg.distinctBy { it.id }, mc.distinctBy { it.id })
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame mode fetch failed (non-fatal): ${e.message}")
        }

        return Pair(groups, courses)
    }

    private suspend fun fetchFrameHtml(
        cookie: String, baseUrl: String, frameStudentId: String, classId: String,
        client: OkHttpClient
    ): String? = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/academic/manager/studyschedule/studentScheduleShowFrame.do?z=z&studentId=$frameStudentId&classId=$classId"
            val req = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { resp ->
                val ct = resp.header("Content-Type") ?: ""
                val cs = if (ct.contains("charset=", ignoreCase = true))
                    try { Charset.forName(ct.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")) }
                    catch (_: Exception) { Charsets.UTF_8 }
                else Charsets.UTF_8
                String(resp.body?.bytes() ?: ByteArray(0), cs)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame page failed: ${e.message}")
            null
        }
    }

    private suspend fun fetchFreeGroup(
        cookie: String, baseUrl: String, groupId: String, name: String,
        client: OkHttpClient
    ): Pair<StudyPlanGroup?, List<StudyPlanCourse>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/academic/manager/studyschedule/scheduleFreeGroupCourseList.do?pojoTypeId=2&id=$groupId"
            val req = Request.Builder().url(url).header("Cookie", cookie).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { resp ->
                val ct = resp.header("Content-Type") ?: ""
                val cs = if (ct.contains("charset=", ignoreCase = true))
                    try { Charset.forName(ct.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")) }
                    catch (_: Exception) { Charsets.UTF_8 }
                else Charsets.UTF_8
                val html = String(resp.body?.bytes() ?: ByteArray(0), cs)
                studyPlanParser.parseFreeGroupDetail(html)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Free '$name' failed: ${e.message}")
            Pair(null, emptyList())
        }
    }

    companion object {
        private const val TAG = "StudyPlanViewModel"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}

class StudyPlanViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return StudyPlanViewModel(repository, sessionStore, loginService, studyPlanParser) as T
    }
}

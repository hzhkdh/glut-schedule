package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import com.glut.schedule.data.model.isReadOnly
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import com.glut.schedule.service.parser.StudyPlanParser
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
import com.glut.schedule.service.graduate.GraduateClient
import com.glut.schedule.service.graduate.GraduateParser
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
    val isReadOnly: Boolean = false,
    val expandedGroupId: String? = null,
    val showLegend: Boolean = false
)

class StudyPlanViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val settingsStore: ScheduleSettingsStore,
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
            isReadOnly = g.isNotEmpty() && g.all { it.isReadOnly },
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
                if (settingsStore.campusType.first() == CampusType.GRADUATE) {
                    refreshGraduateStudyPlan()
                    return@launch
                }
                val campusBaseUrl = sessionStore.campusBaseUrl.first()
                    .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

                // Try existing cookie first
                var cookie = sessionStore.academicCookie.first()
                var data = if (cookie.isNotBlank()) {
                    fetchStudyPlanData(cookie, campusBaseUrl)
                } else null

                // 会话失效：原地续期——南宁/研究生弹验证码，桂林静默换票。
                // 用户取消或续期失败都**保留已有缓存**，只提示、不动屏幕上已有的数据。
                if (data == null || data.first.isEmpty()) {
                    if (!renewal.ensureFreshSession()) {
                        _message.value = "会话已过期，已取消登录"
                        delay(4000)
                        _message.value = ""
                        return@launch
                    }
                    cookie = sessionStore.academicCookie.first()
                    data = fetchStudyPlanData(cookie, campusBaseUrl)
                    if (data == null || data.first.isEmpty()) {
                        _message.value = "获取失败，请稍后重试"
                        delay(4000)
                        _message.value = ""
                        return@launch
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

    /** 研究生培养计划走独立 JSON 网关，不得回退本科 HTML 或本科静默登录。 */
    private suspend fun refreshGraduateStudyPlan() {
        val cookie = sessionStore.academicCookie.first()
        val studentId = sessionStore.authenticatedStudentNumber.first()
        if (cookie.isBlank() || studentId.isBlank()) {
            _message.value = "研究生登录已失效，请到导入课表页面重新登录"
            return
        }
        val client = GraduateClient(cookie)
        val raw = withContext(Dispatchers.IO) { client.studyPlan(studentId) }
        val (groups, courses) = GraduateParser.studyPlan(raw)
        // 合法空结果同样覆盖旧来源缓存，避免本科培养计划残留。
        repository.replaceStudyPlanData(groups, courses)
        sessionStore.saveCookie(client.cookieHeader())
        _message.value = if (groups.isEmpty()) {
            "暂无培养计划数据"
        } else {
            "已获取 ${groups.size} 个课程类别，共 ${courses.size} 门课程"
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
                Pair(
                    response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0),
                    response.header("Content-Type") ?: ""
                )
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
                Pair(
                    response.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0),
                    response.header("Content-Type") ?: ""
                )
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
                String(resp.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0), cs)
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
                val html = String(
                    resp.body?.readBytesLimited(MAX_HTML_RESPONSE_BYTES) ?: ByteArray(0),
                    cs
                )
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
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val settingsStore: ScheduleSettingsStore,
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return StudyPlanViewModel(repository, sessionStore, loginService, renewal, settingsStore, studyPlanParser) as T
    }
}

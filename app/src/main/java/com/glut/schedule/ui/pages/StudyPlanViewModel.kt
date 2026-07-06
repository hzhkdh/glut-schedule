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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

        // ---- helpers ----
        fun mergeCookie(existing: String, response: okhttp3.Response): String {
            val setCookie = response.headers("Set-Cookie").joinToString("; ") { it.substringBefore(";") }
            if (setCookie.isBlank()) return existing
            val map = linkedMapOf<String, String>()
            existing.split(";").forEach { val i = it.indexOf("="); if (i > 0) map[it.substring(0, i).trim()] = it.trim() }
            setCookie.split(";").forEach { val i = it.indexOf("="); if (i > 0) map[it.substring(0, i).trim()] = it.trim() }
            return map.values.joinToString("; ")
        }

        fun resolveCharset(ct: String?): Charset {
            if (ct != null && ct.contains("charset=", ignoreCase = true)) {
                try { return Charset.forName(ct.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")) }
                catch (_: Exception) {}
            }
            try { return Charset.forName("GBK") } catch (_: Exception) { return Charsets.UTF_8 }
        }

        suspend fun fetchBytes(req: Request) = withContext(Dispatchers.IO) {
            client.newCall(req).execute()
        }

        var curCookie = cookie

        // Step 1: selfHtml (GBK)
        val selfUrl = "$campusBaseUrl/academic/manager/studyschedule/studentSelfSchedule.jsdo"
        val selfResp = fetchBytes(Request.Builder().url(selfUrl).header("Cookie", curCookie).header("User-Agent", UA).get().build())
        curCookie = mergeCookie(curCookie, selfResp)
        val selfHtml = String(selfResp.body?.bytes() ?: ByteArray(0), Charset.forName("GBK"))
        Log.d(TAG, "Self: ${selfHtml.length}B, cookie=${curCookie.take(30)}...")

        val ids = studyPlanParser.parseStudentIds(selfHtml)
        if (ids == null) { Log.e(TAG, "No student IDs found"); return Pair(emptyList(), emptyList()) }
        val (studentId, classId) = ids
        Log.d(TAG, "IDs: studentId=$studentId, classId=$classId")

        // Step 2: lineHtml (平铺模式)
        val lineUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
        val lineResp = fetchBytes(Request.Builder().url(lineUrl).header("Cookie", curCookie).header("User-Agent", UA).get().build())
        curCookie = mergeCookie(curCookie, lineResp)
        val lineCt = lineResp.header("Content-Type") ?: ""
        val lineHtml = String(lineResp.body?.bytes() ?: ByteArray(0), resolveCharset(lineCt))
        Log.d(TAG, "LineShow: ${lineHtml.length}B, status=${lineResp.code}")

        val (groups, courses) = studyPlanParser.parseData(lineHtml)

        // Step 3: 框架模式 — 任选课组详情
        val frameStudentId = studyPlanParser.parseFrameStudentId(selfHtml)
        Log.d(TAG, "Frame studentId: ${frameStudentId ?: "NOT FOUND"}")

        if (frameStudentId != null) {
            val frameUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleShowFrame.do?z=z&studentId=$frameStudentId&classId=$classId"
            val frameResp = fetchBytes(Request.Builder().url(frameUrl).header("Cookie", curCookie).header("User-Agent", UA).get().build())
            curCookie = mergeCookie(curCookie, frameResp)
            val frameHtml = String(frameResp.body?.bytes() ?: ByteArray(0), resolveCharset(frameResp.header("Content-Type")))
            Log.d(TAG, "Frame: ${frameHtml.length}B, status=${frameResp.code}")

            val freeGroupIds = studyPlanParser.extractFreeGroupIds(frameHtml)
            Log.d(TAG, "Free groups: ${freeGroupIds.size} — ${freeGroupIds.joinToString { "${it.first}=${it.second}" }}")

            if (freeGroupIds.isNotEmpty()) {
                // Parallel fetch
                val freeResults = coroutineScope {
                    freeGroupIds.map { (gid, gname) ->
                        async(Dispatchers.IO) {
                            try {
                                val gUrl = "$campusBaseUrl/academic/manager/studyschedule/scheduleFreeGroupCourseList.do?pojoTypeId=2&id=$gid"
                                val gResp = client.newCall(Request.Builder().url(gUrl).header("Cookie", curCookie).header("User-Agent", UA).get().build()).execute()
                                val gHtml = String(gResp.body?.bytes() ?: ByteArray(0), resolveCharset(gResp.header("Content-Type")))
                                Log.d(TAG, "Free '$gname': ${gHtml.length}B, status=${gResp.code}")
                                studyPlanParser.parseFreeGroupDetail(gHtml)
                            } catch (e: Exception) {
                                Log.w(TAG, "Free '$gname' failed: ${e.message}")
                                Pair<StudyPlanGroup?, List<StudyPlanCourse>>(null, emptyList())
                            }
                        }
                    }.awaitAll()
                }

                val mergedGroups = groups.toMutableList()
                val mergedCourses = courses.toMutableList()
                for ((freeGroup, freeCourses) in freeResults) {
                    if (freeGroup != null) {
                        val idx = mergedGroups.indexOfFirst { it.groupName == freeGroup.groupName }
                        if (idx >= 0) mergedGroups[idx] = freeGroup else mergedGroups.add(freeGroup)
                        mergedCourses.addAll(freeCourses)
                        Log.d(TAG, "Merged '${freeGroup.groupName}': ${freeCourses.size} courses")
                    }
                }
                return Pair(mergedGroups.distinctBy { it.id }, mergedCourses.distinctBy { it.id })
            }
        }

        return Pair(groups, courses)
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

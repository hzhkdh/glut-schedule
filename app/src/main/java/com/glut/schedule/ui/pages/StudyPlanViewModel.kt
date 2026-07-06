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

        // Step 1: Fetch studentSelfSchedule.jsdo (GBK-encoded) to get studentId and classId
        val selfScheduleUrl = "$campusBaseUrl/academic/manager/studyschedule/studentSelfSchedule.jsdo"
        Log.d(TAG, "Fetching student self schedule from: $selfScheduleUrl")

        val selfRequest = Request.Builder()
            .url(selfScheduleUrl)
            .header("Cookie", cookie)
            .header("User-Agent", UA)
            .get()
            .build()

        val (selfBody, selfContentType) = withContext(Dispatchers.IO) {
            client.newCall(selfRequest).execute().use { response ->
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: ""
                Pair(rawBytes, ct)
            }
        }

        Log.d(TAG, "Self schedule response: bodyLen=${selfBody.size}, contentType=$selfContentType")

        // Use GBK charset for self schedule page
        val gbkCharset = try {
            Charset.forName("GBK")
        } catch (_: Exception) {
            Charsets.UTF_8
        }
        val selfHtml = String(selfBody, gbkCharset)
        Log.d(TAG, "Self schedule HTML preview: ${selfHtml.take(300)}")

        val ids = studyPlanParser.parseStudentIds(selfHtml)
        if (ids == null) {
            Log.e(TAG, "Failed to parse studentId/classId from self schedule page")
            return Pair(emptyList(), emptyList())
        }
        val (studentId, classId) = ids
        Log.d(TAG, "Parsed studentId=$studentId, classId=$classId")

        // Step 2: Fetch studentScheduleLineShow.do (UTF-8) with studentId and classId
        val lineShowUrl = "$campusBaseUrl/academic/manager/studyschedule/studentScheduleLineShow.do?z=z&studentId=$studentId&classId=$classId"
        Log.d(TAG, "Fetching study plan groups from: $lineShowUrl")

        val lineRequest = Request.Builder()
            .url(lineShowUrl)
            .header("Cookie", cookie)
            .header("User-Agent", UA)
            .get()
            .build()

        val (lineBody, lineContentType) = withContext(Dispatchers.IO) {
            client.newCall(lineRequest).execute().use { response ->
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: ""
                Pair(rawBytes, ct)
            }
        }

        Log.d(TAG, "Line show response: bodyLen=${lineBody.size}, contentType=$lineContentType")

        // Use UTF-8 for line show page, fall back to content-type charset
        val utfCharset = if (lineContentType.contains("charset=", ignoreCase = true)) {
            try {
                Charset.forName(lineContentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\""))
            } catch (_: Exception) {
                Charsets.UTF_8
            }
        } else {
            Charsets.UTF_8
        }
        val lineHtml = String(lineBody, utfCharset)
        Log.d(TAG, "Line show HTML preview: ${lineHtml.take(300)}")

        // Parse flat-mode data (必修/限选 groups + courses)
        val (groups, courses) = studyPlanParser.parseData(lineHtml)

        // Hybrid mode: also fetch framework-mode data for 任选 group details
        val frameStudentId = studyPlanParser.parseFrameStudentId(selfHtml)
        if (frameStudentId != null) {
            val frameHtml = fetchFrameHtml(cookie, campusBaseUrl, frameStudentId, classId, client)
            if (frameHtml != null) {
                val freeGroupIds = studyPlanParser.extractFreeGroupIds(frameHtml)
                Log.d(TAG, "Found ${freeGroupIds.size} free elective groups in frame mode")

                val freeGroupResults = freeGroupIds.map { (id, name) ->
                    asyncFetchFreeGroup(cookie, campusBaseUrl, id, name, client)
                }

                val mergedGroups = groups.toMutableList()
                val mergedCourses = courses.toMutableList()
                for (result in freeGroupResults) {
                    val (freeGroup, freeCourses) = result
                    if (freeGroup != null) {
                        val idx = mergedGroups.indexOfFirst { it.groupName == freeGroup.groupName }
                        if (idx >= 0) mergedGroups[idx] = freeGroup else mergedGroups.add(freeGroup)
                        mergedCourses.addAll(freeCourses)
                    }
                }
                return Pair(mergedGroups.distinctBy { it.id }, mergedCourses.distinctBy { it.id })
            }
        }

        return Pair(groups, courses)
    }

    private suspend fun resolveCharset(contentType: String?): Charset {
        if (contentType != null && contentType.contains("charset=", ignoreCase = true)) {
            try { return Charset.forName(contentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\"")) }
            catch (_: Exception) { /* fall through */ }
        }
        // 教务系统页面通常是 GBK 编码
        try { return Charset.forName("GBK") } catch (_: Exception) { return Charsets.UTF_8 }
    }

    private suspend fun fetchFrameHtml(
        cookie: String, baseUrl: String, frameStudentId: String, classId: String,
        client: OkHttpClient
    ): String? = withContext(Dispatchers.IO) {
        try {
            val frameUrl = "$baseUrl/academic/manager/studyschedule/studentScheduleShowFrame.do?z=z&studentId=$frameStudentId&classId=$classId"
            val req = Request.Builder().url(frameUrl)
                .header("Cookie", cookie).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { response ->
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
                String(rawBytes, resolveCharset(response.header("Content-Type")))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch frame page: ${e.message}")
            null
        }
    }

    private suspend fun asyncFetchFreeGroup(
        cookie: String, baseUrl: String, groupId: String, name: String,
        client: OkHttpClient
    ): Pair<StudyPlanGroup?, List<StudyPlanCourse>> = withContext(Dispatchers.IO) {
        try {
            val url = "$baseUrl/academic/manager/studyschedule/scheduleFreeGroupCourseList.do?pojoTypeId=2&id=$groupId"
            val req = Request.Builder().url(url)
                .header("Cookie", cookie).header("User-Agent", UA).get().build()
            client.newCall(req).execute().use { response ->
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
                val html = String(rawBytes, resolveCharset(response.header("Content-Type")))
                studyPlanParser.parseFreeGroupDetail(html)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch free group '$name': ${e.message}")
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

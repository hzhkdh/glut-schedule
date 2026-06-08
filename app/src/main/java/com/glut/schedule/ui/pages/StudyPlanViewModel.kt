package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.StudyPlanGroup
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

class StudyPlanViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val studyPlanParser: StudyPlanParser = StudyPlanParser()
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")

    val uiState: StateFlow<StudyPlanUiState> = combine(
        repository.studyPlanGroups,
        sessionStore.academicCookie,
        _isRefreshing,
        _message
    ) { groups, cookie, isRefreshing, message ->
        StudyPlanUiState(
            groups = groups,
            isRefreshing = isRefreshing,
            message = message,
            hasCookie = cookie.isNotBlank(),
            cookieValue = cookie
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StudyPlanUiState()
    )

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _message.value = "正在获取培养计划..."
            try {
                var cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank()) {
                    val loginResult = loginService.silentLogin()
                    if (loginResult is AcademicLoginResult.Success) {
                        cookie = sessionStore.academicCookie.first()
                    } else {
                        _message.value = "请先在导入课表页面登录教务系统"
                        delay(4000)
                        _message.value = ""
                        return@launch
                    }
                }

                val campusBaseUrl = sessionStore.campusBaseUrl.first()
                    .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

                val groups = fetchStudyPlanGroups(cookie, campusBaseUrl)
                if (groups.isNotEmpty()) {
                    repository.replaceStudyPlanGroups(groups)
                    _message.value = "已获取 ${groups.size} 个培养计划课组"
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

    internal suspend fun fetchStudyPlanGroups(
        cookie: String,
        campusBaseUrl: String
    ): List<StudyPlanGroup> {
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
            return emptyList()
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

        return studyPlanParser.parseGroups(lineHtml)
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

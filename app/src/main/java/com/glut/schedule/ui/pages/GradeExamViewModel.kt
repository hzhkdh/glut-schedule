package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.GradeExamInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.GradeExamParser
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

data class GradeExamUiState(
    val exams: List<GradeExamInfo> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val hasCookie: Boolean = false
)

class GradeExamViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val gradeExamParser: GradeExamParser = GradeExamParser()
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")

    val uiState: StateFlow<GradeExamUiState> = combine(
        repository.gradeExams,
        sessionStore.academicCookie,
        _isRefreshing,
        _message
    ) { exams, cookie, isRefreshing, message ->
        GradeExamUiState(
            exams = exams,
            isRefreshing = isRefreshing,
            message = message,
            hasCookie = cookie.isNotBlank()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = GradeExamUiState()
    )

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _message.value = "正在获取等级考试成绩..."
            try {
                val campusBaseUrl = sessionStore.campusBaseUrl.first()
                    .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

                // Try existing cookie first
                var cookie = sessionStore.academicCookie.first()
                var allExams = if (cookie.isNotBlank()) {
                    fetchGradeExams(cookie, campusBaseUrl)
                } else emptyList()

                // If cookie expired or missing, try silent login
                if (allExams.isEmpty()) {
                    _message.value = "会话已过期，正在使用已保存的账号自动登录..."
                    when (val loginResult = loginService.silentLogin()) {
                        is AcademicLoginResult.Success -> {
                            cookie = sessionStore.academicCookie.first()
                            allExams = fetchGradeExams(cookie, campusBaseUrl)
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

                if (allExams.isNotEmpty()) {
                    repository.replaceGradeExams(allExams)
                    _message.value = "已获取 ${allExams.size} 条等级考试记录"
                } else {
                    _message.value = "暂无等级考试记录"
                }
            } catch (e: Exception) {
                _message.value = "获取失败: ${e.message}"
                Log.e(TAG, "Grade exam fetch failed", e)
            } finally {
                _isRefreshing.value = false
                delay(4000)
                _message.value = ""
            }
        }
    }

    internal suspend fun fetchGradeExams(cookie: String, campusBaseUrl: String): List<GradeExamInfo> {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        val url = "$campusBaseUrl/academic/student/skilltest/skilltest.jsdo?moduleId=2090"
        Log.d(TAG, "Fetching grade exams from: $url")

        val request = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", UA)
            .get()
            .build()

        val (body, contentType) = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val rawBytes = response.body?.bytes() ?: ByteArray(0)
                val ct = response.header("Content-Type") ?: ""
                Pair(rawBytes, ct)
            }
        }

        Log.d(TAG, "Grade exam response: bodyLen=${body.size}, contentType=$contentType")

        val charset = if (contentType.contains("charset=", ignoreCase = true)) {
            try {
                Charset.forName(contentType.substringAfter("charset=").trim().removePrefix("\"").removeSuffix("\""))
            } catch (_: Exception) {
                Charsets.UTF_8
            }
        } else {
            try { Charset.forName("GBK") } catch (_: Exception) { Charsets.UTF_8 }
        }
        val html = String(body, charset)
        Log.d(TAG, "Grade exam HTML preview: ${html.take(300)}")

        return gradeExamParser.parse(html)
    }

    companion object {
        private const val TAG = "GradeExamViewModel"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}

class GradeExamViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val gradeExamParser: GradeExamParser = GradeExamParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return GradeExamViewModel(repository, sessionStore, loginService, gradeExamParser) as T
    }
}

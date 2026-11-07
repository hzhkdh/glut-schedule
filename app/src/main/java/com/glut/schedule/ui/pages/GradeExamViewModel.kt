package com.glut.schedule.ui.pages

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.GradeExamInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import com.glut.schedule.service.academic.looksLikeAcademicLoginPage
import com.glut.schedule.service.parser.GradeExamParser
import com.glut.schedule.service.network.MAX_HTML_RESPONSE_BYTES
import com.glut.schedule.service.network.readBytesLimited
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

/** 等级考试页面响应必须区分“无记录”和“会话已失效”，避免空列表误触发验证码续期。 */
private sealed interface GradeExamFetchResult {
    data class Success(val exams: List<GradeExamInfo>) : GradeExamFetchResult
    data object SessionExpired : GradeExamFetchResult
    data class Failure(val message: String) : GradeExamFetchResult
}

class GradeExamViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
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
                var campusBaseUrl = sessionStore.campusBaseUrl.first()
                    .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

                // Try existing cookie first
                var cookie = sessionStore.academicCookie.first()
                var fetchResult = if (cookie.isNotBlank()) {
                    fetchGradeExamResult(cookie, campusBaseUrl)
                } else GradeExamFetchResult.SessionExpired

                // 会话失效：原地续期——南宁/研究生弹验证码，桂林静默换票。
                // 用户取消或续期失败都**保留已有缓存**，只提示、不动屏幕上已有的数据。
                if (fetchResult == GradeExamFetchResult.SessionExpired) {
                    if (!renewal.ensureFreshSession()) {
                        _message.value = "会话已过期，已取消登录"
                        delay(4000)
                        _message.value = ""
                        return@launch
                    }
                    cookie = sessionStore.academicCookie.first()
                    // 主机地址也要重读：协调器刚把新值写回 DataStore，而这里手上还是门卫之前读的旧值。
                    // 漏掉这一行，重放会把新 cookie 发到旧主机上（南宁换研究生时必然失败）。
                    campusBaseUrl = sessionStore.campusBaseUrl.first()
                        .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }
                    fetchResult = fetchGradeExamResult(cookie, campusBaseUrl)
                }

                when (fetchResult) {
                    is GradeExamFetchResult.Success -> {
                        val allExams = fetchResult.exams
                        if (allExams.isNotEmpty()) {
                            repository.replaceGradeExams(allExams)
                            _message.value = "已获取 ${allExams.size} 条等级考试记录"
                        } else {
                            _message.value = "暂无等级考试记录"
                        }
                    }
                    GradeExamFetchResult.SessionExpired -> {
                        _message.value = "会话续期后仍未恢复，请稍后重试"
                    }
                    is GradeExamFetchResult.Failure -> {
                        throw IllegalStateException(fetchResult.message)
                    }
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

    /** 页面与后台共用解析和结构校验，登录失效仍交回现有验证码续期流程。 */
    private suspend fun fetchGradeExamResult(cookie: String, campusBaseUrl: String): GradeExamFetchResult = try {
        GradeExamFetchResult.Success(com.glut.schedule.service.refresh.AcademicRefreshQueries.gradeExams(cookie, campusBaseUrl))
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: com.glut.schedule.service.refresh.ScheduledRefreshLoginRequiredException) {
        GradeExamFetchResult.SessionExpired
    } catch (_: Exception) {
        GradeExamFetchResult.Failure("获取考级成绩失败，请稍后重试")
    }

    companion object {
        private const val TAG = "GradeExamViewModel"
        private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }
}

class GradeExamViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator,
    private val gradeExamParser: GradeExamParser = GradeExamParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return GradeExamViewModel(repository, sessionStore, renewal, gradeExamParser) as T
    }
}

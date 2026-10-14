package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.academic.SessionRenewalCoordinator
import com.glut.schedule.service.academic.shouldUseExistingAcademicCookie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ExamViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val examService: AcademicExamService,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ExamViewModel(repository, sessionStore, examService, renewal) as T
    }
}

data class ExamUiState(
    val exams: List<ExamInfo> = emptyList(),
    val hasCookie: Boolean = false,
    val isRefreshing: Boolean = false,
    val message: String = "",
    val examCount: Int = 0
)

class ExamViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val examService: AcademicExamService,
    /** 会话失效时的续期门卫：南宁/研究生原地弹验证码，桂林静默换票。 */
    private val renewal: SessionRenewalCoordinator
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")

    val uiState: StateFlow<ExamUiState> = combine(
        repository.exams,
        sessionStore.academicCookie,
        _isRefreshing,
        _message
    ) { exams, cookie, isRefreshing, message ->
        ExamUiState(
            exams = exams,
            hasCookie = cookie.isNotBlank(),
            isRefreshing = isRefreshing,
            message = message,
            examCount = exams.size
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ExamUiState()
    )

    fun refreshExams() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _message.value = "正在刷新..."
            try {
                val existingCookie = sessionStore.academicCookie.first()
                if (shouldUseExistingAcademicCookie(existingCookie) && fetchAndSaveExams(existingCookie)) {
                    return@launch
                }

                // 会话失效：原地续期——南宁/研究生弹验证码，桂林静默换票。
                // 用户取消或续期失败都**保留已有缓存**，只提示、不动屏幕上已有的考试安排。
                if (!renewal.ensureFreshSession()) {
                    _message.value = "会话已过期，已取消登录"
                    return@launch
                }
                val cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank()) {
                    _message.value = "未获取到教务登录态，请重新登录"
                    return@launch
                }
                // 不再接 fetchAndSaveExams 的布尔返回值：它原先只是用来置位一个
                // 「需要交互登录」开关，而那个开关全仓无人消费（已删除），
                // 失败文案照旧由 onFailure 分支写进 _message。
                fetchAndSaveExams(cookie)
            } catch (e: Exception) {
                _message.value = "网络错误: ${e.message}"
            } finally {
                _isRefreshing.value = false
                kotlinx.coroutines.delay(4000)
                _message.value = ""
            }
        }
    }

    private suspend fun fetchAndSaveExams(cookie: String): Boolean {
        val examApiUrl = sessionStore.examApiUrl.first()
        val campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }
        return examService.fetchExamData(cookie, examApiUrl, campusBaseUrl)
            .onSuccess { exams ->
                repository.replaceExams(exams)
                val successfulUrl = examService.lastSuccessfulExamUrl
                if (successfulUrl.isNotBlank()) {
                    sessionStore.saveExamApiUrl(successfulUrl)
                }
                _message.value = "已更新 ${exams.size} 门考试"
            }
            .onFailure { error ->
                _message.value = error.message ?: "获取失败"
            }
            .isSuccess
    }

    fun clearMessage() {
        _message.value = ""
    }
}

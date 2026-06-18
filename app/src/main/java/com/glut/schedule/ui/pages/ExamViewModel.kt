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
    private val loginService: AcademicLoginService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ExamViewModel(repository, sessionStore, examService, loginService) as T
    }
}

data class ExamUiState(
    val exams: List<ExamInfo> = emptyList(),
    val hasCookie: Boolean = false,
    val isRefreshing: Boolean = false,
    val message: String = "",
    val examCount: Int = 0,
    val needsInteractiveLogin: Boolean = false
)

class ExamViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val examService: AcademicExamService,
    private val loginService: com.glut.schedule.service.academic.AcademicLoginService
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _needsInteractiveLogin = MutableStateFlow(false)

    val uiState: StateFlow<ExamUiState> = combine(
        repository.exams,
        sessionStore.academicCookie,
        _isRefreshing,
        _message,
        _needsInteractiveLogin
    ) { exams, cookie, isRefreshing, message, needsInteractiveLogin ->
        ExamUiState(
            exams = exams,
            hasCookie = cookie.isNotBlank(),
            isRefreshing = isRefreshing,
            message = message,
            examCount = exams.size,
            needsInteractiveLogin = needsInteractiveLogin
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
            _needsInteractiveLogin.value = false
            try {
                val existingCookie = sessionStore.academicCookie.first()
                if (shouldUseExistingAcademicCookie(existingCookie) && fetchAndSaveExams(existingCookie)) {
                    return@launch
                }

                when (val loginResult = loginService.silentLogin()) {
                    is AcademicLoginResult.Success -> Unit
                    AcademicLoginResult.MissingCredentials -> {
                        _message.value = "请先登录教务系统以保存账号密码"
                        _needsInteractiveLogin.value = true
                        return@launch
                    }
                    AcademicLoginResult.InvalidCredentials -> {
                        _message.value = "教务账号或密码错误，请重新登录"
                        _needsInteractiveLogin.value = true
                        return@launch
                    }
                    AcademicLoginResult.CaptchaOrInteractiveLoginRequired -> {
                        _message.value = "教务系统需要手动验证，请重新登录"
                        _needsInteractiveLogin.value = true
                        return@launch
                    }
                    is AcademicLoginResult.NetworkError -> {
                        _message.value = "登录失败: ${loginResult.message}"
                        _needsInteractiveLogin.value = true
                        return@launch
                    }
                }
                val cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank()) {
                    _message.value = "未获取到教务登录态，请重新登录"
                    _needsInteractiveLogin.value = true
                    return@launch
                }
                if (!fetchAndSaveExams(cookie)) {
                    _needsInteractiveLogin.value = true
                }
            } catch (e: Exception) {
                _message.value = "网络错误: ${e.message}"
                _needsInteractiveLogin.value = true
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

    fun consumeInteractiveLoginRequest() {
        _needsInteractiveLogin.value = false
    }

}

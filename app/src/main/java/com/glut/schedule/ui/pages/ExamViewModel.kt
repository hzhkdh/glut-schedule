package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.AcademicSessionStore
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
    private val examService: AcademicExamService
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ExamViewModel(repository, sessionStore, examService) as T
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
    private val examService: AcademicExamService
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
            _message.value = ""
            try {
                val cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank()) {
                    _message.value = "请先登录教务系统"
                    return@launch
                }
                _message.value = "正在获取考试安排..."
                val examApiUrl = sessionStore.examApiUrl.first()
                val result = examService.fetchExamData(cookie, examApiUrl)
                result.fold(
                    onSuccess = { exams ->
                        repository.replaceExams(exams)
                        _message.value = "已更新 ${exams.size} 门考试"
                    },
                    onFailure = { error ->
                        _message.value = error.message ?: "获取失败"
                    }
                )
            } catch (e: Exception) {
                _message.value = "网络错误: ${e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun clearMessage() {
        _message.value = ""
    }

}

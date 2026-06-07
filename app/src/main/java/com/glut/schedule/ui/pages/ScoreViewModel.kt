package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.ScoreParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ScoreUiState(
    val scores: List<ScoreInfo> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val hasCookie: Boolean = false
)

class ScoreViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val scoreParser: ScoreParser = ScoreParser()
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _selectedYear = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ScoreUiState> = combine(
        repository.scores,
        sessionStore.academicCookie,
        _isRefreshing,
        _message
    ) { scores, cookie, isRefreshing, message ->
        ScoreUiState(
            scores = scores,
            isRefreshing = isRefreshing,
            message = message,
            hasCookie = cookie.isNotBlank()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScoreUiState()
    )

    val availableYears: StateFlow<List<String>> = repository.scores
        .map { scores -> scores.map { it.year }.distinct().sortedDescending() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedYear: StateFlow<String?> = _selectedYear

    fun selectYear(year: String?) {
        _selectedYear.value = year
    }

    fun refreshScores() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _message.value = "正在获取成绩..."
            try {
                var cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank()) {
                    val loginResult = loginService.silentLogin()
                    if (loginResult is AcademicLoginResult.Success) {
                        cookie = sessionStore.academicCookie.first()
                    } else {
                        _message.value = "请先在导入课表页面登录教务系统"
                        return@launch
                    }
                }

                val allScores = fetchAllScores(cookie)
                if (allScores.isNotEmpty()) {
                    repository.replaceScores(allScores)
                    _message.value = "已获取 ${allScores.size} 条成绩记录"
                } else {
                    _message.value = "未获取到成绩数据"
                }
            } catch (e: Exception) {
                _message.value = "获取失败: ${e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchAllScores(cookie: String): List<ScoreInfo> {
        val campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

        val scoreClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("$campusBaseUrl/academic/manager/score/studentOwnScore.do?year=&term=&para=0&sortColumn=&Submit=%E6%9F%A5%E8%AF%A2")
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .get()
            .build()

        val body = withContext(Dispatchers.IO) {
            scoreClient.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }
        }

        val isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL
        return scoreParser.parseScoreHtml(body, isNanning = isNanning)
    }

    fun clearMessage() { _message.value = "" }
}

class ScoreViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val scoreParser: ScoreParser = ScoreParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScoreViewModel(repository, sessionStore, loginService, scoreParser) as T
    }
}

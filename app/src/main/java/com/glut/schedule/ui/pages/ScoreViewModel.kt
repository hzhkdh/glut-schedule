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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
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
        val scoreClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        val allScores = mutableListOf<ScoreInfo>()
        val years = listOf("2025-2026", "2024-2025", "2023-2024", "2022-2023")
        for (year in years) {
            for (term in 1..2) {
                try {
                    val formBody = FormBody.Builder()
                        .add("year", year).add("term", term.toString()).add("para", "0")
                        .build()
                    val request = Request.Builder()
                        .url("http://jw.glut.edu.cn/academic/manager/score/studentOwnScore.do")
                        .header("Cookie", cookie)
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                        .post(formBody).build()
                    withContext(Dispatchers.IO) {
                        scoreClient.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            allScores.addAll(scoreParser.parseScoreHtml(body, year, term))
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        return allScores
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

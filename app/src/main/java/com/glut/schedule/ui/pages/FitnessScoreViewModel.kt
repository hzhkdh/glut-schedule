package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.FitnessHistoryRecord
import com.glut.schedule.data.model.FitnessResult
import com.glut.schedule.data.model.FitnessStandardTable
import com.glut.schedule.service.fitness.FitnessApiResponse
import com.glut.schedule.service.fitness.FitnessApiService
import com.glut.schedule.service.fitness.FitnessStore
import com.glut.schedule.service.parser.FitnessParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FitnessTab { LATEST, HISTORY, STANDARD }

data class FitnessScoreUiState(
    val activeTab: FitnessTab = FitnessTab.LATEST,
    val isRefreshing: Boolean = false,
    val message: String = "",
    val current: FitnessResult? = null,
    val history: List<FitnessHistoryRecord> = emptyList(),
    val historyDetails: Map<String, FitnessResult> = emptyMap(),
    val selectedHistoryKey: String = "",
    val standards: List<FitnessStandardTable> = emptyList(),
    val selectedStandardKey: String = "male",
    val showLoginDialog: Boolean = false,
    val username: String = "",
    val password: String = "",
    val captcha: String = "",
    val captchaImage: String = "",
    val loginError: String = ""
) {
    val selectedHistory: FitnessHistoryRecord?
        get() = history.firstOrNull { it.key == selectedHistoryKey }

    val visibleHistoryResult: FitnessResult?
        get() = historyDetails[selectedHistoryKey]
}

class FitnessScoreViewModel(
    private val service: FitnessApiService,
    private val store: FitnessStore,
    private val parser: FitnessParser = FitnessParser()
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        FitnessScoreUiState(
            username = store.getUsername(),
            password = store.getPassword()
        )
    )
    val uiState: StateFlow<FitnessScoreUiState> = _uiState.asStateFlow()

    private var messageJob: Job? = null
    private var captchaCookie = ""
    private var captchaRequestId = 0

    init {
        loadCache()
    }

    fun selectTab(tab: FitnessTab) {
        _uiState.update { it.copy(activeTab = tab, message = "") }
        when (tab) {
            FitnessTab.HISTORY -> ensureSelectedHistoryLoaded()
            FitnessTab.STANDARD -> if (_uiState.value.standards.isEmpty()) loadStandard(false)
            FitnessTab.LATEST -> Unit
        }
    }

    fun selectHistory(key: String) {
        if (_uiState.value.selectedHistoryKey == key) return
        _uiState.update { it.copy(selectedHistoryKey = key, message = "") }
        ensureSelectedHistoryLoaded()
    }

    fun selectStandard(key: String) {
        _uiState.update { it.copy(selectedStandardKey = key) }
    }

    fun updateUsername(value: String) = _uiState.update { it.copy(username = value, loginError = "") }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, loginError = "") }
    fun updateCaptcha(value: String) = _uiState.update { it.copy(captcha = value, loginError = "") }

    fun dismissLogin() {
        _uiState.update { it.copy(showLoginDialog = false, loginError = "", captcha = "", message = "") }
    }

    fun clearData() {
        messageJob?.cancel()
        captchaRequestId++
        captchaCookie = ""
        store.clearAll()
        _uiState.value = FitnessScoreUiState()
    }

    fun showLogin() {
        requestCaptcha()
    }

    fun refreshCaptcha() {
        requestCaptcha(preserveError = true)
    }

    fun login() {
        val state = _uiState.value
        val username = state.username.trim()
        val password = state.password
        val captcha = state.captcha.trim()
        val validation = when {
            username.isBlank() -> "请输入学号"
            password.isBlank() -> "请输入体测平台密码"
            captcha.isBlank() -> "请输入验证码"
            else -> ""
        }
        if (validation.isNotBlank()) {
            _uiState.update { it.copy(loginError = validation) }
            return
        }
        viewModelScope.launch {
            var followUpTab: FitnessTab? = null
            _uiState.update { it.copy(isRefreshing = true, loginError = "") }
            try {
                val result = service.login(username, password, captcha, captchaCookie)
                if (result.success) {
                    store.saveCredentials(username, password)
                    updateSession(result)
                    applySnapshot(result)
                    _uiState.update {
                        it.copy(showLoginDialog = false, captcha = "", captchaImage = "", loginError = "")
                    }
                    followUpTab = _uiState.value.activeTab
                    showMessage("体测成绩已更新")
                } else {
                    _uiState.update { it.copy(loginError = loginError(result), captcha = "") }
                    requestCaptcha(preserveError = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                _uiState.update { it.copy(loginError = networkError(error), captcha = "") }
                requestCaptcha(preserveError = true)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
            when (followUpTab) {
                FitnessTab.HISTORY -> ensureSelectedHistoryLoaded()
                FitnessTab.STANDARD -> loadStandard(true)
                else -> Unit
            }
        }
    }

    fun refresh() {
        messageJob?.cancel()
        _uiState.update { it.copy(message = "") }
        when (_uiState.value.activeTab) {
            FitnessTab.LATEST -> refreshLatest()
            FitnessTab.HISTORY -> refreshSelectedHistory()
            FitnessTab.STANDARD -> loadStandard(true)
        }
    }

    private fun loadCache() {
        viewModelScope.launch(Dispatchers.Default) {
            val currentHtml = store.getCurrentHtml()
            val historyHtml = store.getHistoryHtml()
            val snapshot = if (currentHtml.isNotBlank() || historyHtml.isNotBlank()) {
                parser.parseSnapshot(currentHtml, historyHtml)
            } else null
            val standardHtml = store.getStandardHtml()
            val standards = if (standardHtml.isNotBlank()) parser.parseStandard(standardHtml) else emptyList()
            val selectedKey = snapshot?.history?.firstOrNull()?.key.orEmpty()
            val details = if (selectedKey.isNotBlank()) {
                val record = snapshot?.history?.firstOrNull()
                val detailHtml = record?.let { store.getHistoryDetail(it.year, it.term) }.orEmpty()
                if (detailHtml.isNotBlank()) mapOf(selectedKey to parser.parseHistoryDetail(detailHtml)) else emptyMap()
            } else emptyMap()
            _uiState.update {
                it.copy(
                    current = snapshot?.current?.takeIf { result -> result.items.isNotEmpty() },
                    history = snapshot?.history.orEmpty(),
                    selectedHistoryKey = selectedKey,
                    historyDetails = details,
                    standards = standards,
                    selectedStandardKey = standards.firstOrNull()?.key ?: it.selectedStandardKey
                )
            }
        }
    }

    private fun refreshLatest() {
        val cookie = store.getSession()
        if (cookie.isBlank()) {
            requestCaptcha("请先登录体测平台")
            return
        }
        viewModelScope.launch {
            var loadHistoryAfterRefresh = false
            _uiState.update { it.copy(isRefreshing = true, message = "正在刷新体测成绩...") }
            try {
                val result = service.refresh(cookie)
                if (result.success) {
                    updateSession(result)
                    applySnapshot(result)
                    loadHistoryAfterRefresh = _uiState.value.activeTab == FitnessTab.HISTORY
                    showMessage("体测成绩已更新")
                } else {
                    handleApiFailure(result)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage(networkError(error), 3500)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
            if (loadHistoryAfterRefresh) ensureSelectedHistoryLoaded()
        }
    }

    private fun refreshSelectedHistory() {
        val selected = _uiState.value.selectedHistory
        if (selected == null) {
            refreshLatest()
            return
        }
        loadHistoryDetail(selected, force = true)
    }

    private fun ensureSelectedHistoryLoaded() {
        val selected = _uiState.value.selectedHistory ?: return
        if (_uiState.value.historyDetails.containsKey(selected.key)) return
        val cached = store.getHistoryDetail(selected.year, selected.term)
        if (cached.isNotBlank()) {
            viewModelScope.launch(Dispatchers.Default) {
                val parsed = parser.parseHistoryDetail(cached)
                _uiState.update { it.copy(historyDetails = it.historyDetails + (selected.key to parsed)) }
            }
        } else {
            loadHistoryDetail(selected, force = false)
        }
    }

    private fun loadHistoryDetail(record: FitnessHistoryRecord, force: Boolean) {
        if (!force && _uiState.value.historyDetails.containsKey(record.key)) return
        val cookie = store.getSession()
        if (cookie.isBlank()) {
            requestCaptcha("请先登录体测平台")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = "正在获取历年体测成绩...") }
            try {
                val response = service.getHistoryDetail(cookie, record.year, record.term)
                if (response.success && response.detailHtml.isNotBlank()) {
                    updateSession(response)
                    store.saveHistoryDetail(record.year, record.term, response.detailHtml)
                    val detail = withContext(Dispatchers.Default) { parser.parseHistoryDetail(response.detailHtml) }
                    _uiState.update { it.copy(historyDetails = it.historyDetails + (record.key to detail)) }
                    showMessage("历年体测成绩已更新")
                } else {
                    handleApiFailure(response)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage(networkError(error), 3500)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun loadStandard(force: Boolean) {
        if (!force && _uiState.value.standards.isNotEmpty()) return
        val cookie = store.getSession()
        if (cookie.isBlank()) {
            requestCaptcha("请先登录体测平台")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = "正在获取评分标准...") }
            try {
                val response = service.getStandard(cookie)
                if (response.success && response.standardHtml.isNotBlank()) {
                    val tables = withContext(Dispatchers.Default) { parser.parseStandard(response.standardHtml) }
                    if (tables.isEmpty()) {
                        showMessage("未解析到评分标准", 3500)
                    } else {
                        updateSession(response)
                        store.saveStandard(response.standardHtml)
                        _uiState.update {
                            it.copy(
                                standards = tables,
                                selectedStandardKey = tables.firstOrNull { table -> table.key == it.selectedStandardKey }?.key
                                    ?: tables.first().key
                            )
                        }
                        showMessage("评分标准已更新", 1800)
                    }
                } else {
                    handleApiFailure(response)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                showMessage(networkError(error), 3500)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun requestCaptcha(message: String = "", preserveError: Boolean = false) {
        val requestId = ++captchaRequestId
        _uiState.update {
            it.copy(
                showLoginDialog = true,
                isRefreshing = true,
                message = message,
                loginError = if (preserveError) it.loginError else ""
            )
        }
        viewModelScope.launch {
            try {
                val response = service.getCaptcha(store.getSession())
                if (requestId != captchaRequestId) return@launch
                if (!response.success) {
                    _uiState.update { it.copy(loginError = response.message.ifBlank { "获取验证码失败" }) }
                } else {
                    captchaCookie = response.fitnessCookie
                    _uiState.update { it.copy(captchaImage = response.captchaImage) }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (requestId == captchaRequestId) {
                    _uiState.update { it.copy(loginError = networkError(error)) }
                }
            } finally {
                if (requestId == captchaRequestId) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun applySnapshot(response: FitnessApiResponse) {
        if (response.currentHtml.isBlank() && response.historyHtml.isBlank()) return
        store.saveSnapshot(response.currentHtml, response.historyHtml)
        val snapshot = withContext(Dispatchers.Default) {
            parser.parseSnapshot(response.currentHtml, response.historyHtml)
        }
        val currentSelected = _uiState.value.selectedHistoryKey
        val selectedKey = snapshot.history.firstOrNull { it.key == currentSelected }?.key
            ?: snapshot.history.firstOrNull()?.key.orEmpty()
        _uiState.update {
            it.copy(
                current = snapshot.current.takeIf { result -> result.items.isNotEmpty() },
                history = snapshot.history,
                selectedHistoryKey = selectedKey
            )
        }
    }

    private fun updateSession(response: FitnessApiResponse) {
        if (response.fitnessCookie.isNotBlank()) store.saveSession(response.fitnessCookie)
    }

    private fun handleApiFailure(response: FitnessApiResponse) {
        if (response.code == "FITNESS_SESSION_EXPIRED") {
            store.clearSession()
            requestCaptcha("登录态已过期，请重新登录")
        } else {
            showMessage(response.message.ifBlank { "体测服务暂时不可用，请稍后重试" }, 3500)
        }
    }

    private fun loginError(response: FitnessApiResponse): String {
        val text = response.message
        return when {
            response.code == "FITNESS_CAPTCHA_REQUIRED" || text.contains("验证码", true) -> "验证码错误，请重新输入"
            response.code == "FITNESS_CREDENTIALS_REQUIRED" -> "请输入学号和密码"
            response.code == "FITNESS_LOGIN_FAILED" || text.contains("密码", true) || text.contains("账号", true) ->
                "学号或体测平台密码错误，请检查后重试"
            else -> text.ifBlank { "登录失败，请稍后重试" }
        }
    }

    private fun networkError(error: Exception): String = when {
        error.message?.contains("服务器错误") == true -> error.message.orEmpty()
        else -> "体测服务暂时不可用，请稍后重试"
    }

    private fun showMessage(message: String, duration: Long = 2600) {
        messageJob?.cancel()
        _uiState.update { it.copy(message = message) }
        messageJob = viewModelScope.launch {
            delay(duration)
            _uiState.update { it.copy(message = "") }
        }
    }
}

class FitnessScoreViewModelFactory(
    private val service: FitnessApiService,
    private val store: FitnessStore,
    private val parser: FitnessParser = FitnessParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FitnessScoreViewModel(service, store, parser) as T
}

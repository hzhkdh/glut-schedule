package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.FitnessHistoryRecord
import com.glut.schedule.data.model.FitnessResult
import com.glut.schedule.data.model.FitnessStandardTable
import com.glut.schedule.service.fitness.FitnessApiResponse
import com.glut.schedule.service.fitness.FitnessGateway
import com.glut.schedule.service.fitness.FitnessStorage
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
    private val service: FitnessGateway,
    private val store: FitnessStorage,
    private val parser: FitnessParser = FitnessParser()
) : ViewModel() {
    private enum class FullLoadStatus { COMPLETE, PARTIAL, SESSION_EXPIRED }

    private val _uiState = MutableStateFlow(
        FitnessScoreUiState(
            username = store.getUsername(),
            password = store.getPassword()
        )
    )
    val uiState: StateFlow<FitnessScoreUiState> = _uiState.asStateFlow()

    private var messageJob: Job? = null
    private var captchaCookie = ""
    private var captchaLoginKey = ""
    private var captchaRequestId = 0
    @Volatile private var operationEpoch = 0

    init {
        loadCache()
    }

    fun selectTab(tab: FitnessTab) {
        _uiState.update { it.copy(activeTab = tab, message = "") }
        if (_uiState.value.isRefreshing) return
        when (tab) {
            FitnessTab.HISTORY -> ensureSelectedHistoryLoaded()
            FitnessTab.STANDARD -> if (_uiState.value.standards.isEmpty() && store.getSession().isNotBlank()) {
                loadStandard(false)
            }
            FitnessTab.LATEST -> Unit
        }
    }

    fun selectHistory(key: String) {
        if (_uiState.value.selectedHistoryKey == key) return
        _uiState.update { it.copy(selectedHistoryKey = key, message = "") }
        if (_uiState.value.isRefreshing) return
        ensureSelectedHistoryLoaded()
    }

    fun selectStandard(key: String) {
        _uiState.update { it.copy(selectedStandardKey = key) }
    }

    fun updateUsername(value: String) = _uiState.update { it.copy(username = value, loginError = "") }
    fun updatePassword(value: String) = _uiState.update { it.copy(password = value, loginError = "") }
    fun updateCaptcha(value: String) = _uiState.update { it.copy(captcha = value, loginError = "") }

    fun dismissLogin() {
        captchaRequestId++
        captchaCookie = ""
        captchaLoginKey = ""
        _uiState.update { it.copy(showLoginDialog = false, loginError = "", captcha = "", message = "") }
    }

    fun clearData() {
        messageJob?.cancel()
        captchaRequestId++
        captchaCookie = ""
        captchaLoginKey = ""
        operationEpoch++
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
        val epoch = ++operationEpoch
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, loginError = "") }
            try {
                val result = service.login(username, password, captcha, captchaCookie, captchaLoginKey)
                if (epoch != operationEpoch) return@launch
                if (result.success) {
                    store.clearAccountCache()
                    store.saveCredentials(username, password)
                    updateSession(result)
                    applySnapshot(result, epoch, clearHistoryDetails = true)
                    if (epoch != operationEpoch) return@launch
                    _uiState.update {
                        it.copy(showLoginDialog = false, captcha = "", captchaImage = "", loginError = "")
                    }
                    captchaRequestId++
                    captchaCookie = ""
                    captchaLoginKey = ""
                    val fullLoadStatus = loadAllAfterLogin(epoch)
                    if (epoch != operationEpoch) return@launch
                    when (fullLoadStatus) {
                        FullLoadStatus.COMPLETE -> showMessage("体测数据已全部更新")
                        FullLoadStatus.PARTIAL -> showMessage("主要数据已更新，部分历年详情或评分标准暂时无法加载", 4200)
                        FullLoadStatus.SESSION_EXPIRED -> requestCaptcha("登录态已失效，请重新登录")
                    }
                } else {
                    _uiState.update { it.copy(loginError = loginError(result), captcha = "") }
                    requestCaptcha(preserveError = true)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch != operationEpoch) return@launch
                _uiState.update { it.copy(loginError = networkError(error), captcha = "") }
                requestCaptcha(preserveError = true)
            } finally {
                if (epoch == operationEpoch) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun refresh() {
        if (_uiState.value.isRefreshing) return
        messageJob?.cancel()
        _uiState.update { it.copy(message = "") }
        if (_uiState.value.activeTab != FitnessTab.LATEST && store.getSession().isBlank()) {
            showMessage("请先在最新成绩页登录体测平台", 3500)
            return
        }
        when (_uiState.value.activeTab) {
            FitnessTab.LATEST -> refreshLatest()
            FitnessTab.HISTORY -> refreshSelectedHistory()
            FitnessTab.STANDARD -> loadStandard(true)
        }
    }

    private fun loadCache() {
        val epoch = operationEpoch
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
            _uiState.update { state ->
                if (epoch != operationEpoch) state else state.copy(
                    current = snapshot?.current?.takeIf { result -> result.items.isNotEmpty() },
                    history = snapshot?.history.orEmpty(),
                    selectedHistoryKey = selectedKey,
                    historyDetails = details,
                    standards = standards,
                    selectedStandardKey = standards.firstOrNull()?.key ?: state.selectedStandardKey
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
        val epoch = ++operationEpoch
        viewModelScope.launch {
            var loadHistoryAfterRefresh = false
            _uiState.update { it.copy(isRefreshing = true, message = "正在刷新体测成绩...") }
            try {
                val result = service.refresh(cookie)
                if (epoch != operationEpoch) return@launch
                if (result.success) {
                    updateSession(result)
                    applySnapshot(result, epoch)
                    if (epoch != operationEpoch) return@launch
                    loadHistoryAfterRefresh = _uiState.value.activeTab == FitnessTab.HISTORY
                    showMessage("体测成绩已更新")
                } else {
                    handleApiFailure(result)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch != operationEpoch) return@launch
                showMessage(networkError(error), 3500)
            } finally {
                if (epoch == operationEpoch) _uiState.update { it.copy(isRefreshing = false) }
            }
            if (epoch == operationEpoch && loadHistoryAfterRefresh) ensureSelectedHistoryLoaded()
        }
    }

    private fun refreshSelectedHistory() {
        val selected = _uiState.value.selectedHistory
        if (selected == null) {
            refreshLatest()
            return
        }
        if (selected.detailRequest == null) {
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
            val epoch = operationEpoch
            viewModelScope.launch(Dispatchers.Default) {
                val parsed = parser.parseHistoryDetail(cached)
                if (epoch == operationEpoch) {
                    _uiState.update { it.copy(historyDetails = it.historyDetails + (selected.key to parsed)) }
                }
            }
        } else {
            loadHistoryDetail(selected, force = false)
        }
    }

    private fun loadHistoryDetail(record: FitnessHistoryRecord, force: Boolean) {
        if (!force && _uiState.value.historyDetails.containsKey(record.key)) return
        val detailRequest = record.detailRequest
        if (detailRequest == null) {
            showMessage("历年记录缺少查询信息，请先刷新体测成绩", 3500)
            return
        }
        val cookie = store.getSession()
        if (cookie.isBlank()) {
            showMessage("请先在最新成绩页登录体测平台", 3500)
            return
        }
        val epoch = ++operationEpoch
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = "正在获取历年体测成绩...") }
            try {
                val response = service.getHistoryDetail(cookie, detailRequest)
                if (epoch != operationEpoch) return@launch
                if (response.success && response.detailHtml.isNotBlank()) {
                    updateSession(response)
                    store.saveHistoryDetail(record.year, record.term, response.detailHtml)
                    val detail = withContext(Dispatchers.Default) { parser.parseHistoryDetail(response.detailHtml) }
                    if (epoch != operationEpoch) return@launch
                    _uiState.update { it.copy(historyDetails = it.historyDetails + (record.key to detail)) }
                    showMessage("历年体测成绩已更新")
                } else {
                    handleApiFailure(response)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (epoch != operationEpoch) return@launch
                showMessage(networkError(error), 3500)
            } finally {
                if (epoch == operationEpoch) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun loadStandard(force: Boolean) {
        if (!force && _uiState.value.standards.isNotEmpty()) return
        val cookie = store.getSession()
        if (cookie.isBlank()) {
            showMessage("请先在最新成绩页登录体测平台", 3500)
            return
        }
        val epoch = ++operationEpoch
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = "正在获取评分标准...") }
            try {
                val response = service.getStandard(cookie)
                if (epoch != operationEpoch) return@launch
                if (response.success && response.standardHtml.isNotBlank()) {
                    val tables = withContext(Dispatchers.Default) { parser.parseStandard(response.standardHtml) }
                    if (epoch != operationEpoch) return@launch
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
                if (epoch != operationEpoch) return@launch
                showMessage(networkError(error), 3500)
            } finally {
                if (epoch == operationEpoch) _uiState.update { it.copy(isRefreshing = false) }
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
                    captchaLoginKey = response.loginKey
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

    private suspend fun loadAllAfterLogin(epoch: Int): FullLoadStatus {
        var partial = false
        for (record in _uiState.value.history) {
            if (epoch != operationEpoch) return FullLoadStatus.PARTIAL
            val request = record.detailRequest
            if (request == null) {
                partial = true
                continue
            }
            val response = try {
                service.getHistoryDetail(store.getSession(), request)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                partial = true
                continue
            }
            if (epoch != operationEpoch) return FullLoadStatus.PARTIAL
            if (response.code == "FITNESS_SESSION_EXPIRED") {
                store.clearSession()
                return FullLoadStatus.SESSION_EXPIRED
            }
            updateSession(response)
            if (!response.success || response.detailHtml.isBlank()) {
                partial = true
                continue
            }
            val detail = withContext(Dispatchers.Default) {
                parser.parseHistoryDetail(response.detailHtml)
            }
            if (epoch != operationEpoch) return FullLoadStatus.PARTIAL
            store.saveHistoryDetail(record.year, record.term, response.detailHtml)
            _uiState.update { state ->
                state.copy(historyDetails = state.historyDetails + (record.key to detail))
            }
        }

        if (epoch != operationEpoch) return FullLoadStatus.PARTIAL
        val standardResponse = try {
            service.getStandard(store.getSession())
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return FullLoadStatus.PARTIAL
        }
        if (epoch != operationEpoch) return FullLoadStatus.PARTIAL
        if (standardResponse.code == "FITNESS_SESSION_EXPIRED") {
            store.clearSession()
            return FullLoadStatus.SESSION_EXPIRED
        }
        updateSession(standardResponse)
        if (!standardResponse.success || standardResponse.standardHtml.isBlank()) {
            return FullLoadStatus.PARTIAL
        }
        val tables = withContext(Dispatchers.Default) {
            parser.parseStandard(standardResponse.standardHtml)
        }
        if (epoch != operationEpoch) return FullLoadStatus.PARTIAL
        if (tables.isEmpty()) return FullLoadStatus.PARTIAL
        store.saveStandard(standardResponse.standardHtml)
        _uiState.update { state ->
            state.copy(
                standards = tables,
                selectedStandardKey = tables.firstOrNull { it.key == state.selectedStandardKey }?.key
                    ?: tables.first().key
            )
        }
        return if (partial) FullLoadStatus.PARTIAL else FullLoadStatus.COMPLETE
    }

    private suspend fun applySnapshot(
        response: FitnessApiResponse,
        epoch: Int,
        clearHistoryDetails: Boolean = false
    ) {
        if (epoch != operationEpoch) return
        if (response.currentHtml.isBlank() && response.historyHtml.isBlank()) {
            if (clearHistoryDetails) {
                _uiState.update {
                    it.copy(current = null, history = emptyList(), historyDetails = emptyMap(), selectedHistoryKey = "")
                }
            }
            return
        }
        val snapshot = withContext(Dispatchers.Default) {
            parser.parseSnapshot(response.currentHtml, response.historyHtml)
        }
        if (epoch != operationEpoch) return
        store.saveSnapshot(response.currentHtml, response.historyHtml)
        val currentSelected = _uiState.value.selectedHistoryKey
        val selectedKey = snapshot.history.firstOrNull { it.key == currentSelected }?.key
            ?: snapshot.history.firstOrNull()?.key.orEmpty()
        _uiState.update {
            it.copy(
                current = snapshot.current.takeIf { result -> result.items.isNotEmpty() },
                history = snapshot.history,
                selectedHistoryKey = selectedKey,
                historyDetails = if (clearHistoryDetails) emptyMap() else it.historyDetails
            )
        }
    }

    private fun updateSession(response: FitnessApiResponse) {
        if (response.fitnessCookie.isNotBlank()) store.saveSession(response.fitnessCookie)
    }

    private fun handleApiFailure(response: FitnessApiResponse) {
        if (response.code == "FITNESS_SESSION_EXPIRED") {
            store.clearSession()
            _uiState.update { it.copy(activeTab = FitnessTab.LATEST) }
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
    private val service: FitnessGateway,
    private val store: FitnessStorage,
    private val parser: FitnessParser = FitnessParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        FitnessScoreViewModel(service, store, parser) as T
}

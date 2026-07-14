package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.CachedFinancePayload
import com.glut.schedule.data.model.FinanceCredentials
import com.glut.schedule.data.model.FinanceGroup
import com.glut.schedule.data.model.FinanceItem
import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.data.model.FinancePayload
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.finance.FinanceFailure
import com.glut.schedule.service.finance.FinanceGateway
import com.glut.schedule.service.finance.FinanceResponse
import com.glut.schedule.service.finance.FinanceStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class FinanceLoginState(
    val visible: Boolean = false,
    val username: String = "",
    val password: String = "",
    val captcha: String = "",
    val captchaImage: String = "",
    val cookie: String = "",
    val error: String = "",
    val passwordVisible: Boolean = false
)

data class FinanceUiState(
    val campusUnsupported: Boolean = false,
    val group: FinanceGroup = FinanceGroup.OVERVIEW,
    val module: FinanceModule = FinanceModule.OVERVIEW,
    val payloads: Map<FinanceModule, CachedFinancePayload> = emptyMap(),
    val isRefreshing: Boolean = false,
    val moneyVisible: Boolean = false,
    val message: String = "",
    val login: FinanceLoginState = FinanceLoginState(),
    val selectedItem: FinanceItem? = null,
    val ticketImage: String = ""
) {
    val activePayload: FinancePayload?
        get() = payloads[module]?.payload ?: if (module == FinanceModule.PENDING) {
            val overview = payloads[FinanceModule.OVERVIEW]?.payload as? FinancePayload.Overview
            overview?.let { FinancePayload.Items(it.value.pendingItems) }
        } else null
    val activeSavedAt: Long
        get() = payloads[module]?.savedAt
            ?: if (module == FinanceModule.PENDING) payloads[FinanceModule.OVERVIEW]?.savedAt ?: 0L else 0L
}

class FinanceViewModel(
    private val gateway: FinanceGateway,
    private val store: FinanceStorage,
    campus: CampusType
) : ViewModel() {
    private val credentials = store.credentials()
    private val cached = FinanceModule.entries.mapNotNull { module -> store.module(module)?.let { module to it } }.toMap()
    private val _uiState = MutableStateFlow(
        FinanceUiState(
            campusUnsupported = campus == CampusType.NANNING,
            payloads = cached,
            login = FinanceLoginState(username = credentials.username, password = credentials.password)
        )
    )
    val uiState: StateFlow<FinanceUiState> = _uiState.asStateFlow()

    private sealed interface PendingAction {
        data class Fetch(val module: FinanceModule, val page: Int, val append: Boolean) : PendingAction
        data class Ticket(val item: FinanceItem, val receiptNo: String) : PendingAction
    }

    private var pendingAction: PendingAction? = null
    private var generation = 0
    private var captchaRequestId = 0

    fun clearData() {
        generation++
        captchaRequestId++
        pendingAction = null
        store.clearAll()
        _uiState.value = FinanceUiState(campusUnsupported = _uiState.value.campusUnsupported)
    }

    fun selectGroup(group: FinanceGroup) = selectModule(FinanceModule.defaultFor(group))

    fun selectModule(module: FinanceModule) {
        _uiState.update { it.copy(group = module.group, module = module, message = "", selectedItem = null, ticketImage = "") }
    }

    fun refresh() {
        if (_uiState.value.campusUnsupported || _uiState.value.isRefreshing) return
        val module = _uiState.value.module
        val action = PendingAction.Fetch(module, 1, false)
        pendingAction = action
        val cookie = store.sessionCookie()
        if (cookie.isBlank()) requestCaptcha("请先登录财务平台")
        else viewModelScope.launch { fetch(action, cookie) }
    }

    fun loadMore() {
        val payload = _uiState.value.activePayload as? FinancePayload.Items ?: return
        if (!payload.hasMore || _uiState.value.isRefreshing) return
        val cookie = store.sessionCookie()
        val action = PendingAction.Fetch(_uiState.value.module, payload.page + 1, true)
        pendingAction = action
        if (cookie.isBlank()) {
            requestCaptcha("登录态已失效，请重新登录")
            return
        }
        viewModelScope.launch { fetch(action, cookie) }
    }

    fun showLogin() {
        pendingAction = PendingAction.Fetch(_uiState.value.module, 1, false)
        requestCaptcha()
    }

    fun dismissLogin() = _uiState.update { it.copy(login = it.login.copy(visible = false, error = "", captcha = ""), isRefreshing = false) }
    fun updateUsername(value: String) = _uiState.update { it.copy(login = it.login.copy(username = value, error = "")) }
    fun updatePassword(value: String) = _uiState.update { it.copy(login = it.login.copy(password = value, error = "")) }
    fun updateCaptcha(value: String) = _uiState.update { it.copy(login = it.login.copy(captcha = value, error = "")) }
    fun togglePasswordVisibility() = _uiState.update { it.copy(login = it.login.copy(passwordVisible = !it.login.passwordVisible)) }
    fun toggleMoneyVisibility() = _uiState.update { it.copy(moneyVisible = !it.moneyVisible) }
    fun hideMoney() = _uiState.update { if (it.moneyVisible) it.copy(moneyVisible = false) else it }
    fun refreshCaptcha() = requestCaptcha(preserveError = true)

    fun login() {
        if (_uiState.value.isRefreshing) return
        val login = _uiState.value.login
        val validation = when {
            login.username.isBlank() -> "请输入学号"
            login.password.isBlank() -> "请输入财务平台密码"
            login.captcha.isBlank() -> "请输入验证码"
            else -> ""
        }
        if (validation.isNotBlank()) {
            _uiState.update { it.copy(login = it.login.copy(error = validation)) }
            return
        }
        viewModelScope.launch {
            val requestGeneration = generation
            _uiState.update { it.copy(isRefreshing = true, login = it.login.copy(error = "")) }
            try {
                val result = gateway.login(login.username, login.password, login.captcha, login.cookie)
                if (requestGeneration != generation) return@launch
                store.saveCredentials(FinanceCredentials(login.username, login.password))
                store.saveSessionCookie(result.cookie)
                _uiState.update { it.copy(isRefreshing = false, login = it.login.copy(visible = false, captcha = "", captchaImage = "", error = "")) }
                val resume = pendingAction
                var resumedModule: FinanceModule? = null
                var resumeFailures = 0
                when (resume) {
                    is PendingAction.Fetch -> {
                        var outcome = fetch(resume, result.cookie)
                        if (outcome == FetchOutcome.Failure) {
                            outcome = fetch(resume, store.sessionCookie().ifBlank { result.cookie })
                        }
                        when (outcome) {
                        FetchOutcome.Success -> resumedModule = resume.module
                        FetchOutcome.LoginRequired, FetchOutcome.Stale -> return@launch
                            FetchOutcome.Failure -> {
                                resumedModule = resume.module
                                resumeFailures = 1
                            }
                        }
                    }
                    is PendingAction.Ticket -> {
                        fetchTicket(resume, result.cookie)
                        if (_uiState.value.login.visible) return@launch
                    }
                    null -> Unit
                }
                if (requestGeneration != generation) return@launch
                preloadAfterLogin(store.sessionCookie().ifBlank { result.cookie }, resumedModule, resumeFailures, requestGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: FinanceFailure.CaptchaInvalid) {
                if (requestGeneration != generation) return@launch
                _uiState.update { it.copy(isRefreshing = false, login = it.login.copy(error = error.message.orEmpty(), captcha = "")) }
                requestCaptcha(preserveError = true)
            } catch (error: Exception) {
                if (requestGeneration != generation) return@launch
                _uiState.update { it.copy(isRefreshing = false, login = it.login.copy(error = errorMessage(error), captcha = "")) }
            }
        }
    }

    fun selectItem(item: FinanceItem?) = _uiState.update { it.copy(selectedItem = item, ticketImage = "") }

    fun loadTicketImage(item: FinanceItem, receiptNo: String) {
        if (_uiState.value.isRefreshing || !item.canPreview) return
        val action = PendingAction.Ticket(item, receiptNo)
        pendingAction = action
        val cookie = store.sessionCookie()
        if (cookie.isBlank()) return requestCaptcha("登录态已失效，请重新登录")
        viewModelScope.launch { fetchTicket(action, cookie) }
    }

    private suspend fun fetchTicket(action: PendingAction.Ticket, cookie: String) {
        val requestGeneration = generation
        var handedOffToLogin = false
        _uiState.update { it.copy(isRefreshing = true, message = "正在获取电子票据...") }
        try {
                val result = gateway.ticketImage(cookie, action.item.chargeId, action.receiptNo)
                if (requestGeneration != generation) return
                if (result.cookie.isNotBlank()) store.saveSessionCookie(result.cookie)
                val image = (result.payload as? FinancePayload.TicketImage)?.dataUrl.orEmpty()
                _uiState.update { it.copy(ticketImage = image, message = if (image.isBlank()) "未获取到票据图片" else "") }
                if (pendingAction == action) pendingAction = null
            } catch (error: Exception) {
                if (requestGeneration != generation) return
                handedOffToLogin = handleFailure(error)
            } finally {
                if (requestGeneration == generation && !handedOffToLogin) _uiState.update { it.copy(isRefreshing = false) }
            }
    }

    private suspend fun fetch(action: PendingAction.Fetch, cookie: String): FetchOutcome {
        val (module, page, append) = action
        val requestGeneration = generation
        var handedOffToLogin = false
        var outcome = FetchOutcome.Failure
        _uiState.update { it.copy(isRefreshing = true, message = if (page > 1) "正在加载更多..." else "正在刷新${module.label}...") }
        try {
            val result = gateway.fetch(module, cookie, page, PAGE_SIZE)
            if (requestGeneration != generation) return FetchOutcome.Stale
            if (result.cookie.isNotBlank()) store.saveSessionCookie(result.cookie)
            result.payload?.let { incoming ->
                val payload = if (append && incoming is FinancePayload.Items) {
                    val previous = _uiState.value.payloads[module]?.payload as? FinancePayload.Items
                    incoming.copy(values = previous.orEmptyItems() + incoming.values)
                } else incoming
                val cached = CachedFinancePayload(payload, System.currentTimeMillis())
                store.saveModule(module, cached)
                _uiState.update { it.copy(payloads = it.payloads + (module to cached), message = "${module.label}已更新") }
                if (pendingAction == action) pendingAction = null
                outcome = FetchOutcome.Success
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (requestGeneration != generation) return FetchOutcome.Stale
            handedOffToLogin = handleFailure(error)
            outcome = if (handedOffToLogin) FetchOutcome.LoginRequired else FetchOutcome.Failure
        } finally {
            if (requestGeneration == generation && !handedOffToLogin) _uiState.update { it.copy(isRefreshing = false) }
        }
        return outcome
    }

    private suspend fun preloadAfterLogin(cookie: String, resumedModule: FinanceModule?, initialFailures: Int, requestGeneration: Int) {
        if (requestGeneration != generation) return
        val modules = FinanceModule.entries.filter { it != FinanceModule.PENDING && it != resumedModule }.toMutableList()
        var authoritativeCookie = cookie
        var failures = initialFailures
        _uiState.update { it.copy(isRefreshing = true, message = "登录成功，正在同步财务数据...") }
        if (FinanceModule.OVERVIEW in modules) {
            modules.remove(FinanceModule.OVERVIEW)
            try {
                val overview = gateway.fetch(FinanceModule.OVERVIEW, authoritativeCookie, 1, PAGE_SIZE)
                if (requestGeneration != generation) return
                if (overview.cookie.isNotBlank()) {
                    authoritativeCookie = overview.cookie
                    store.saveSessionCookie(authoritativeCookie)
                }
                overview.payload?.let { payload ->
                    val cached = CachedFinancePayload(payload, System.currentTimeMillis())
                    store.saveModule(FinanceModule.OVERVIEW, cached)
                    _uiState.update { it.copy(payloads = it.payloads + (FinanceModule.OVERVIEW to cached)) }
                } ?: run { failures++ }
            } catch (error: CancellationException) {
                throw error
            } catch (error: FinanceFailure.SessionExpired) {
                if (requestGeneration != generation) return
                handlePreloadSessionExpired()
                return
            } catch (error: Exception) {
                if (requestGeneration != generation) return
                failures++
            }
        }
        if (requestGeneration != generation) return
        if (modules.isEmpty()) {
            _uiState.update { it.copy(message = preloadMessage(failures), isRefreshing = false) }
            return
        }
        val results = coroutineScope {
            modules.map { module ->
                async {
                    try {
                        PreloadResult.Success(module, gateway.fetch(module, authoritativeCookie, 1, PAGE_SIZE))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        PreloadResult.Failure(module, error)
                    }
                }
            }.awaitAll()
        }
        if (requestGeneration != generation) return
        if (results.filterIsInstance<PreloadResult.Failure>().any { it.error is FinanceFailure.SessionExpired }) {
            handlePreloadSessionExpired()
            return
        }
        results.forEach { result ->
            when (result) {
                is PreloadResult.Success -> {
                    result.response.payload?.let { payload ->
                        val cached = CachedFinancePayload(payload, System.currentTimeMillis())
                        store.saveModule(result.module, cached)
                        _uiState.update { it.copy(payloads = it.payloads + (result.module to cached)) }
                    } ?: run { failures++ }
                }
                is PreloadResult.Failure -> failures++
            }
        }
        _uiState.update {
            it.copy(
                isRefreshing = false,
                message = preloadMessage(failures)
            )
        }
    }

    private fun handlePreloadSessionExpired() {
        store.clearSession()
        requestCaptcha("财务登录已失效，请重新登录")
    }

    private fun preloadMessage(failures: Int): String =
        if (failures == 0) "登录成功，财务数据已全部更新" else "登录成功，部分财务数据暂未同步"

    private fun handleFailure(error: Exception): Boolean {
        if (error is FinanceFailure.SessionExpired) {
            store.clearSession()
            requestCaptcha("财务登录已失效，请重新登录")
            return true
        } else {
            _uiState.update { it.copy(message = errorMessage(error)) }
        }
        return false
    }

    private fun requestCaptcha(message: String = "", preserveError: Boolean = false) {
        if (_uiState.value.campusUnsupported) return
        val requestGeneration = generation
        val requestId = ++captchaRequestId
        _uiState.update {
            it.copy(
                isRefreshing = true,
                message = message,
                login = it.login.copy(visible = true, error = if (preserveError) it.login.error else "", captcha = "", captchaImage = "", cookie = "")
            )
        }
        viewModelScope.launch {
            try {
                val result = gateway.captcha()
                if (requestGeneration != generation || requestId != captchaRequestId) return@launch
                _uiState.update { it.copy(login = it.login.copy(captchaImage = result.imageDataUrl, cookie = result.cookie)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (requestGeneration != generation || requestId != captchaRequestId) return@launch
                _uiState.update { it.copy(login = it.login.copy(error = errorMessage(error))) }
            } finally {
                if (requestGeneration == generation && requestId == captchaRequestId) _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is FinanceFailure -> error.message.orEmpty()
        else -> "财务服务暂时不可用，请稍后重试"
    }

    private sealed interface PreloadResult {
        data class Success(val module: FinanceModule, val response: FinanceResponse) : PreloadResult
        data class Failure(val module: FinanceModule, val error: Exception) : PreloadResult
    }

    private enum class FetchOutcome { Success, Failure, LoginRequired, Stale }

    companion object { private const val PAGE_SIZE = 20 }
}

class FinanceViewModelRegistry {
    private val viewModels = linkedSetOf<FinanceViewModel>()

    fun register(viewModel: FinanceViewModel): FinanceViewModel = viewModel.also(viewModels::add)

    fun clearAll() {
        viewModels.forEach(FinanceViewModel::clearData)
    }
}

private fun FinancePayload.Items?.orEmptyItems(): List<FinanceItem> = this?.values.orEmpty()

class FinanceViewModelFactory(
    private val gateway: FinanceGateway,
    private val store: FinanceStorage,
    private val campus: CampusType
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = FinanceViewModel(gateway, store, campus) as T
}

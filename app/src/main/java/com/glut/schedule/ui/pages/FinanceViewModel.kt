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
import com.glut.schedule.service.finance.FinanceStorage
import kotlinx.coroutines.CancellationException
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
    val message: String = "",
    val login: FinanceLoginState = FinanceLoginState(),
    val selectedItem: FinanceItem? = null,
    val ticketImage: String = ""
) {
    val activePayload: FinancePayload? get() = payloads[module]?.payload
    val activeSavedAt: Long get() = payloads[module]?.savedAt ?: 0L
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

    private var pendingRefresh: FinanceModule? = null

    fun selectGroup(group: FinanceGroup) = selectModule(FinanceModule.defaultFor(group))

    fun selectModule(module: FinanceModule) {
        _uiState.update { it.copy(group = module.group, module = module, message = "", selectedItem = null, ticketImage = "") }
    }

    fun refresh() {
        if (_uiState.value.campusUnsupported || _uiState.value.isRefreshing) return
        val module = _uiState.value.module
        pendingRefresh = module
        val cookie = store.sessionCookie()
        if (cookie.isBlank()) requestCaptcha("请先登录财务平台")
        else viewModelScope.launch { fetch(module, cookie, 1, append = false) }
    }

    fun loadMore() {
        val payload = _uiState.value.activePayload as? FinancePayload.Items ?: return
        if (!payload.hasMore || _uiState.value.isRefreshing) return
        val cookie = store.sessionCookie()
        if (cookie.isBlank()) {
            pendingRefresh = _uiState.value.module
            requestCaptcha("登录态已失效，请重新登录")
            return
        }
        viewModelScope.launch { fetch(_uiState.value.module, cookie, payload.page + 1, append = true) }
    }

    fun showLogin() {
        pendingRefresh = _uiState.value.module
        requestCaptcha()
    }

    fun dismissLogin() = _uiState.update { it.copy(login = it.login.copy(visible = false, error = "", captcha = ""), isRefreshing = false) }
    fun updateUsername(value: String) = _uiState.update { it.copy(login = it.login.copy(username = value, error = "")) }
    fun updatePassword(value: String) = _uiState.update { it.copy(login = it.login.copy(password = value, error = "")) }
    fun updateCaptcha(value: String) = _uiState.update { it.copy(login = it.login.copy(captcha = value, error = "")) }
    fun togglePasswordVisibility() = _uiState.update { it.copy(login = it.login.copy(passwordVisible = !it.login.passwordVisible)) }
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
            _uiState.update { it.copy(isRefreshing = true, login = it.login.copy(error = "")) }
            try {
                val result = gateway.login(login.username, login.password, login.captcha, login.cookie)
                store.saveCredentials(FinanceCredentials(login.username, login.password))
                store.saveSessionCookie(result.cookie)
                _uiState.update { it.copy(isRefreshing = false, login = it.login.copy(visible = false, captcha = "", captchaImage = "", error = "")) }
                val resume = pendingRefresh
                if (resume != null) fetch(resume, result.cookie, 1, append = false)
            } catch (error: CancellationException) {
                throw error
            } catch (error: FinanceFailure.CaptchaInvalid) {
                _uiState.update { it.copy(isRefreshing = false, login = it.login.copy(error = error.message.orEmpty(), captcha = "")) }
                requestCaptcha(preserveError = true)
            } catch (error: Exception) {
                _uiState.update { it.copy(isRefreshing = false, login = it.login.copy(error = errorMessage(error), captcha = "")) }
            }
        }
    }

    fun selectItem(item: FinanceItem?) = _uiState.update { it.copy(selectedItem = item, ticketImage = "") }

    fun loadTicketImage(item: FinanceItem, receiptNo: String) {
        if (_uiState.value.isRefreshing || !item.canPreview) return
        val cookie = store.sessionCookie()
        if (cookie.isBlank()) return requestCaptcha("登录态已失效，请重新登录")
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, message = "正在获取电子票据...") }
            try {
                val result = gateway.ticketImage(cookie, item.chargeId, receiptNo)
                if (result.cookie.isNotBlank()) store.saveSessionCookie(result.cookie)
                val image = (result.payload as? FinancePayload.TicketImage)?.dataUrl.orEmpty()
                _uiState.update { it.copy(ticketImage = image, message = if (image.isBlank()) "未获取到票据图片" else "") }
            } catch (error: Exception) {
                handleFailure(error)
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private suspend fun fetch(module: FinanceModule, cookie: String, page: Int, append: Boolean) {
        _uiState.update { it.copy(isRefreshing = true, message = if (page > 1) "正在加载更多..." else "正在刷新${module.label}...") }
        try {
            val result = gateway.fetch(module, cookie, page, PAGE_SIZE)
            if (result.cookie.isNotBlank()) store.saveSessionCookie(result.cookie)
            val incoming = result.payload ?: return
            val payload = if (append && incoming is FinancePayload.Items) {
                val previous = _uiState.value.payloads[module]?.payload as? FinancePayload.Items
                incoming.copy(values = previous.orEmptyItems() + incoming.values)
            } else incoming
            val cached = CachedFinancePayload(payload, System.currentTimeMillis())
            store.saveModule(module, cached)
            _uiState.update { it.copy(payloads = it.payloads + (module to cached), message = "${module.label}已更新") }
            pendingRefresh = null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handleFailure(error)
        } finally {
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    private fun handleFailure(error: Exception) {
        if (error is FinanceFailure.SessionExpired) {
            store.clearSession()
            requestCaptcha("财务登录已失效，请重新登录")
        } else {
            _uiState.update { it.copy(message = errorMessage(error)) }
        }
    }

    private fun requestCaptcha(message: String = "", preserveError: Boolean = false) {
        if (_uiState.value.campusUnsupported) return
        _uiState.update {
            it.copy(
                isRefreshing = true,
                message = message,
                login = it.login.copy(visible = true, error = if (preserveError) it.login.error else "", captcha = "")
            )
        }
        viewModelScope.launch {
            try {
                val result = gateway.captcha()
                _uiState.update { it.copy(login = it.login.copy(captchaImage = result.imageDataUrl, cookie = result.cookie)) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(login = it.login.copy(error = errorMessage(error))) }
            } finally {
                _uiState.update { it.copy(isRefreshing = false) }
            }
        }
    }

    private fun errorMessage(error: Exception): String = when (error) {
        is FinanceFailure -> error.message.orEmpty()
        else -> "财务服务暂时不可用，请稍后重试"
    }

    companion object { private const val PAGE_SIZE = 20 }
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

package com.glut.schedule.service.academic

import android.graphics.Bitmap
import com.glut.schedule.data.settings.ImportSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 续期弹窗的界面状态。 */
sealed interface RenewalUiState {
    /** 没有续期在进行中。 */
    data object Idle : RenewalUiState

    /** 正在取验证码或正在提交。 */
    data class Busy(val source: ImportSource) : RenewalUiState

    /** 等用户输入。 */
    data class AwaitingCaptcha(
        val source: ImportSource,
        /** 取图失败时为 null，此时 [error] 一定有值。 */
        val captchaBitmap: Bitmap?,
        /**
         * 已保存的账号，用于预填；本地没有则为空串，由用户当场填。
         *
         * 本地没有账号是**常态而非边缘**：没勾「记住密码」时 `clearCredentials()` 把账号和密码
         * 一起清掉，而全新安装的复选状态就是按「本地有没有账号」复位的（默认不勾）。
         * 所以弹窗必须有账号输入框，否则这类用户永远登不进去。
         */
        val prefilledUsername: String,
        /** 已保存的密码，用于预填；本地没有则为空串，由用户当场填。 */
        val prefilledPassword: String,
        /** 上一次失败原因；首次为空。 */
        val error: String = ""
    ) : RenewalUiState
}

/**
 * 协调器需要写回的东西，仅此两样。
 *
 * 抽成接口而不是直接依赖 [AcademicSessionStore]：那个类要 `Context`，
 * JVM 单测里造不出来——而续期状态机恰恰是本功能最需要单测的部分。
 */
interface RenewalSessionWriter {
    suspend fun saveCookie(cookie: String)
    suspend fun saveCampusBaseUrl(url: String)
}

/**
 * 协调器需要读的东西：账号用于提交，密码用于预填弹窗。
 *
 * 与 [RenewalSessionWriter] 同理，抽接口是为了能在 JVM 单测里给出手写 fake。
 */
interface RenewalCredentialReader {
    fun getUsername(): String
    fun getPassword(): String
}

/**
 * 教务会话的续期门卫。
 *
 * 只服务**需要图形验证码的两个来源**（南宁、研究生）——它们无法静默补登录，
 * 会话一失效就只剩「去导入页重走一遍」这一条路。桂林照旧走 [silentLogin] 静默换票。
 *
 * 用法：菜单在**取数已经失败之后**调用 [ensureFreshSession]；返回 true 就用
 * `AcademicSessionStore` 里的新 cookie 重放一次那次取数，false 则保留已有缓存并提示用户。
 *
 * **刻意不做「cookie 还在就直接返回 true」的短路**：`shouldUseExistingAcademicCookie`
 * 只看 cookie 里有没有会话名，判不出「cookie 在、但服务端已经失效」，短路会让调用方
 * 拿着同一个死 cookie 再失败一次。
 *
 * **也刻意不设「续期成功后短期内不再弹」的抑制窗口**：每次调用都对应一次刚刚失败的取数，
 * 用户再刷新一次就该再弹一次，而不是拿一个可能又失效的会话去搪塞。
 */
class SessionRenewalCoordinator(
    private val sessionWriter: RenewalSessionWriter,
    private val credentialReader: RenewalCredentialReader,
    private val captchaLogin: CaptchaLoginSession,
    private val currentSource: suspend () -> ImportSource,
    private val silentLogin: suspend () -> AcademicLoginResult,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<RenewalUiState>(RenewalUiState.Idle)
    val state: StateFlow<RenewalUiState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 正在等用户操作的那次续期；没有则为 null。单飞的锚点。 */
    private var pending: CompletableDeferred<Boolean>? = null
    private var pendingSource: ImportSource? = null

    /** 上一次失败原因，用于取到新图后仍然把原因显示在弹窗里。 */
    private var lastError: String = ""

    /**
     * 重新建立一个可用的教务会话，成功返回 true。
     *
     * 并发调用单飞：第二个调用者 await 同一个结果，不会弹两次窗。
     */
    suspend fun ensureFreshSession(): Boolean {
        val source = currentSource()
        if (source != ImportSource.NANNING && source != ImportSource.GRADUATE) {
            // 桂林能静默换票，不需要验证码，也就不该弹窗。
            val result = silentLogin()
            if (result is AcademicLoginResult.Success) {
                sessionWriter.saveCookie(result.cookie)
                sessionWriter.saveCampusBaseUrl(result.campusBaseUrl)
                return true
            }
            return false
        }

        // 「看一眼有没有在飞的续期」与「没有就认领一个」必须在**同一个原子段**里完成：
        // 分开写的话，两个并发调用会都读到 null，于是各弹各的窗、各等各的
        // CompletableDeferred，单飞失效。
        val mine = CompletableDeferred<Boolean>()
        val inFlight = mutex.withLock {
            val current = pending
            if (current != null) {
                current
            } else {
                pending = mine
                pendingSource = source
                null
            }
        }
        inFlight?.let { return it.await() }

        fetchCaptcha(source, captchaLogin::requestCaptcha)
        return mine.await()
    }

    /** 用户在弹窗里点了确认。 */
    fun submit(captcha: String, username: String, password: String) {
        val source = pendingSource ?: return
        val state = _state.value as? RenewalUiState.AwaitingCaptcha ?: return
        if (captcha.isBlank()) {
            _state.value = state.copy(error = "请输入验证码")
            return
        }
        // 本地没存账号/密码时弹窗是空的，由用户当场填；填了就用填的那个。
        //
        // 账号这一项过去**只**从 credentialReader 取，用户无从补填——本地没存账号的用户
        // 会陷入「填完密码和验证码 → 后端拒绝 → 换一张图」的死循环，且提示是
        // 「验证码或密码错误」，看不出真正缺的是账号。所以这里必须用弹窗传上来的值。
        val effectiveUsername = username.ifBlank { state.prefilledUsername }
        if (effectiveUsername.isBlank()) {
            _state.value = state.copy(error = "请输入学号")
            return
        }
        val effectivePassword = password.ifBlank { state.prefilledPassword }
        if (effectivePassword.isBlank()) {
            _state.value = state.copy(error = "请输入密码")
            return
        }
        _state.value = RenewalUiState.Busy(source)
        scope.launch {
            val result = captchaLogin.submit(
                source = source,
                username = effectiveUsername.trim(),
                password = effectivePassword,
                captcha = captcha.trim()
            )
            val renewed = result.getOrNull()
            if (renewed == null) {
                lastError = failureMessage(result, "验证码或密码错误")
                // 输错就换一张图，但**不关窗**：用户还在原地，重输即可。
                fetchCaptcha(source, captchaLogin::refreshCaptcha)
                return@launch
            }
            sessionWriter.saveCookie(renewed.cookie)
            // 主机地址用登录结果带回来的那个，而不是在这里按来源再猜一遍：
            // 研究生门户的主机只有 CaptchaLoginSession 知道，猜错会把后续取数发到错误的主机上。
            sessionWriter.saveCampusBaseUrl(renewed.campusBaseUrl)
            lastError = ""
            _state.value = RenewalUiState.Idle
            completePending(true)
        }
    }

    /** 用户点了「换一张」。 */
    fun refreshCaptcha() {
        pendingSource?.let { fetchCaptcha(it, captchaLogin::refreshCaptcha) }
    }

    /** 用户取消：返回 false，调用方据此**保留已有缓存**，不要清屏。 */
    fun cancel() {
        _state.value = RenewalUiState.Idle
        completePending(false)
        lastError = ""
        captchaLogin.reset()
    }

    /**
     * 取一张验证码图并反映到状态上。
     *
     * 取图失败**不能静默退出**：留在弹窗里说明原因并允许重试，否则用户看到的是
     * 弹窗一闪而过、菜单还是那句「会话已过期」，无从知道是网络问题。
     */
    private fun fetchCaptcha(
        source: ImportSource,
        fetch: suspend (ImportSource) -> Result<Bitmap>
    ) {
        _state.value = RenewalUiState.Busy(source)
        scope.launch {
            val result = fetch(source)
            _state.value = RenewalUiState.AwaitingCaptcha(
                source = source,
                captchaBitmap = result.getOrNull(),
                prefilledUsername = credentialReader.getUsername(),
                prefilledPassword = credentialReader.getPassword(),
                // 取图失败时把原因写进 error；成功时保留上一次的失败原因（如「验证码或密码错误」），
                // 让用户知道刚刚为什么被弹回来，而不是面对一张新图却不知发生了什么。
                error = if (result.isFailure) {
                    failureMessage(result, "无法获取验证码，请重试")
                } else {
                    lastError
                }
            )
        }
    }

    private fun completePending(result: Boolean) {
        // `complete` 对已完成的 deferred 返回 false、不抛异常，所以重复调用是安全的。
        pending?.complete(result)
        pending = null
        pendingSource = null
    }

    private fun failureMessage(result: Result<*>, fallback: String): String =
        result.exceptionOrNull()?.message?.takeIf { it.isNotBlank() } ?: fallback
}

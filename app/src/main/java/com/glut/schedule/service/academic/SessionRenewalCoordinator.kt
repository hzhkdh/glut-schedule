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

    /**
     * 正在取验证码或正在提交。
     *
     * [form] 是进入 Busy 之前那张表单的快照，**首次取图时为 null**。
     * 带上它是为了让界面在等待期间**继续显示**弹窗（只置灰 + 转圈）而不是把它卸载：
     * 卸载会让用户点完「确认登录」后看到弹窗凭空消失数秒，且重建时连刚填的密码和验证码
     * 都会随 `remember` 一起丢掉，错一次就要重打一次。
     */
    data class Busy(
        val source: ImportSource,
        val form: AwaitingCaptcha? = null
    ) : RenewalUiState

    /** 等用户输入。 */
    data class AwaitingCaptcha(
        val source: ImportSource,
        /** 取图失败时为 null，此时 [error] 一定有值。 */
        val captchaBitmap: Bitmap?,
        /** 上一次失败原因；首次为空。 */
        val error: String = ""
    ) : RenewalUiState

    /** 微信统一认证票据失效，等待用户确认后重新扫码。 */
    data object WechatReauthenticationRequired : RenewalUiState

    /** 原地续期没有可用凭据，保留菜单缓存并引导用户回导入页登录。 */
    data class ImportLoginRequired(val source: ImportSource) : RenewalUiState
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

    /**
     * 写回学号（目前只有研究生会用到）。
     *
     * 学号为空在调用方那里就被算作「会话失效」，所以续期成功后必须把它补上，
     * 否则重放会拿着空学号再失败一次——用户白登一场。
     */
    suspend fun saveStudentNumber(studentNumber: String)
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
    private val currentSessionAuthMethod: suspend () -> AcademicSessionAuthMethod,
    private val silentWechatLogin: suspend () -> AcademicLoginResult,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow<RenewalUiState>(RenewalUiState.Idle)
    val state: StateFlow<RenewalUiState> = _state.asStateFlow()

    private val mutex = Mutex()

    /** 正在等用户操作的那次续期；没有则为 null。单飞的锚点。 */
    private var pending: CompletableDeferred<Boolean>? = null
    private var pendingSource: ImportSource? = null

    /** 每次取图递增；取消或刷新后，旧协程即使晚到也不得覆盖当前弹窗状态。 */
    private var captchaRequestGeneration: Long = 0

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
            // 桂林既可能是账号密码会话，也可能是微信扫码会话；两者的静默续期凭据不同。
            val isWechatSession = currentSessionAuthMethod() == AcademicSessionAuthMethod.WECHAT_SSO
            val result = if (isWechatSession) silentWechatLogin() else silentLogin()
            if (result is AcademicLoginResult.Success) {
                sessionWriter.saveCookie(result.cookie)
                sessionWriter.saveCampusBaseUrl(result.campusBaseUrl)
                return true
            }
            if (isWechatSession && result == AcademicLoginResult.CaptchaOrInteractiveLoginRequired) {
                return awaitWechatReauthentication()
            }
            return false
        }

        // 验证码无法替代账号密码；未保存凭据时不在菜单暴露输入框。
        if (credentialReader.getUsername().isBlank() || credentialReader.getPassword().isBlank()) {
            _state.value = RenewalUiState.ImportLoginRequired(source)
            return false
        }

        val mine = claimPending(source)
        mine.inFlight?.let { return it.await() }

        fetchCaptcha(source, captchaLogin::requestCaptcha)
        return mine.owned.await()
    }

    /**
     * 微信统一认证票据过期时，让原先失败的取数操作保持等待。
     *
     * 扫码成功后 [completeWechatReauthentication] 会写回新会话并完成这个 deferred，
     * 调用方随即重读 cookie、重放原请求；不能像旧实现那样先返回 false，
     * 否则会出现「新会话已经有了，但用户还得再点一次刷新」的断层。
     */
    private suspend fun awaitWechatReauthentication(): Boolean {
        val mine = claimPending(ImportSource.GUILIN)
        mine.inFlight?.let { return it.await() }
        _state.value = RenewalUiState.WechatReauthenticationRequired
        return mine.owned.await()
    }

    /** 扫码页已拿到新教务会话：持久化后释放原先等待重放的刷新请求。 */
    fun completeWechatReauthentication(cookie: String) {
        if (pending == null) return
        scope.launch {
            // 续期路径不能依赖导入页异步写库的时序；先由门卫写回，重放读取时必定是新票据。
            sessionWriter.saveCookie(cookie)
            sessionWriter.saveCampusBaseUrl(AcademicLoginResult.DEFAULT_GUILIN_URL)
            _state.value = RenewalUiState.Idle
            completePending(true)
        }
    }

    /** 认领或加入正在进行的续期，南宁验证码与微信扫码共用同一个单飞闸门。 */
    private suspend fun claimPending(source: ImportSource): PendingClaim {
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
        return PendingClaim(owned = mine, inFlight = inFlight)
    }

    private data class PendingClaim(
        val owned: CompletableDeferred<Boolean>,
        val inFlight: CompletableDeferred<Boolean>?
    )

    /** 用户在弹窗里点了确认。 */
    fun submit(captcha: String) {
        val source = pendingSource ?: return
        val state = _state.value as? RenewalUiState.AwaitingCaptcha ?: return
        if (captcha.isBlank()) {
            _state.value = state.copy(error = "请输入验证码")
            return
        }
        val effectiveUsername = credentialReader.getUsername()
        val effectivePassword = credentialReader.getPassword()
        // 把表单快照带进 Busy：提交期间界面继续显示它，用户不会以为点空了。
        _state.value = RenewalUiState.Busy(source, state)
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
            // 学号只在研究生那条路上有；南宁为 null，不写，免得把已有的学号抹成空串。
            renewed.studentNumber?.takeIf { it.isNotBlank() }?.let {
                sessionWriter.saveStudentNumber(it)
            }
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
        // 先作废在飞取图，再切 Idle：否则旧协程完成后会把已关闭的弹窗重新打开。
        captchaRequestGeneration += 1
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
        val requestGeneration = ++captchaRequestGeneration
        // 保留上一次的表单快照：Busy 期间界面继续渲染它（置灰 + 转圈），
        // 而不是把弹窗整个卸载——卸载会让用户看到它消失数秒、并丢掉已输入的内容。
        //
        // 要**穿透 Busy** 再取：提交失败后紧接着就会调到这里换新图，此时当前状态正是
        // `submit` 刚写入的 `Busy(source, form)`。若只看 `AwaitingCaptcha`，这里会拿到
        // null，弹窗恰好在「刚输错、正在换图」那一刻被卸载——正是最不该消失的时候。
        val previousForm = when (val current = _state.value) {
            is RenewalUiState.AwaitingCaptcha -> current
            is RenewalUiState.Busy -> current.form
            else -> null
        }
        _state.value = RenewalUiState.Busy(source, previousForm)
        scope.launch {
            val result = fetch(source)
            // 取消、刷新或新一轮续期已接管状态时，丢弃旧网络响应。
            if (requestGeneration != captchaRequestGeneration || pendingSource != source || pending == null) {
                return@launch
            }
            _state.value = RenewalUiState.AwaitingCaptcha(
                source = source,
                captchaBitmap = result.getOrNull(),
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

package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 微信登录的接线契约。
 *
 * 判定逻辑本身（导航分类、cookie 合并、学号解析）都有各自的纯函数单测；
 * 这里锁的是**接线**——那些只能在源码层面核对、一旦被后续重构悄悄改掉就会出事的地方。
 * `DirectLoginViewModel` 与 `MainActivity` 的构造依赖一整套带 Context 的服务，
 * JVM 单测里起不来，所以沿用本仓既有的 `*ContractTest` 路数。
 */
class WechatLoginFlowContractTest {

    @Test
    fun wechatEntryOnlyShowsForGuilinAndBeforeCompletion() {
        val screen = page("DirectLoginScreen.kt")
        val entry = screen.substringAfter("if (uiState.importSource == ImportSource.GUILIN && !uiState.importCompleted)")

        assertTrue(entry.contains("onOpenWechatLogin"))
        // 登录过程中必须禁用：主按钮已经在转圈，此时再放一个能点的入口只会让人点出第二次登录。
        assertTrue(entry.contains("enabled = !uiState.isLoggingIn"))
    }

    /**
     * 界面条件是状态相关的，会随导入来源变化；**守卫才是权威**。
     * 少了这道守卫，南宁/研究生用户点进去只会对着一个永远登不上的页面发呆。
     */
    @Test
    fun viewModelRefusesWechatLoginForNonGuilinSource() {
        val viewModel = viewModelSource()
        val body = viewModel.substringAfter("fun startWechatLogin()")
            .substringBefore("fun cancelWechatLogin()")

        assertTrue(body.contains("importSource != ImportSource.GUILIN"))
        assertTrue(body.contains("WECHAT_GUILIN_ONLY_MESSAGE"))
    }

    /**
     * 微信登录没有密码，绝不能借道 `onLoginSuccess` 里那套「存/清凭据」的逻辑：
     * `uiState` 里的账号密码是上一位用户留下的，写回去等于张冠李戴。
     */
    @Test
    fun wechatSuccessPathNeverWritesOrClearsStoredPasswords() {
        val viewModel = viewModelSource()
        val body = viewModel.substringAfter("fun onWechatLoginSucceeded(")
            .substringBefore("fun loginAndImport()")

        assertTrue(body.contains("updateCredentials = false"))

        val onLoginSuccess = viewModel.substringAfter("private suspend fun onLoginSuccess(")
            .substringBefore("private suspend fun onGraduateLoginSuccess(")
        assertTrue(onLoginSuccess.contains("if (updateCredentials)"))
    }

    /**
     * 微信登录在**导入过程中**才拿到学号（登录成功那一刻没有密码、也没有学号），
     * 所以换账号清理必须晚于学号解析。顺序一旦反过来，清理就恒不触发——
     * 用户会在新账号下看到上一位学生的课表，而且没有任何报错。
     */
    @Test
    fun accountSwitchClearingHappensAfterStudentNumberResolution() {
        val viewModel = viewModelSource()
        val importBody = viewModel.substringAfter("private suspend fun performImport(")
            .substringBefore("private suspend fun fetchAndSaveScores(")

        val resolutionIndex = importBody.indexOf("resolvedStudentNumber")
        val clearingIndex = importBody.indexOf("semesterBulkDownloadCoordinator.cancelForAccountChange()")

        assertTrue("必须能解析出学号", resolutionIndex >= 0)
        assertTrue("必须保留换账号清理", clearingIndex >= 0)
        assertTrue("学号解析必须先于换账号清理", resolutionIndex < clearingIndex)
        // 清理会把刚存下的 cookie 一并抹掉，必须立刻补回，否则后续请求全部未登录。
        assertTrue(importBody.contains("sessionStore.saveCookie(activeCookie)"))
    }

    /**
     * WebView 的 cookie **必须留着**。
     *
     * 统一身份认证的会话就存在那里，留着它，下次打开「微信登录」就能直接换成教务会话，
     * 用户不必每次重扫——这正是扫码头号被抱怨的地方。清空会让「每次都扫码」变成死规定。
     *
     * 代价与浏览器的「记住登录」相同（共用设备上别人也能续用），换账号则另有兜底：
     * 导入时按学号判定并清空旧数据。
     */
    @Test
    fun webLoginKeepsCookiesSoRepeatLoginsSkipTheScan() {
        val screen = page("WechatLoginScreen.kt")

        assertFalse(
            "不得清空 WebView 的 cookie——那会让每次登录都得重新扫码",
            screen.contains("removeAllCookies")
        )
        assertTrue(
            "退出时要落盘，否则进程被回收时会话就丢了，免扫无从谈起",
            screen.contains("CookieManager.getInstance().flush()")
        )
    }

    /**
     * 续期探测必须让统一认证页的脚本有机会把已有登录态换成教务会话。
     * 只查 CASTGC/TGC 或刚落到登录页就结束，都会把本可静默恢复的会话误判成要扫码，
     * 用户因而看到二维码页面一闪而过。
     */
    @Test
    fun sessionResumerWaitsForActualInteractiveLoginBeforeShowingQr() {
        val resumer = source("service/academic/WechatSsoSessionResumer.kt")

        assertTrue("后台续期也必须允许统一认证页执行必要脚本", resumer.contains("settings.javaScriptEnabled = true"))
        assertTrue("后台续期也必须保留认证页的 DOM 存储", resumer.contains("settings.domStorageEnabled = true"))
        assertFalse("不能只凭特定 Cookie 名提前判定需要扫码", resumer.contains("hasCasSession("))
        assertTrue("落到登录页后应留出短暂探测窗口", resumer.contains("INTERACTIVE_LOGIN_PROBE_DELAY_MILLIS"))
        assertTrue("探测窗口必须由主线程延迟回调执行", resumer.contains("handler.postDelayed"))
    }

    /**
     * 这条是 WebView 加固的核心。一旦有人为了图方便注册 JS 桥，
     * 页面（以及任何被它加载的第三方脚本）就能直接调进 Kotlin。
     * App → 页面方向的 `evaluateJavascript` 是单向的，安全，允许。
     */
    @Test
    fun noJavascriptBridgeIsEverRegistered() {
        // 匹配调用形式而非裸词：文件头的注释里也会出现这个名字（正是为了声明不这么做）。
        val offenders = mainSources().filter { it.readText().contains(".addJavascriptInterface(") }

        assertTrue(
            "不得注册 JS 桥：${offenders.map { it.name }}",
            offenders.isEmpty()
        )
    }

    @Test
    fun webLoginBlocksNonCampusHostsAndNeverProceedsOnSslErrors() {
        val screen = page("WechatLoginScreen.kt")

        assertTrue(screen.contains("classifyWechatNavigation(url)"))
        assertTrue(screen.contains("WechatNav.BLOCKED"))
        // 证书有问题就停住。proceed() 等于亲手拆掉这道防线。
        assertTrue(screen.contains("handler.cancel()"))
        assertFalse(screen.contains("handler.proceed()"))
    }

    /** 覆盖层必须画在抽屉之外，否则抽屉开着时它会被压在下面。 */
    @Test
    fun overlayIsASiblingOfTheDrawer() {
        val main = mainActivitySource()

        assertTrue(main.contains("var showWechatLogin by remember"))
        assertTrue(main.contains("if (showWechatLogin) {"))
        assertTrue(main.contains("showWechatLogin -> showWechatLogin = false"))
        // 与 pendingBackgroundCrop 同一层级：都以 `?.let` / `if` 直接写在 drawer 收尾之后。
        assertTrue(
            main.indexOf("if (showWechatLogin) {") > main.indexOf("ModalNavigationDrawer(")
        )
    }

    private fun viewModelSource(): String = page("DirectLoginViewModel.kt")

    private fun page(name: String): String = source("ui/pages/$name")

    private fun mainActivitySource(): String = source("MainActivity.kt")

    private fun source(relative: String): String {
        val module = File("src/main/java/com/glut/schedule/$relative")
        val path = if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$relative")
        return path.readText()
    }

    private fun mainSources(): List<File> {
        val module = File("src/main/java")
        val root = if (module.exists()) module else File("app/src/main/java")
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}

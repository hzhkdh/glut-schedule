package com.glut.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续期弹窗的源码契约。
 *
 * 为什么用源码断言而不是 Compose UI 测试：本仓库没有 androidTest 运行环境（也没有 Robolectric），
 * 而这里要钉住的两件事恰恰是「跑起来也未必看得出」的——组件只有一份、以及弹窗显式给了容器色。
 * 后者尤其阴：`GlutScheduleTheme` 用的是 `darkColorScheme`，不给 `containerColor` 会渲染成深灰，
 * 而单元测试根本渲染不了，只有源码断言拦得住。
 */
class SessionRenewalUiContractTest {

    @Test
    fun bothEntryPointsShareTheSameDialogComponent() {
        val main = source("MainActivity.kt")
        val login = page("DirectLoginScreen.kt")

        assertTrue("导入页必须改用共用组件", login.contains("SessionRenewalDialog("))
        assertFalse("导入页不再自养私有弹窗", login.contains("private fun LoginCaptchaDialog("))
        // MainActivity 的引用在 Task 4 接入，这里先只钉导入页侧；先断言文件读得到，
        // 免得路径写错时这条测试「因为读不到文件而空过」。
        assertTrue(main.contains("setContent"))
    }

    @Test
    fun renewalDialogOnlyCollectsCaptcha() {
        val dialog = component("SessionRenewalDialog.kt")

        assertFalse("会话续期弹窗不应再暴露密码输入框", dialog.contains("PasswordVisualTransformation"))
        assertFalse("会话续期弹窗不应再暴露学号输入框", dialog.contains("Text(\"学号\""))
        assertTrue("提交只应带验证码", dialog.contains("onSubmit(captchaInput)"))
        assertTrue("不传容器色会落到 darkColorScheme 的深灰", dialog.contains("containerColor ="))
        assertTrue("取图失败的原因要显示在弹窗里", dialog.contains("error"))
    }

    /** `MainActivity.kt` 在 `com.glut.schedule` 根下，不在 `ui/pages/`。 */
    private fun source(name: String) = java.io.File("src/main/java/com/glut/schedule/$name")
        .let { if (it.exists()) it else java.io.File("app/src/main/java/com/glut/schedule/$name") }
        .readText()

    @Test
    fun containerAssemblesTheCoordinatorAndMainActivityMountsIt() {
        val app = source("ScheduleApplication.kt")
        val main = source("MainActivity.kt")

        assertTrue(app.contains("SessionRenewalCoordinator("))
        assertTrue(app.contains("CaptchaLoginSession("))
        assertTrue("挂在顶层，任何页面都盖得住", main.contains("renewalState"))
        assertTrue("返回键要能取消续期", main.contains("sessionRenewalCoordinator.cancel()"))
    }

    @Test
    fun everyAcademicMenuGoesThroughTheRenewalGate() {
        listOf(
            "ScoreViewModel.kt", "GradeExamViewModel.kt", "StudyPlanViewModel.kt",
            "SemesterOverviewViewModel.kt", "ExamViewModel.kt",
            "ScheduleViewModel.kt", "ProfessionalScoreViewModel.kt"
        ).forEach { file ->
            val source = page(file)
            assertFalse("$file 不该再直接调 silentLogin", source.contains("loginService.silentLogin()"))
            assertTrue("$file 应经续期门卫", source.contains("ensureFreshSession()"))
        }
    }

    /**
     * 本地没存账号是**新装的默认状态**，不是边缘情况：没勾「记住密码」时
     * `DirectLoginViewModel` 调的是 `clearCredentials()`，账号和密码一起清掉；
     * 而全新安装的复选状态又按「本地有没有账号」复位，也就是默认不勾。
     *
     * 弹窗若只收验证码和密码，这群人点确认只会一直拿到「验证码或密码错误」，
     * 根本看不出真正缺的是账号——于是陷入「换一张图 → 再错一次」的死循环。
     * 而他们恰恰是计划里真机验证点名要覆盖的那群人。
     */
    @Test
    fun firstGraduateLoginUsesLoginTitleInsteadOfExpiryTitle() {
        val login = page("DirectLoginScreen.kt")

        assertTrue("首次研究生登录必须显示登录语义", login.contains("研究生教务登录"))
        assertTrue("首次南宁登录必须显示登录语义", login.contains("南宁分校教务登录"))
        assertFalse("导入页首次登录不能沿用续期标题", login.contains("研究生教务会话已过期"))
    }

    /**
     * `needsInteractiveLogin` 曾写了 14 次、还配了 `consumeInteractiveLoginRequest()`，
     * 但全仓零消费方——续期门卫接管后连写入点都成了孤儿。这类「有人写没人读」的信号
     * 最容易在下次重构时被误当成「还有人在用」而保留下来，所以用一条断言钉死它不再回来。
     */
    @Test
    fun theDeadInteractiveLoginFlagIsGone() {
        listOf("ScheduleViewModel.kt", "ExamViewModel.kt").forEach { file ->
            val source = page(file)
            assertFalse("$file 仍有无人消费的 needsInteractiveLogin", source.contains("needsInteractiveLogin"))
            assertFalse("$file 仍有无人消费的 consumeInteractiveLoginRequest", source.contains("consumeInteractiveLoginRequest"))
        }
    }

    /**
     * 微信上次授权已经失效时，用户需要扫码是认证方的要求；但中间那层“去登录”确认框
     * 不做任何认证工作，只会让一次刷新多一次点击。状态一到就应直接拉起扫码覆盖层。
     */
    @Test
    fun expiredWechatSessionOpensTheLoginOverlayWithoutAnExtraConfirmation() {
        val main = source("MainActivity.kt")

        assertTrue(
            "微信续期状态应由副作用自动开启扫码页",
            main.contains("LaunchedEffect(renewalState)") &&
                main.contains("renewalState is RenewalUiState.WechatReauthenticationRequired")
        )
        assertFalse("不应再要求用户点击无业务意义的去登录确认框", main.contains("需要重新微信登录"))
    }

    @Test
    fun successfulWechatReauthenticationReplaysTheOriginalRefresh() {
        val main = source("MainActivity.kt")

        assertTrue(
            "扫码成功写回会话后，要释放续期门卫等待的原刷新任务",
            main.contains("sessionRenewalCoordinator.completeWechatReauthentication(cookie)")
        )
    }

    /**
     * Busy 期间弹窗必须**继续挂着**，否则用户点完「确认登录」会看到它凭空消失好几秒
     * （南宁要串行跑验证码校验、登录、重定向链、框架页验证），既没进度反馈也按不了取消；
     * 更糟的是弹窗重建时 `remember` 被销毁，刚填的密码和验证码会一起丢，错一次重打一次。
     */
    @Test
    fun theRenewalDialogStaysMountedWhileBusy() {
        val main = source("MainActivity.kt")

        assertTrue(
            "Busy 期间要沿用上一次的表单内容，而不是把弹窗卸载",
            main.contains("is RenewalUiState.Busy -> current.form")
        )
        assertTrue(
            "忙碌态要真的传给组件，而不是硬编码 false（那会让转圈变成死代码）",
            main.contains("isBusy = renewalState is RenewalUiState.Busy")
        )
        assertTrue(
            "Busy 期间返回键要能取消，否则用户只能干等网络超时",
            main.contains("renewalState is RenewalUiState.Busy ->")
        )
    }

    /**
     * 弹窗的副标题必须能按入口换。
     *
     * 续期语境的副标题是「不用去导入页重新登录」——但那句话在**导入页**上是自相矛盾的：
     * 用户此刻就在导入页。两个入口共用一个组件，副标题就不能写死。
     */
    @Test
    fun theDialogSubtitleIsNotContradictoryOnTheImportPage() {
        val dialog = component("SessionRenewalDialog.kt")
        val login = page("DirectLoginScreen.kt")

        assertTrue("弹窗要能按入口换副标题", dialog.contains("subtitle"))
        assertTrue(
            "导入页必须传自己的副标题——否则用户就在导入页，却被告知「不用去导入页」",
            login.contains("subtitle =")
        )
    }

    /**
     * 七个教务菜单都不再接受 `loginService`。
     *
     * 续期门卫接管后，这七个类体里已经没有一处读它（逐个 grep 确认过），只剩构造参数在传递。
     * 留着它比留一段死代码更糟：**下一位读者会以为门卫之外还有登录路径**，
     * 从而不敢删、或照着它再写一条旁路。
     */
    @Test
    fun academicMenusNoLongerCarryADeadLoginService() {
        listOf(
            "ScoreViewModel.kt", "GradeExamViewModel.kt", "StudyPlanViewModel.kt",
            "SemesterOverviewViewModel.kt", "ExamViewModel.kt",
            "ScheduleViewModel.kt", "ProfessionalScoreViewModel.kt"
        ).forEach { file ->
            assertFalse("$file 仍带着类体里零读取的 loginService", page(file).contains("loginService"))
        }
        // 导入页例外：它自己就是登录入口，loginService 是真在用的。
        assertTrue(
            "导入页应当保留 loginService——它就是登录本身",
            page("DirectLoginViewModel.kt").contains("loginService")
        )
    }

    /**
     * 「去导入课表页重新登录」正是本功能要消灭的那句话。哪个 ViewModel 里还留着它，
     * 就说明**还有分支没接门卫**。
     *
     * 为什么不能只靠 `everyAcademicMenuGoesThroughTheRenewalGate`：那条断言的是
     * 「文件里出现过 `ensureFreshSession()`」，一个文件只要**任意一处**接入就通过。
     * `ScoreViewModel` 与 `StudyPlanViewModel` 各自有一个研究生分支，本科分支接了门卫、
     * 研究生分支没接，于是那条断言照样报绿——这正是评审 C2 抓到的漏洞。
     * 所以这里按「旧文案必须消失」来钉，它对「漏掉一个分支」是敏感的。
     */
    @Test
    fun noMenuStillSendsTheUserBackToTheImportPageOnExpiry() {
        listOf(
            "ScoreViewModel.kt", "GradeExamViewModel.kt", "StudyPlanViewModel.kt",
            "SemesterOverviewViewModel.kt", "ExamViewModel.kt",
            "ScheduleViewModel.kt", "ProfessionalScoreViewModel.kt"
        ).forEach { file ->
            val source = page(file)
            assertFalse("$file 仍提示用户去导入页登录研究生教务", source.contains("请先在导入课表页面登录研究生教务"))
            assertFalse("$file 仍提示用户去导入课表页", source.contains("请到导入课表页面重新登录"))
        }
    }

    private fun page(name: String) = java.io.File("src/main/java/com/glut/schedule/ui/pages/$name")
        .let { if (it.exists()) it else java.io.File("app/src/main/java/com/glut/schedule/ui/pages/$name") }
        .readText()

    private fun component(name: String) = java.io.File("src/main/java/com/glut/schedule/ui/components/$name")
        .let { if (it.exists()) it else java.io.File("app/src/main/java/com/glut/schedule/ui/components/$name") }
        .readText()
}

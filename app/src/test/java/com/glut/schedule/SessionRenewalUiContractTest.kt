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
    fun renewalDialogCollectsPasswordNotJustCaptcha() {
        val dialog = component("SessionRenewalDialog.kt")

        assertTrue("会话过期后本地可能没有密码，必须能当场填", dialog.contains("PasswordVisualTransformation"))
        assertTrue("已保存的密码要能预填", dialog.contains("prefilledPassword"))
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
    fun renewalDialogCollectsTheAccountNotJustThePassword() {
        val dialog = component("SessionRenewalDialog.kt")

        assertTrue("必须能当场填账号，而不只依赖本机保存的", dialog.contains("prefilledUsername"))
        assertTrue("账号要有独立输入框", dialog.contains("Text(\"学号\""))
        assertTrue(
            "提交时要把账号一起交出去",
            dialog.contains("onSubmit(captchaInput, usernameInput, passwordInput)")
        )
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

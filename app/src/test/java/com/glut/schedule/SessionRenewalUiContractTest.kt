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

    private fun page(name: String) = java.io.File("src/main/java/com/glut/schedule/ui/pages/$name")
        .let { if (it.exists()) it else java.io.File("app/src/main/java/com/glut/schedule/ui/pages/$name") }
        .readText()

    private fun component(name: String) = java.io.File("src/main/java/com/glut/schedule/ui/components/$name")
        .let { if (it.exists()) it else java.io.File("app/src/main/java/com/glut/schedule/ui/components/$name") }
        .readText()
}

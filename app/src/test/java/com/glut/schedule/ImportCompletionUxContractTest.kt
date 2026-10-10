package com.glut.schedule

import com.glut.schedule.ui.pages.DirectLoginUiState
import com.glut.schedule.ui.pages.afterLoginInputEdit
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入完成后的出口：主按钮变身「查看课表」，下面再挂一颗小号「重新导入」。
 *
 * 背景：导入成功后主按钮只是复位成可点的「登 录 并 导 入」，且没有任何跳转。用户不知道
 * 要自己回课表页，就留在导入页反复点导入——每点一次都重新拉一遍全量数据，而他看不出区别。
 *
 * 断言分两层：
 * - 行为层：完成标志的初值、以及「动过登录输入就复位」这条状态迁移，直接跑纯函数；
 * - 契约层：`DirectLoginViewModel` 的构造依赖一整套带 Context 的服务，在 JVM 单测里起不来，
 *   所以「哪几处置位、哪几处复位」以及界面接线只能用源码断言锁住
 *   （与本仓既有的 `*ContractTest` 同一路数）。
 */
class ImportCompletionUxContractTest {

    @Test
    fun importCompletionFlagStartsCleared() {
        // 初值必须是假：否则用户刚进导入页就看到主按钮写着「查看课表」，
        // 而他什么都还没导。
        assertFalse(DirectLoginUiState().importCompleted)
    }

    @Test
    fun editingLoginInputClearsImportCompletionWithoutTouchingWhatUserTyped() {
        val completed = DirectLoginUiState(
            username = "20240001",
            password = "synthetic-password",
            importCompleted = true
        )

        val afterEdit = completed.copy(username = "20240002").afterLoginInputEdit()

        assertFalse(afterEdit.importCompleted)
        // 复位只收完成标志，不能顺手改掉用户正在编辑的内容。
        assertEquals("20240002", afterEdit.username)
        assertEquals("synthetic-password", afterEdit.password)
    }

    @Test
    fun changingAccountPasswordOrSourceClearsImportCompletion() {
        val viewModel = viewModelSource()

        val updateUsernameBody = viewModel.substringAfter("fun updateUsername(")
            .substringBefore("fun updatePassword(")
        val updatePasswordBody = viewModel.substringAfter("fun updatePassword(")
            .substringBefore("fun updateImportSource(")
        val updateImportSourceBody = viewModel.substringAfter("fun updateImportSource(")
            .substringBefore("fun updateCaptchaInput(")

        // ViewModel 挂在 Activity 作用域，离开导入页不会销毁，完成标志会一直留着。
        // 这三处改学号 / 改密码 / 换来源时若不复位，换个账号回来的用户会看到主按钮
        // 仍写着「查看课表」，而点下去看到的是上一个账号的课表。
        listOf(
            "改学号" to updateUsernameBody,
            "改密码" to updatePasswordBody,
            "换导入来源" to updateImportSourceBody
        ).forEach { (what, body) ->
            assertTrue("$what 必须收回「已完成」标志", body.contains("afterLoginInputEdit()"))
        }
    }

    @Test
    fun bothImportPathsMarkCompletionAndReimportTakesItBack() {
        val viewModel = viewModelSource()

        val undergradBody = viewModel.substringAfter("private suspend fun performImport(")
            .substringBefore("private suspend fun fetchAndSaveScores(")
        val graduateBody = viewModel.substringAfter("private suspend fun performGraduateImport(")
            .substringBefore("private companion object {")

        // 本科（课表落库后的收尾 copy）与研究生（coordinator.run().onSuccess）两条路径都要置位；
        // 少一条就会出现「某个来源导完还是让人反复点导入」。
        assertTrue("本科成功路径必须置位", undergradBody.contains("importCompleted = true"))
        assertTrue("研究生成功路径必须置位", graduateBody.contains("importCompleted = true"))

        // 「重新导入」把标志收回去，新一轮登录也必须先清零，
        // 否则登录过程中主按钮会残留着上一次的「查看课表」。
        val restartBody = viewModel.substringAfter("fun restartImport() {")
            .substringBefore("fun updateCaptchaInput(")
        assertTrue(restartBody.contains("importCompleted = false"))
        assertTrue(viewModel.contains("message = \"正在登录...\", importCompleted = false"))
    }

    @Test
    fun mainButtonBecomesViewScheduleAndReimportIsVisuallyLighter() {
        val screen = screenSource()

        val mainButton = screen.substringAfter("// 主按钮。")
            .substringBefore("// 「重新导入」只在完成态出现")
        assertTrue(
            mainButton.contains("if (uiState.importCompleted) onDone() else viewModel.loginAndImport()")
        )
        assertTrue(mainButton.contains("\"查看课表 ›\""))
        assertTrue(mainButton.contains("\"登 录 并 导 入\""))

        // 「重新导入」只在完成态出现，且用 TextButton 而不是第二颗实心按钮：
        // 主按钮已经把用户送去课表，这里只是回头路，视觉必须明显轻于主按钮。
        // 切到该 if 块的第一个 `}`（即 TextButton 尾部 lambda 的收尾），
        // 而不是切到「重新导入」这个字面量——那样会把 Text(...) 的颜色参数切掉。
        val reimportBlock = screen.substringAfter("if (uiState.importCompleted) {")
            .substringBefore("}")
        assertTrue(reimportBlock.contains("\"重新导入\""))
        assertTrue(reimportBlock.contains("TextButton"))
        assertTrue(reimportBlock.contains("viewModel::restartImport"))
        assertTrue(reimportBlock.contains("color = LoginSecondary"))
        assertFalse(reimportBlock.contains("OutlinedButton"))
    }

    @Test
    fun semesterViewButtonSwitchesSemesterBeforeLeavingTheImportPage() {
        val screen = screenSource()

        val block = screen.substringAfter("val viewSemesterAndOpenSchedule")
            .substringBefore("}")
        assertTrue(block.contains("viewModel.viewSemester(semesterId)"))
        // 顺序有意义：viewSemester 只切学期不跳转，单独调用时用户点了界面毫无变化。
        // 先切、再跳，落到课表页看到的才是刚选的那个学期。
        assertTrue(
            block.indexOf("viewModel.viewSemester(semesterId)") < block.indexOf("onDone()")
        )
        assertTrue(screen.contains("onViewSemester = viewSemesterAndOpenSchedule"))
    }

    @Test
    fun importCompletionReturnsToTheSchedulePageAndClosesTheDrawer() {
        val mainActivity = mainActivitySource()

        val importBranch = mainActivity.substringAfter("DrawerItem.Import -> DirectLoginScreen(")
            .substringBefore("DrawerItem.Settings ->")
        assertTrue(importBranch.contains("selectedItem = DrawerItem.Schedule"))
        // 抽屉此时可能还开着（用户从抽屉点进导入页，没关就点了按钮），
        // 不关会出现「内容已经变了、抽屉还挡着」。
        assertTrue(importBranch.contains("drawerState.close()"))
    }

    private fun viewModelSource(): String = pageSource("DirectLoginViewModel.kt")

    private fun screenSource(): String = pageSource("DirectLoginScreen.kt")

    private fun pageSource(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }

    private fun mainActivitySource(): String {
        val module = File("src/main/java/com/glut/schedule/MainActivity.kt")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/MainActivity.kt")).readText()
    }
}

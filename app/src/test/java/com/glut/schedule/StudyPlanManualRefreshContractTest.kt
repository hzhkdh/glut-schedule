package com.glut.schedule

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudyPlanManualRefreshContractTest {
    @Test
    fun studyPlanScreenNeverRefreshesWithoutTopBarAction() {
        val screen = source("StudyPlanScreen.kt")
        val activity = source("MainActivity.kt")

        assertFalse(screen.contains("viewModel.refresh()"))
        assertFalse(screen.contains("LaunchedEffect"))
        assertTrue(activity.contains("onClick = studyPlanViewModel::refresh"))
    }

    @Test
    fun graduateRefreshUsesGraduatePortalAndReadonlyLayout() {
        val viewModel = source("StudyPlanViewModel.kt")
        val screen = source("StudyPlanScreen.kt")

        assertTrue(viewModel.contains("CampusType.GRADUATE"))
        assertTrue(viewModel.contains("GraduateClient"))
        assertTrue(viewModel.contains("GraduateParser.studyPlan"))
        assertTrue(screen.contains("uiState.isReadOnly"))
        assertTrue(screen.contains("readOnly = uiState.isReadOnly"))
    }

    /**
     * 只读模式（研究生）下整个表头行都不渲染。
     *
     * 只读模式没有课组要求、学分/门数/合格这些列，留一行「课程类别（只读）」等于凭空占掉
     * 一行高度。所以判定是「表头那行的存在由 !isReadOnly 控制」，而不是「只读时换一组表头文字」。
     */
    @Test
    fun readOnlyStudyPlanRendersNoHeaderRow() {
        val screen = source("StudyPlanScreen.kt")

        val headerStart = screen.indexOf("if (!uiState.isReadOnly) {")
        val headerEnd = screen.indexOf("LegendBar(", headerStart)
        assertTrue("表头行必须整体包在 !isReadOnly 条件里", headerStart > 0 && headerEnd > headerStart)

        // 用 `HeaderCell("` 调用计数而不是搜字面量：注释里提到旧的「课程类别（只读）」不该被判成表头残留。
        // 这里必须用普通字符串：raw string 收尾的引号会与结尾的三个引号连成一体，把断言退化成匹配 `HeaderCell(`，
        // 连下面那个 `private fun RowScope.HeaderCell(` 声明也会被算进去。
        val headerCalls = Regex("HeaderCell\\(\"")
        val headerBlock = screen.substring(headerStart, headerEnd)
        listOf("课组名称", "属性", "学分要求", "已获学分", "门数要求", "通过门数", "是否合格")
            .forEach { column -> assertTrue("表头应包含 $column", headerBlock.contains(column)) }
        assertEquals("受 !isReadOnly 保护的表头应是 7 列", 7, headerCalls.findAll(headerBlock).count())
        // 而全文件也只有这 7 个表头单元：只读分支里不能再冒出第二个表头。
        assertEquals("只读分支不得再渲染任何表头", 7, headerCalls.findAll(screen).count())
    }

    @Test
    fun graduateSessionIsCommittedOnlyAfterScheduleWrite() {
        val source = source("DirectLoginViewModel.kt")
        val loginStart = source.indexOf("private suspend fun onGraduateLoginSuccess")
        val loginEnd = source.indexOf("private suspend fun sourceClassPeriods", loginStart)
        val loginSuccess = source.substring(loginStart, loginEnd)
        assertFalse(loginSuccess.contains("sessionStore.saveCookie"))
        assertFalse(loginSuccess.contains("sessionStore.saveCampusBaseUrl"))
        assertFalse(loginSuccess.contains("scheduleRepository.clearAllData"))

        val importStart = source.indexOf("private suspend fun performGraduateImport")
        val switchIndex = source.indexOf("switchSource = {", importStart)
        assertTrue(switchIndex > importStart)
        assertTrue(source.indexOf("sessionStore.saveCookie", switchIndex) > switchIndex)
        assertTrue(source.indexOf("sessionStore.saveCampusBaseUrl", switchIndex) > switchIndex)
        val writeScheduleIndex = source.indexOf("writeSchedule = {", importStart)
        assertTrue(writeScheduleIndex > importStart)
        assertTrue(source.indexOf("scheduleRepository.clearAllData", writeScheduleIndex) > writeScheduleIndex)
    }

    private fun source(name: String): String {
        val modulePath = if (name == "MainActivity.kt") {
            "src/main/java/com/glut/schedule/$name"
        } else {
            "src/main/java/com/glut/schedule/ui/pages/$name"
        }
        val module = File(modulePath)
        val rootPath = if (name == "MainActivity.kt") {
            "app/src/main/java/com/glut/schedule/$name"
        } else {
            "app/src/main/java/com/glut/schedule/ui/pages/$name"
        }
        return (if (module.exists()) module else File(rootPath)).readText()
    }
}

package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleasePolishContractTest {
    @Test
    fun professionalYearsUseAnEqualWidthNonScrollingRow() {
        val screen = source("ProfessionalScoreScreen.kt")
        val selector = screen
            .substringAfter("private fun AcademicYearSelector")
            .substringBefore("private fun ScoreUnavailableNote")

        assertFalse(selector.contains("LazyRow"))
        assertTrue(selector.contains("Arrangement.spacedBy(6.dp)"))
        assertTrue(selector.contains(".weight(1f)"))
        assertTrue(selector.contains("heightIn(min = 44.dp)"))
    }

    @Test
    fun fitnessLoginActionClearlyShowsTheRunningState() {
        val screen = source("FitnessScoreScreen.kt")

        assertTrue(screen.contains("登录中…"))
        assertTrue(screen.contains("CircularProgressIndicator"))
        assertTrue(screen.contains("FitnessAccent.copy(alpha = 0.38f)"))
        assertTrue(screen.contains("if (state.isLoggingIn)"))
        assertTrue(screen.contains("enabled = state.canSubmitLogin"))
    }

    @Test
    fun fitnessLoginActionOnlyAppearsOnTheLatestTab() {
        val screen = source("FitnessScoreScreen.kt")
        val latestBranch = screen.substringAfter("FitnessTab.LATEST ->").substringBefore("FitnessTab.HISTORY ->")
        val historyBranch = screen.substringAfter("FitnessTab.HISTORY ->").substringBefore("FitnessTab.STANDARD ->")
        val standardBranch = screen.substringAfter("FitnessTab.STANDARD ->").substringBefore("if (state.showLoginDialog)")
        val historyContent = screen.substringAfter("private fun HistoryContent").substringBefore("private fun OverallCard")
        val standardContent = screen.substringAfter("private fun StandardContent").substringBefore("private fun StandardTable")

        assertTrue(latestBranch.contains("onLogin = viewModel::showLogin"))
        assertFalse(historyBranch.contains("onLogin"))
        assertFalse(standardBranch.contains("onLogin"))
        assertFalse(historyContent.substringBefore(") {").contains("onLogin"))
        assertFalse(standardContent.substringBefore(") {").contains("onLogin"))
        assertTrue(screen.contains("请先在“最新成绩”页登录"))
        assertTrue(screen.contains("正在加载历年体测详情"))
        assertTrue(screen.contains("该学期详情暂时无法加载，可点击顶部刷新重试"))
        assertTrue(screen.contains("正在加载评分标准"))
        assertTrue(screen.contains("暂无评分标准，可点击顶部刷新重试"))
    }

    @Test
    fun aboutUsesTheApprovedContributorCopy() {
        val screen = source("AboutScreen.kt")

        assertTrue(screen.contains("label = \"贡献者\""))
        assertTrue(screen.contains("value = \"24人工智能 mzjia\""))
        assertFalse(screen.contains("核心贡献者"))
        assertFalse(screen.contains("24人工智能 m-z-jia"))
    }

    @Test
    fun aboutUsesTheDedicatedMiniProgramIcon() {
        val screen = source("AboutScreen.kt")

        assertTrue(screen.contains("icon = MiniProgramIcon"))
        assertTrue(screen.contains("private val MiniProgramIcon"))
        assertFalse(screen.contains("import androidx.compose.material.icons.outlined.Apps"))
        assertFalse(screen.contains("icon = Icons.Outlined.Apps"))
    }

    @Test
    fun financeCreditColumnsUseCompactAdaptiveWidths() {
        val screen = source("FinanceScreen.kt")

        assertTrue(screen.contains("CREDIT_INDEX_WIDTH = 48.dp"))
        assertTrue(screen.contains("creditColumnWidth(column)"))
        assertTrue(screen.contains("fun creditColumnWidth(column: String): Dp"))
        assertFalse(screen.contains("CREDIT_CELL_WIDTH = 148.dp"))
    }

    @Test
    fun privacyFaqDisclosesActualStorageAndNetworkBoundaries() {
        val faq = source("FaqScreen.kt")

        listOf(
            "维护者能看到吗",
            "直接连接学校体测系统",
            "不会经过项目维护者的服务器",
            "HTTP",
            "无法获得与 HTTPS 相同的传输保护",
            "重置全部数据",
            "公开的问题反馈页面"
        ).forEach { phrase -> assertTrue("Missing privacy FAQ phrase: $phrase", faq.contains(phrase)) }
        assertFalse(faq.contains("我们无法访问你的数据"))
        listOf(
            "glut-api.999314.xyz",
            "Firebase",
            "Crashlytics",
            "Sentry",
            "Cookie",
            "Root",
            "GitHub Issues",
            "FaqListItem.Header(\"新功能\")"
        ).forEach { phrase -> assertFalse("FAQ should not contain: $phrase", faq.contains(phrase)) }
    }

    @Test
    fun faqExplainsTheThreeRecentlyAddedMenus() {
        val faq = source("FaqScreen.kt")

        listOf(
            "“专业成绩”是怎么计算的？",
            "“财务”菜单能做什么？可以直接缴费吗？",
            "“体测成绩”菜单包含哪些功能？"
        ).forEach { question -> assertTrue("Missing FAQ question: $question", faq.contains(question)) }
    }

    @Test
    fun financeResetOpensTheOfficialPageInsteadOfCopyingTheUrl() {
        val screen = source("FinanceScreen.kt")

        assertTrue(screen.contains("uriHandler.openUri(FINANCE_RESET_URL)"))
        assertTrue(screen.contains("前往财务官网重置密码"))
        assertFalse(screen.contains("ClipboardManager"))
        assertFalse(screen.contains("ClipData"))
        assertFalse(screen.contains("密码重置链接已复制"))
    }

    private fun source(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }
}

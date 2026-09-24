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
            // 「体测成绩」菜单那条 FAQ 已按产品要求删除，披露改由「隐私安全」段承载：
            // 那句仍写着「财务和体测都由手机直接连接学校系统」。断言跟着挪到这句话上，
            // 覆盖的仍是同一件事——数据直达学校系统、不经维护者服务器。
            "直接连接学校系统",
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

    // 这条契约原本叫 faqExplainsTheThreeRecentlyAddedMenus，盯着「专业成绩 / 财务 / 体测」
    // 三条菜单介绍。财务与体测两条已按产品要求从 FAQ 删除，剩下的检查改成盯「本机算出来的
    // 统计口径有没有写进 FAQ」——专业成绩与课时统计都属于这一类，正是用户最容易怀疑
    // 「数字是怎么来的」的地方。
    @Test
    fun faqExplainsTheLocallyComputedStatistics() {
        val faq = source("FaqScreen.kt")

        listOf(
            "“专业成绩”是怎么计算的？",
            "课时统计是怎么算出来的？"
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

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
        assertTrue(screen.contains("enabled = !state.isRefreshing"))
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
            "glut-api.999314.xyz",
            "HTTP",
            "无法获得与 HTTPS 相同的传输保护",
            "重置全部数据",
            "GitHub Issues 属于公开页面"
        ).forEach { phrase -> assertTrue("Missing privacy FAQ phrase: $phrase", faq.contains(phrase)) }
        assertFalse(faq.contains("我们无法访问你的数据"))
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

    private fun source(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }
}

package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceUiContractTest {
    @Test
    fun screenKeepsApprovedA1Contract() {
        val source = source("ui/pages/FinanceScreen.kt") + source("data/model/FinanceModels.kt")

        listOf("概览", "缴费", "记录", "学分").forEach { assertTrue(source.contains(it)) }
        listOf("0xFF244F46", "0xFF397267", "0xFFBD573E", "0xFFF5F1E9").forEach { assertTrue(source.contains(it)) }
        assertTrue(source.contains("https://cwjf.glut.edu.cn/home/login"))
        assertTrue(source.contains("https://cwjf.glut.edu.cn/home/mmcz"))
        assertTrue(source.contains("fun FinanceScreen("))
    }

    @Test
    fun financeIsRegisteredInContainerDrawerAndMainDestination() {
        val container = source("ScheduleApplication.kt")
        val drawer = source("ui/navigation/DrawerItem.kt")
        val main = source("MainActivity.kt")

        assertTrue(container.contains("financeApiService") && container.contains("financeStore"))
        assertTrue(drawer.contains("Finance(\"财务\""))
        assertTrue(main.contains("DrawerItem.Finance"))
        assertTrue(main.contains("FinanceViewModelFactory"))
        assertTrue(main.contains("FinanceScreen("))
        assertTrue(main.contains("FinanceViewModelRegistry"))
        assertTrue(main.contains("listOf(DrawerItem.Score, DrawerItem.ProfessionalScore, DrawerItem.GradeExam, DrawerItem.FitnessScore)"))
        assertTrue(main.contains("items(campusDrawerItems(campusType))"))
        assertTrue(Regex("""DrawerItem\.ShuttleBus,\s*DrawerItem\.Finance""").containsMatchIn(drawer))
        assertTrue(!drawer.substringAfter("internal val otherDrawerItems").contains("DrawerItem.Finance"))
    }

    @Test
    fun financeTopBarHasPrivacyToggleAndResetKeepsLazyViewModelReachable() {
        val main = source("MainActivity.kt")

        assertTrue(main.contains("Icons.Outlined.Visibility"))
        assertTrue(main.contains("Icons.Outlined.VisibilityOff"))
        assertTrue(main.contains("viewModel::toggleMoneyVisibility"))
        assertTrue(main.contains("financeViewModels.clearAll()"))
        assertTrue(main.contains("container.financeStore.clearAll()"))
    }

    @Test
    fun loginFormUsesVisibleLightColorsAndCompactWechatLayout() {
        val screen = source("ui/pages/FinanceScreen.kt")

        assertTrue(screen.contains("Dialog("))
        assertTrue(screen.contains("financeTextFieldColors()"))
        assertTrue(screen.contains("if (login.passwordVisible) \"隐藏\" else \"显示\""))
        assertTrue(screen.contains("点击重试"))
        assertTrue(screen.contains("height(48.dp)"))
        assertTrue(screen.contains("if (state.isRefreshing) FinanceMuted else FinancePrimary"))
    }

    @Test
    fun creditTableKeepsHeaderAndCompactIndexVisibleWhileScrolling() {
        val screen = source("ui/pages/FinanceScreen.kt")

        assertTrue(screen.contains("stickyHeader"))
        assertTrue(screen.contains("isIndexColumn"))
        assertTrue(screen.contains("CreditIndexCell"))
        assertTrue(screen.contains("CREDIT_INDEX_WIDTH"))
        assertTrue(screen.contains("height(IntrinsicSize.Min)"))
        assertTrue(screen.contains("Spacer(Modifier.height(CREDIT_SECTION_GAP))"))
        assertTrue(screen.contains("horizontalStates.getValue(sectionIndex)"))
        assertTrue(!screen.contains("val horizontal = rememberScrollState()"))
        assertTrue(screen.contains("Column(Modifier.background(FinancePageBg))"))
        assertTrue(!screen.contains("Text(\"学分结算\", fontWeight = FontWeight.Bold, fontSize = 18.sp)"))
    }

    @Test
    fun everyFinanceMoneySurfaceUsesThePrivacyFormatter() {
        val screen = source("ui/pages/FinanceScreen.kt")

        assertTrue(screen.contains("financeMoneyText("))
        assertTrue(screen.contains("isFinanceMoneyLabel("))
        assertTrue(screen.contains("moneyVisible = state.moneyVisible"))
        assertTrue(screen.contains("if (!header && !moneyVisible && isFinanceMoneyLabel(column))"))
        assertTrue(screen.contains("onDispose(viewModel::hideMoney)"))
        assertTrue(screen.contains("if (item.canPreview && !moneyVisible)"))
    }

    @Test
    fun cachedFeeProjectNamesAreSanitizedAtRenderTime() {
        val screen = source("ui/pages/FinanceScreen.kt")

        assertTrue(screen.contains("displayFinanceItemName(state.module, item.name)"))
    }

    private fun source(path: String): String {
        val module = File("src/main/java/com/glut/schedule/$path")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$path")).readText()
    }
}

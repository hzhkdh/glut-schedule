package com.glut.schedule

import com.glut.schedule.ui.pages.financeMoneyText
import com.glut.schedule.ui.pages.isFinanceMoneyLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceMoneyPrivacyTest {
    @Test
    fun hiddenMoneyUsesStarsWithoutChangingUnderlyingValue() {
        assertEquals("****", financeMoneyText("1280.00", moneyVisible = false))
        assertEquals("1280.00", financeMoneyText("1280.00", moneyVisible = true))
    }

    @Test
    fun moneyColumnsAreDetectedWithoutMaskingCreditsOrSequenceNumbers() {
        assertTrue(isFinanceMoneyLabel("专业课学分收费标准(元/学分)"))
        assertTrue(isFinanceMoneyLabel("应收合计"))
        assertTrue(isFinanceMoneyLabel("单价"))
        assertFalse(isFinanceMoneyLabel("收费序号"))
        assertFalse(isFinanceMoneyLabel("收费项目"))
        assertFalse(isFinanceMoneyLabel("收费状态"))
        assertFalse(isFinanceMoneyLabel("学分"))
        assertFalse(isFinanceMoneyLabel("序号"))
        assertFalse(isFinanceMoneyLabel("专业名称"))
    }
}

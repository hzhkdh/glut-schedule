package com.glut.schedule

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.glut.schedule.partner.PartnerIdentityColor
import com.glut.schedule.partner.PartnerOverlapKind
import com.glut.schedule.partner.PartnerScheduleVisualStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class PartnerScheduleVisualStyleTest {

    @Test
    fun `浅色页面和邀请码卡片的文字满足普通文本对比度`() {
        assertReadable(
            PartnerScheduleVisualStyle.pagePrimaryText,
            PartnerScheduleVisualStyle.pageBackground
        )
        assertReadable(
            PartnerScheduleVisualStyle.inviteContent,
            PartnerScheduleVisualStyle.inviteSurface
        )
        assertReadable(
            PartnerScheduleVisualStyle.inviteAction,
            PartnerScheduleVisualStyle.inviteSurface
        )
    }

    @Test
    fun `粉蓝详情卡片均使用可读的身份色文字`() {
        PartnerIdentityColor.entries.forEach { identityColor ->
            val cardStyle = PartnerScheduleVisualStyle.courseCard(identityColor)
            assertReadable(cardStyle.content, cardStyle.surface)
        }
    }

    @Test
    fun `部分重合只在顶部为右上角角标留位`() {
        val partialPadding = PartnerScheduleVisualStyle.cardContentTopPadding(PartnerOverlapKind.PARTIAL)

        assertTrue(
            "课程内容顶部留位必须大于角标直径",
            partialPadding >= PartnerScheduleVisualStyle.overlapBadgeSize + 2.dp
        )
        assertEquals(
            0.dp,
            PartnerScheduleVisualStyle.cardContentTopPadding(PartnerOverlapKind.EXACT)
        )
    }

    private fun assertReadable(foreground: Color, background: Color) {
        assertTrue(
            "普通文本对比度应不低于 4.5:1，实际为 ${contrastRatio(foreground, background)}",
            contrastRatio(foreground, background) >= 4.5
        )
    }

    /**
     * 按 WCAG 2.x 的相对亮度公式计算对比度，期望值独立于生产实现。
     */
    private fun contrastRatio(first: Color, second: Color): Double {
        val firstLuminance = relativeLuminance(first)
        val secondLuminance = relativeLuminance(second)
        return (max(firstLuminance, secondLuminance) + 0.05) /
            (min(firstLuminance, secondLuminance) + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun linearize(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }
}

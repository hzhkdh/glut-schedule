package com.glut.schedule

import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.glut.schedule.ui.components.periodColumnTextStyle
import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodColumnTextStyleTest {

    @Test
    fun `节次栏文字使用明确行高并移除系统字体额外留白`() {
        val style = periodColumnTextStyle(
            fontSize = 9.sp,
            lineHeight = 11.sp
        )

        assertEquals(9.sp, style.fontSize)
        assertEquals(11.sp, style.lineHeight)
        assertEquals(
            PlatformTextStyle(includeFontPadding = false),
            style.platformStyle
        )
        assertEquals(LineHeightStyle.Alignment.Center, style.lineHeightStyle?.alignment)
        assertEquals(LineHeightStyle.Trim.None, style.lineHeightStyle?.trim)
    }
}

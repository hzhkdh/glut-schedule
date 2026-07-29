package com.glut.schedule

import com.glut.schedule.service.academic.scorePageUnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcademicScorePageClassifierTest {
    @Test
    fun academicPromptPageIsReportedAsUpstreamError() {
        val html = """
            <html>
              <head><title>提示信息</title></head>
              <body><table class="error"><tr><td>null</td></tr></table></body>
            </html>
        """.trimIndent()

        assertEquals("教务成绩页面异常，请稍后重试", scorePageUnavailableReason(html))
    }

    @Test
    fun evaluationBlockKeepsItsSpecificExplanation() {
        val html = """
            <html><body>由于没有参加评教，不能查看成绩</body></html>
        """.trimIndent()

        assertEquals(
            "教务系统提示：有课程未参加评教，暂时不能查看成绩",
            scorePageUnavailableReason(html)
        )
    }

    @Test
    fun normalScorePageIsNotClassifiedAsUnavailable() {
        val html = """
            <html>
              <head><title>学生成绩</title></head>
              <body><table class="datalist"><tr><th>课程名称</th></tr></table></body>
            </html>
        """.trimIndent()

        assertNull(scorePageUnavailableReason(html))
    }
}

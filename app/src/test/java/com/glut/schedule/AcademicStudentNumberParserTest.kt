package com.glut.schedule

import com.glut.schedule.service.parser.AcademicSemesterParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 学号解析的夹具全部是**合成的**，不含任何真实账号信息（沿用网络层测试的惯例）。
 */
class AcademicStudentNumberParserTest {

    @Test
    fun studentNumberIsReadFromStudentInfoInput() {
        val html = """<html><body><input name="xh" value="2022123456"/></body></html>"""

        assertEquals("2022123456", AcademicSemesterParser.parseStudentNumber(html))
    }

    @Test
    fun studentNumberFallsBackToVisibleLabelRow() {
        val html = """
            <table>
              <tr><td>姓名</td><td>张三</td></tr>
              <tr><td>学号：</td><td>2022123456</td></tr>
            </table>
        """.trimIndent()

        assertEquals("2022123456", AcademicSemesterParser.parseStudentNumber(html))
    }

    @Test
    fun implausibleValuesAreRejected() {
        // 太短（内部 id 常见形态）、太长、含非数字——一律不认。
        assertNull(AcademicSemesterParser.parseStudentNumber("""<input name="xh" value="123"/>"""))
        assertNull(
            AcademicSemesterParser.parseStudentNumber("""<input name="xh" value="2022123456789012"/>""")
        )
        assertNull(
            AcademicSemesterParser.parseStudentNumber("""<input name="xh" value="abc20221234"/>""")
        )
        assertNull(AcademicSemesterParser.parseStudentNumber(""))
        assertNull(AcademicSemesterParser.parseStudentNumber("   "))
    }

    @Test
    fun frameJsonUserObjectYieldsStudentNumber() {
        val body = """{"user":{"id":"2022123456","name":"张三"},"schoolCalendarAlias":"2025秋"}"""

        assertEquals("2022123456", AcademicSemesterParser.parseStudentNumberFromFrameJson(body))
    }

    /**
     * `framePage.do` 的响应里到处是纯数字（模块 id、学年 id、周次……）。
     * 只认 `user` 块内、且是合法学号形态的值，否则「换账号清数据」会静默失效。
     */
    @Test
    fun frameJsonNeverMistakesSurroundingNumbersForStudentNumber() {
        assertNull(
            AcademicSemesterParser.parseStudentNumberFromFrameJson(
                """{"moduleId":12345678,"schoolCalendarAlias":"2025秋","yearId":"46"}"""
            )
        )
        assertNull(
            AcademicSemesterParser.parseStudentNumberFromFrameJson("""{"user":{"size":"2"}}""")
        )
        assertNull(
            AcademicSemesterParser.parseStudentNumberFromFrameJson("""{"user":{"id":""}}""")
        )
        assertNull(AcademicSemesterParser.parseStudentNumberFromFrameJson(""))
        assertNull(AcademicSemesterParser.parseStudentNumberFromFrameJson("not json at all"))
    }
}

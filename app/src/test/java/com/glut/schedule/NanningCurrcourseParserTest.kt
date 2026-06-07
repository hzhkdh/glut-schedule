package com.glut.schedule

import com.glut.schedule.service.parser.NanningCurrcourseParser
import org.junit.Assert.*
import org.junit.Test

class NanningCurrcourseParserTest {
    private val parser = NanningCurrcourseParser()

    @Test
    fun returnsEmptyForBlankInput() {
        assertTrue(parser.parsePersonalSchedule("").isEmpty())
        assertTrue(parser.parsePersonalSchedule("   ").isEmpty())
    }

    @Test
    fun returnsEmptyForNonCurrcourseHtml() {
        val html = "<html><body><p>Hello World</p></body></html>"
        assertTrue(parser.parsePersonalSchedule(html).isEmpty())
    }

    @Test
    fun parsesSingleCourseWithOneTimeSlot() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">高等数学</a></td>
                <td class="center"><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=12345' class="infolist">张三</a></td>
                <td><table class="none"><tr>
                    <td>1-18周</td><td>星期一</td><td>第1-2节</td><td>06104</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        val course = courses[0]
        assertEquals("高等数学", course.title)
        assertEquals("张三", course.teacher)
        assertEquals("06104", course.room)
        assertEquals(1, course.occurrences.size)
        val occ = course.occurrences[0]
        assertEquals(1, occ.dayOfWeek)
        assertEquals(1, occ.startSection)
        assertEquals(2, occ.endSection)
        assertEquals("1-18周", occ.weekText)
    }

    @Test
    fun parsesCourseWithMultipleTimeSlots() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">大学英语</a></td>
                <td class="center"><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=67890' class="infolist">李四</a></td>
                <td><table class="none">
                    <tr><td>1-18周</td><td>星期二</td><td>第1-2节</td><td>06201</td></tr>
                    <tr><td>1-18周</td><td>星期四</td><td>第3-4节</td><td>06201</td></tr>
                </table></td>
            </tr></table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        assertEquals(2, courses[0].occurrences.size)
        assertEquals(2, courses[0].occurrences[0].dayOfWeek) // Tuesday
        assertEquals(4, courses[0].occurrences[1].dayOfWeek) // Thursday
    }

    @Test
    fun skipsMoocCourseWithoutNestedTable() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">广播电视概论（慕课）</a></td>
                <td class="center"></td><td></td>
            </tr></table>
        """.trimIndent()

        assertTrue(parser.parsePersonalSchedule(html).isEmpty())
    }

    @Test
    fun handlesEmptyTeacher() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">某课程</a></td>
                <td class="center"></td>
                <td><table class="none"><tr>
                    <td>1-9周</td><td>星期三</td><td>第5-6节</td><td>8301</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        assertEquals("待确认", courses[0].teacher)
    }

    @Test
    fun handlesSingleDoubleWeekNotation() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">体育</a></td>
                <td class="center"><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=11111' class="infolist">王五</a></td>
                <td><table class="none"><tr>
                    <td>1-18周单</td><td>星期五</td><td>第3-4节</td><td>操场</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        assertEquals("1-18周单", courses[0].occurrences[0].weekText)
    }

    @Test
    fun parsesMultipleCourses() {
        val html = """
            <table class="infolist_tab">
                <tr class="infolist_common">
                    <td><a class="infolist">高等数学</a></td>
                    <td class="center"><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=12345' class="infolist">张三</a></td>
                    <td><table class="none"><tr><td>1-18周</td><td>星期一</td><td>第1-2节</td><td>06104</td></tr></table></td>
                </tr>
                <tr class="infolist_common">
                    <td><a class="infolist">大学英语</a></td>
                    <td class="center"><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=67890' class="infolist">李四</a></td>
                    <td><table class="none"><tr><td>1-18周</td><td>星期二</td><td>第1-2节</td><td>06201</td></tr></table></td>
                </tr>
            </table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        assertEquals(2, courses.size)
        assertEquals("高等数学", courses[0].title)
        assertEquals("大学英语", courses[1].title)
    }
}

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
    fun splitsSameCourseDifferentRoomsIntoDistinctCourses() {
        // 安全生产管理: 4个教室 → 拆为4门独立课程
        val html = """
            <table class="infolist_tab">
                <tr class="infolist_common">
                    <td><a class="infolist">安全生产管理</a></td>
                    <td class="center"><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=1' class="infolist">韦有圆</a></td>
                    <td><table class="none">
                        <tr><td>7-14周</td><td>星期二</td><td>第7-8节</td><td>6304D</td></tr>
                        <tr><td>7-9周</td><td>星期三</td><td>第1-2节</td><td>8208D</td></tr>
                        <tr><td>10-13周</td><td>星期三</td><td>第1-2节</td><td>6301D</td></tr>
                        <tr><td>第14周</td><td>星期三</td><td>第1-2节</td><td>6502D</td></tr>
                    </table></td>
                </tr>
            </table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        // 4个不同教室 → 4门独立课程
        assertEquals(4, courses.size)

        val course6502 = courses.single { it.room == "6502D" }
        assertEquals("安全生产管理", course6502.title)
        assertEquals("韦有圆", course6502.teacher)
        assertEquals(3, course6502.occurrences.single().dayOfWeek) // 周三
        assertEquals("第14周", course6502.occurrences.single().weekText)

        val course6304 = courses.single { it.room == "6304D" }
        assertEquals(2, course6304.occurrences.single().dayOfWeek) // 周二
        assertEquals("7-14周", course6304.occurrences.single().weekText)

        val course8208 = courses.single { it.room == "8208D" }
        assertEquals("7-9周", course8208.occurrences.single().weekText)

        val course6301 = courses.single { it.room == "6301D" }
        assertEquals("10-13周", course6301.occurrences.single().weekText)
    }

    @Test
    fun splitsTrainingCourseDifferentRoomsPerDay() {
        // 基础工程实训: 周一/四6310D, 周二6302D, 周三6304D
        val html = """
            <table class="infolist_tab">
                <tr class="infolist_common">
                    <td><a class="infolist">基础工程实训</a></td>
                    <td class="center"><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=1' class="infolist">王俊璇</a></td>
                    <td><table class="none">
                        <tr><td>16-17周</td><td>星期一</td><td>第1-4节</td><td>6310D</td></tr>
                        <tr><td>16-17周</td><td>星期二</td><td>第1-4节</td><td>6302D</td></tr>
                        <tr><td>16-17周</td><td>星期三</td><td>第1-4节</td><td>6304D</td></tr>
                        <tr><td>16-17周</td><td>星期四</td><td>第1-4节</td><td>6310D</td></tr>
                    </table></td>
                </tr>
            </table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        // 3个不同教室 → 3门独立课程 (6310D含周一+周四)
        assertEquals(3, courses.size)

        val course6310 = courses.single { it.room == "6310D" }
        assertEquals(2, course6310.occurrences.size) // 周一+周四
        assertTrue(course6310.occurrences.any { it.dayOfWeek == 1 })
        assertTrue(course6310.occurrences.any { it.dayOfWeek == 4 })

        val course6302 = courses.single { it.room == "6302D" }
        assertEquals(2, course6302.occurrences.single().dayOfWeek)

        val course6304 = courses.single { it.room == "6304D" }
        assertEquals(3, course6304.occurrences.single().dayOfWeek)
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

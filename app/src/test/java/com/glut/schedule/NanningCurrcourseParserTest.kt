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
    fun parsesChineseEnumerationSeparatorUsedByLivePortal() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">高等数学</a></td>
                <td><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=1' class="infolist">张三</a></td>
                <td><table class="none"><tr>
                    <td>1-18周</td><td>星期一</td><td>第1、2节</td><td>06104</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()

        val occurrence = parser.parsePersonalSchedule(html).single().occurrences.single()

        assertEquals(1, occurrence.startSection)
        assertEquals(2, occurrence.endSection)
    }

    @Test
    fun guilinCurrcourseShiftsSectionsFromPeriodFiveBecauseOfNoonSlots() {
        // 桂林的 currcourse.jsdo 与南宁共用 tr.infolist_common + 嵌套 table.none 结构，
        // 而 CompositeScheduleParser 把本解析器排在最前，所以桂林页面也由这里解析。
        // 区别是桂林页面存在「中午1/中午2」：第 5 节起的内部节次号必须 +2。
        //
        // 漏掉偏移会让「第5、6节」落在内部 5/6——正好是中午的两个槽位，关闭「显示中午」
        // 后整门课不显示（实测：数据库原理及应用B 整门课消失）。
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">数据库原理及应用B</a></td>
                <td><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=1' class="infolist">樊婷</a></td>
                <td><table class="none"><tr>
                    <td>1-12周</td><td>星期一</td><td>第5、6节</td><td>07120D</td>
                </tr><tr>
                    <td>1-6周</td><td>星期三</td><td>第7、8节</td><td>07120D</td>
                </tr></table></td>
            </tr></table>
            <table class="infolist_tab"><tr><td>中午1</td><td>中午2</td></tr></table>
        """.trimIndent()

        val occurrences = parser.parsePersonalSchedule(html).single().occurrences

        assertEquals(2, occurrences.size)
        // 第5、6节 → 内部 7、8（中午1/2 占 5、6）
        assertEquals(7, occurrences[0].startSection)
        assertEquals(8, occurrences[0].endSection)
        // 第7、8节 → 内部 9、10
        assertEquals(9, occurrences[1].startSection)
        assertEquals(10, occurrences[1].endSection)
    }

    @Test
    fun nanningCurrcourseKeepsSectionsUnshiftedWhenPageHasNoNoonSlots() {
        // 南宁没有中午时段，节次直排 1-11。这条锁住「修桂林不得误伤南宁」——
        // 同样的「第5、6节」在无中午的页面上必须仍是内部 5、6。
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">高等数学</a></td>
                <td><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=1' class="infolist">张三</a></td>
                <td><table class="none"><tr>
                    <td>1-18周</td><td>星期一</td><td>第5、6节</td><td>06104</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()

        val occurrence = parser.parsePersonalSchedule(html).single().occurrences.single()

        assertEquals(5, occurrence.startSection)
        assertEquals(6, occurrence.endSection)
    }

    @Test
    fun guilinCurrcourseParsesNoonAndMixedNoonSectionRanges() {
        // 真实个人课表既会返回单独的“中午”，也会返回跨中午的混合端点。
        // 三种写法都必须映射到内部连续节次，不能因正则只认数字而静默丢课。
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">匿名课程乙</a></td>
                <td><a href='/academic/manager/teacherinfo/showTeacherInfoItem.do?userid=1' class="infolist">匿名教师乙</a></td>
                <td><table class="none">
                    <tr><td>第1周</td><td>星期一</td><td>中午</td><td>01001D</td></tr>
                    <tr><td>第2周</td><td>星期二</td><td>中午1-第8节</td><td>01001D</td></tr>
                    <tr><td>第3周</td><td>星期三</td><td>第1节-中午2</td><td>01001D</td></tr>
                </table></td>
            </tr></table>
            <table class="infolist_tab"><tr><td>中午1</td><td>中午2</td></tr></table>
        """.trimIndent()

        val occurrences = parser.parsePersonalSchedule(html).single().occurrences
            .sortedBy { it.dayOfWeek }

        assertEquals(listOf(5, 5, 1), occurrences.map { it.startSection })
        assertEquals(listOf(6, 10, 6), occurrences.map { it.endSection })
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

package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.isActiveInWeek
import com.glut.schedule.service.parser.WeeklyTimetableParser
import com.glut.schedule.service.parser.contentMonday
import com.glut.schedule.service.parser.validateFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class WeeklyTimetableParserTest {
    private val parser = WeeklyTimetableParser()

    @Test
    fun parsesSelectedSemesterWeekAndCourseStatuses() {
        val page = parser.parsePage(week17Html(), hasNoon = true)

        assertEquals("2024秋", page.semesterLabel)
        assertEquals(17, page.selectedWeek)
        assertEquals(listOf(1, 17, 20), page.availableWeeks)
        assertEquals(4, page.rows.size)
        assertEquals(2, page.rows.count { it.status == "停课" })
        assertEquals(2, page.rows.count { it.status == "补课" })
        assertTrue(page.rows.all { it.startSection in setOf(7, 9) })
    }

    @Test
    fun parsesChineseSpecialRoomAndAllowsBlankLocations() {
        val page = parser.parsePage(realWeek6Html(), hasNoon = true)
        val safety = page.rows.single { it.title == "大学生安全教育1" }

        assertEquals("雁8号楼一层安全教育基地", safety.room)
        assertTrue(page.rows.any { it.title == "体育1" && it.room.isBlank() })
        assertEquals(LocalDate.of(2024, 10, 7), page.contentMonday())
    }

    @Test
    fun validateForRejectsDateAndWeekdayConflict() {
        val page = parser.parsePage(
            weeklyHtml(
                week = 6,
                rows = courseRow(date = "2024-10-08", weekday = "星期一")
            ),
            hasNoon = true
        )

        assertThrows(IllegalArgumentException::class.java) {
            page.validateFor(6, "2024秋", LocalDate.of(2024, 9, 2))
        }
    }

    @Test
    fun validateForRejectsRowsFromDifferentCalendarWeeks() {
        val page = parser.parsePage(
            weeklyHtml(
                week = 6,
                rows = courseRow(date = "2024-10-07", weekday = "星期一") +
                    courseRow(date = "2024-10-14", weekday = "星期一")
            ),
            hasNoon = true
        )

        assertThrows(IllegalArgumentException::class.java) {
            page.validateFor(6, "2024秋", LocalDate.of(2024, 9, 2))
        }
    }

    @Test
    fun validateForRejectsSemesterMondayConflict() {
        val page = parser.parsePage(realWeek6Html(), hasNoon = true)

        assertThrows(IllegalArgumentException::class.java) {
            page.validateFor(6, "2024秋", LocalDate.of(2024, 9, 9))
        }
    }

    @Test
    fun validateForRejectsUnexpectedSemesterLabel() {
        val page = parser.parsePage(realWeek6Html(), hasNoon = true)

        assertThrows(IllegalArgumentException::class.java) {
            page.validateFor(6, "2025春", LocalDate.of(2024, 9, 2))
        }
    }

    @Test
    fun validateForRejectsUnexpectedSelectedWeek() {
        val page = parser.parsePage(realWeek6Html(), hasNoon = true)

        assertThrows(IllegalArgumentException::class.java) {
            page.validateFor(7, "2024秋", LocalDate.of(2024, 9, 2))
        }
    }

    @Test
    fun parsesRealEmptyWeekAsValidPage() {
        val page = parser.parsePage(
            weeklyHtml(week = 6, rows = "<tr><td colspan=\"12\">&nbsp;</td></tr>"),
            hasNoon = true
        )

        assertTrue(page.rows.isEmpty())
        assertEquals(null, page.contentMonday())
        assertEquals(
            LocalDate.of(2024, 9, 2),
            page.validateFor(6, "2024秋", LocalDate.of(2024, 9, 2))
        )
    }

    @Test
    fun rejectsMissingOrUnrecognizedMainCourseTable() {
        val missingTable = "<html><body><span>2024秋</span></body></html>"
        val unrecognizedTable = weeklyHtml(week = 6, rows = "")
            .replace("<th>日期</th>", "<th>上课日期</th>")

        listOf(missingTable, unrecognizedTable).forEach { html ->
            assertThrows(IllegalArgumentException::class.java) {
                parser.parsePage(html, hasNoon = true)
            }
        }
    }

    @Test
    fun skipsNonEmptyCourseRowWithMissingColumns() {
        // 缺列的课程行被静默跳过，不再中断整个页面解析
        val malformedRow = "<tr><td>2024-10-07</td><td>测试课程</td></tr>"

        val page = parser.parsePage(weeklyHtml(week = 6, rows = malformedRow), hasNoon = true)
        assertTrue("缺少必要列的行应被跳过", page.rows.isEmpty())
    }

    @Test
    fun skipsUnparseableCourseDate() {
        // 日期格式无效的行被静默跳过，不再中断整个页面解析
        val page = parser.parsePage(
            weeklyHtml(week = 6, rows = courseRow(date = "2024-02-30")),
            hasNoon = true
        )
        assertTrue("日期不存在的行应被跳过", page.rows.isEmpty())
    }

    @Test
    fun skipsUnparseableCourseWeekday() {
        // 非法星期的行被静默跳过，不再中断整页解析
        val page = parser.parsePage(
            weeklyHtml(week = 6, rows = courseRow(weekday = "周八")),
            hasNoon = true
        )
        assertTrue("星期无效的行应被跳过", page.rows.isEmpty())
    }

    @Test
    fun skipsUnparseableSectionRange() {
        listOf("实验课", "第1节错误").forEach { section ->
            val page = parser.parsePage(
                weeklyHtml(week = 6, rows = courseRow(section = section)),
                hasNoon = true
            )
            assertTrue("节次无法解析的课程行应被跳过: $section", page.rows.isEmpty())
        }
    }

    @Test
    fun skipsReversedSectionRange() {
        // 节次倒置的行被静默跳过（start > end 在 mapSection 后校验失败）
        val page = parser.parsePage(
            weeklyHtml(week = 6, rows = courseRow(section = "第4、3节")),
            hasNoon = true
        )
        assertTrue("节次范围倒置的行应被跳过", page.rows.isEmpty())
    }

    @Test
    fun skipsCampusSpecificOutOfRangeSections() {
        // 超出校区节次范围的行被静默跳过
        val page = parser.parsePage(
            weeklyHtml(week = 6, rows = courseRow(section = "第13节")),
            hasNoon = true
        )
        assertTrue("节次超出范围的行应被跳过(桂林max=12)", page.rows.isEmpty())
    }

    @Test
    fun skipsNanningOutOfRangeSection12() {
        // 南宁第12节超出范围(南宁max=11)，应被静默跳过
        val page = parser.parsePage(
            weeklyHtml(week = 6, rows = courseRow(section = "第12节")),
            hasNoon = false
        )
        assertTrue("南宁第12节超出范围应被跳过", page.rows.isEmpty())
    }

    @Test
    fun mapsCampusSpecificSectionBounds() {
        val guilin = parser.parsePage(
            weeklyHtml(week = 6, rows = courseRow(section = "第12节")),
            hasNoon = true
        )
        val nanning = parser.parsePage(
            weeklyHtml(week = 6, rows = courseRow(section = "第11节")),
            hasNoon = false
        )

        assertEquals(14, guilin.rows.single().startSection)
        assertEquals(14, guilin.rows.single().endSection)
        assertEquals(11, nanning.rows.single().startSection)
        assertEquals(11, nanning.rows.single().endSection)
    }

    @Test
    fun blankRoomFallsBackToBuilding() {
        val page = parser.parsePage(
            weeklyHtml(
                week = 6,
                rows = courseRow(building = "雁山12号楼", room = "")
            ),
            hasNoon = true
        )

        assertEquals("雁山12号楼", page.rows.single().room)
    }

    @Test
    fun mergeUsesWeeklyRowsAsFinalOccurrences() {
        val base = listOf(
            course("体育1", "朱涌", "体育训练馆", "6-14双,16-18", 1, 3, 4),
            course("C语言程序设计", "杨呈永", "014202J", "6-19周", 3, 7, 8)
        )
        val pages = listOf(
            parser.parsePage(sportsWeekHtml(6), hasNoon = true),
            parser.parsePage(sportsWeekHtml(8), hasNoon = true),
            parser.parsePage(sportsWeekHtml(16), hasNoon = true),
            parser.parsePage(sportsWeekHtml(17), hasNoon = true),
            parser.parsePage(week17Html(), hasNoon = true)
        )

        val merged = parser.mergeWithMetadata(base, pages)
        val sports = merged.single { it.title == "体育1" }
        val cLanguage = merged.filter { it.title == "C语言程序设计" }

        assertTrue(sports.occurrences.single().isActiveInWeek(6))
        assertTrue(sports.occurrences.single().isActiveInWeek(8))
        assertTrue(sports.occurrences.single().isActiveInWeek(16))
        assertTrue(sports.occurrences.single().isActiveInWeek(17))
        assertFalse(sports.occurrences.single().isActiveInWeek(7))
        assertEquals(2, cLanguage.flatMap { it.occurrences }.count { it.isActiveInWeek(17) })
        assertTrue(cLanguage.flatMap { it.occurrences }.all { it.dayOfWeek == 5 })
    }

    private fun course(
        title: String,
        teacher: String,
        room: String,
        weekText: String,
        day: Int,
        start: Int,
        end: Int
    ): ScheduleCourse {
        val id = "base-$title-$room"
        return ScheduleCourse(
            id = id,
            title = title,
            room = room,
            teacher = teacher,
            colorHex = "#4477AA",
            occurrences = listOf(CourseOccurrence("$id-occ", id, day, start, end, weekText, room))
        )
    }

    private fun sportsWeekHtml(week: Int) = weeklyHtml(
        week = week,
        rows = """
            <tr><td>2024-10-07</td><td>体育1</td><td>必修</td><td>正常考试</td>
            <td>上午第三节</td><td>星期一</td><td>第3、4节</td><td>10:20</td><td>12:00</td>
            <td>雁山操场</td><td>体育训练馆</td><td></td></tr>
        """.trimIndent()
    )

    private fun week17Html() = weeklyHtml(
        week = 17,
        rows = """
            <tr><td>2024-12-25</td><td>C语言程序设计</td><td>必修</td><td>正常考试</td>
            <td>下午第五节</td><td>星期三</td><td>第5、6节</td><td>14:30</td><td>16:10</td>
            <td>雁山14号楼</td><td>014202J</td><td>停课</td></tr>
            <tr><td>2024-12-25</td><td>C语言程序设计</td><td>必修</td><td>正常考试</td>
            <td>下午第七节</td><td>星期三</td><td>第7、8节</td><td>16:20</td><td>18:00</td>
            <td>雁山14号楼</td><td>014104J</td><td>停课</td></tr>
            <tr><td>2024-12-27</td><td>C语言程序设计</td><td>必修</td><td>正常考试</td>
            <td>下午第五节</td><td>星期五</td><td>第5、6节</td><td>14:30</td><td>16:10</td>
            <td>雁山14号楼</td><td>014202J</td><td>补课</td></tr>
            <tr><td>2024-12-27</td><td>C语言程序设计</td><td>必修</td><td>正常考试</td>
            <td>下午第七节</td><td>星期五</td><td>第7、8节</td><td>16:20</td><td>18:00</td>
            <td>雁山14号楼</td><td>014104J</td><td>补课</td></tr>
        """.trimIndent()
    )

    private fun realWeek6Html() = weeklyHtml(
        week = 6,
        rows = """
            <tr><td>2024-10-09</td><td>大学生安全教育1</td><td>必修</td><td>正常考试</td>
            <td>上午第一节</td><td>星期三</td><td>第1、2节</td><td>08:00</td><td>09:40</td>
            <td>雁山8号楼</td><td>雁8号楼一层安全教育基地</td><td></td></tr>
            <tr><td>2024-10-07</td><td>体育1</td><td>必修</td><td>正常考试</td>
            <td>上午第三节</td><td>星期一</td><td>第3、4节</td><td>10:20</td><td>12:00</td>
            <td></td><td></td><td></td></tr>
        """.trimIndent()
    )

    private fun courseRow(
        date: String = "2024-10-07",
        weekday: String = "星期一",
        section: String = "第1、2节",
        building: String = "雁山1号楼",
        room: String = "00101"
    ) = """
        <tr><td>$date</td><td>测试课程</td><td>必修</td><td>正常考试</td>
        <td>上午第一节</td><td>$weekday</td><td>$section</td><td>08:00</td><td>09:40</td>
        <td>$building</td><td>$room</td><td></td></tr>
    """.trimIndent()

    private fun weeklyHtml(week: Int, rows: String) = """
        <html><body>
        <table class="layout"><tr><td>
        <form><span>2024秋 第 </span><select name="whichWeek">
          <option value="1">1</option><option value="17" ${if (week == 17) "selected" else ""}>17</option>
          <option value="20">20</option><option value="$week" selected>$week</option>
        </select><span> 周 周次课表</span></form>
        <table class="infolist_hr">
          <tr><th>日期</th><th>课程名</th><th>选课属性</th><th>考试性质</th><th>课节名称</th><th>星期</th>
          <th>节次</th><th>开始时间</th><th>结束时间</th><th>教学楼</th><th>教室</th><th></th></tr>
          $rows
        </table>
        </td></tr></table>
        </body></html>
    """.trimIndent()
}

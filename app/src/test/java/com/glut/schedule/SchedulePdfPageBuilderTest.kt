package com.glut.schedule

import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.model.yanshanClassPeriods
import com.glut.schedule.ui.pages.buildPdfWeekPage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulePdfPageBuilderTest {

    private val semesterStart = LocalDate.of(2026, 9, 7)

    private fun block(
        title: String = "高等数学",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        room: String = "06101",
        teacher: String = "张三",
        id: String = "occ-$title-$day-$start"
    ): CourseBlock {
        val occurrence = CourseOccurrence(
            id = id,
            courseId = "course-$title",
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            weekText = "1-16周",
            note = room
        )
        return CourseBlock(
            course = ScheduleCourse(
                id = "course-$title",
                title = title,
                room = room,
                teacher = teacher,
                colorHex = "#4477AA",
                occurrences = listOf(occurrence)
            ),
            occurrence = occurrence
        )
    }

    private fun page(
        blocks: List<CourseBlock>,
        showNoon: Boolean = false,
        showWeekend: Boolean = false,
        showTeacher: Boolean = true,
        showRoom: Boolean = true,
        showCalendarDates: Boolean = true,
        adjustments: List<SemesterAdjustment> = emptyList()
    ) = buildPdfWeekPage(
        semesterName = "2026-2027学年第一学期",
        week = scheduleWeekForNumber(9, semesterStart, 20),
        blocks = blocks,
        periods = yanshanClassPeriods(),
        showNoon = showNoon,
        hasNoonSections = true,
        showWeekend = showWeekend,
        showCalendarDates = showCalendarDates,
        holidayDates = emptySet(),
        manualAdjustmentDates = emptySet(),
        adjustments = adjustments,
        showTeacher = showTeacher,
        showRoom = showRoom
    )

    /** 无权威校历时，图片和 PDF 的所有日期位置都必须留空。 */
    @Test fun estimatedDatesStayOutOfExports() {
        val page = page(emptyList(), showCalendarDates = false)
        assertEquals("", page.subtitle)
        assertEquals("", page.monthText)
        assertTrue(page.days.all { it.dateText.isNullOrBlank() })
    }

    @Test
    fun hiddenNoonAndWeekendShrinkTheGrid() {
        val page = page(blocks = emptyList(), showNoon = false, showWeekend = false)

        // 桂林 14 节，关掉中午后 12 行；周末关掉后 5 列。
        assertEquals(12, page.rows.size)
        assertEquals(5, page.days.size)
        assertEquals("1", page.rows.first().label)
        assertEquals(listOf("一", "二", "三", "四", "五"), page.days.map { it.weekday })
        assertEquals(listOf("2", "3", "4", "5", "6"), page.days.map { it.dateText })
    }

    @Test
    fun courseRowsFollowTheVisibleSectionMap() {
        val page = page(
            blocks = listOf(
                block(title = "第一节", start = 1, end = 2, day = 1),
                block(title = "下午课", start = 7, end = 8, day = 2),
                block(title = "午课", start = 5, end = 6, day = 3)
            )
        )

        // 午课在关闭中午时整行不占位，也不该出现在页面上。
        assertEquals(2, page.slots.size)
        val first = page.slots.first { it.title == "第一节" }
        assertEquals(0, first.rowIndex)
        assertEquals(0, first.columnIndex)
        assertEquals(2, first.rowSpan)
        val afternoon = page.slots.first { it.title == "下午课" }
        assertEquals(4, afternoon.rowIndex)
        assertEquals(1, afternoon.columnIndex)
    }

    @Test
    fun noonAndWeekendCanBeIncluded() {
        val page = page(
            blocks = listOf(
                block(title = "午课", start = 5, end = 6, day = 3),
                block(title = "周末课", day = 6, start = 1, end = 2)
            ),
            showNoon = true,
            showWeekend = true
        )

        assertEquals(14, page.rows.size)
        assertEquals(7, page.days.size)
        assertEquals(2, page.slots.size)
        assertEquals(4, page.slots.first { it.title == "午课" }.rowIndex)
        assertEquals(5, page.slots.first { it.title == "周末课" }.columnIndex)
    }

    @Test
    fun conflictingCoursesShareOneSlotWithCount() {
        val page = page(
            blocks = listOf(
                block(title = "课程 A", day = 2, start = 3, end = 4, id = "a"),
                block(title = "课程 B", day = 2, start = 3, end = 4, id = "b")
            )
        )

        assertEquals(1, page.slots.size)
        assertEquals(2, page.slots.first().conflictCount)
    }

    @Test
    fun teacherAndRoomCanBeOmitted() {
        val page = page(
            blocks = listOf(block()),
            showTeacher = false,
            showRoom = false
        )

        assertEquals("", page.slots.first().teacher)
        assertEquals("", page.slots.first().room)
    }

    @Test
    fun suspendedCourseStillCarriesTheMarker() {
        val adjustment = SemesterAdjustment(
            id = "stop-1",
            type = "停课",
            title = "高等数学",
            teacher = "张三",
            originalWeek = 9,
            originalDay = 1,
            originalStartSection = 1,
            originalEndSection = 2,
            originalRoom = "06101",
            makeupWeek = 0,
            makeupDay = 0,
            makeupStartSection = 0,
            makeupEndSection = 0,
            makeupRoom = ""
        )
        val page = page(blocks = listOf(block()), adjustments = listOf(adjustment))

        assertEquals("停", page.slots.first().marker)
        assertTrue(page.title.contains("第 9 周"))
        assertTrue(page.subtitle.contains("11月2日"))
    }
}

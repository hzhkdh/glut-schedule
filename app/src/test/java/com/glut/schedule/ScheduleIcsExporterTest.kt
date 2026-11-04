package com.glut.schedule

import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.graduateClassPeriods
import com.glut.schedule.data.model.yanshanClassPeriods
import com.glut.schedule.service.export.ScheduleIcsResult
import com.glut.schedule.service.export.buildScheduleIcs
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导出的日历必须与屏幕上的课表同源：周次、节次、停课与调课都在这里钉住。
 */
class ScheduleIcsExporterTest {

    private val semesterStart = LocalDate.of(2026, 9, 7) // 周一

    private fun block(
        title: String = "高等数学",
        room: String = "06101",
        teacher: String = "张三",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2
    ): CourseBlock {
        val occurrence = CourseOccurrence(
            id = "occ-$title-$day-$start",
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

    private fun build(
        blocksByWeek: Map<Int, List<CourseBlock>>,
        maxWeek: Int = 2,
        adjustments: List<SemesterAdjustment> = emptyList(),
        periods: List<com.glut.schedule.data.model.ClassPeriod> = yanshanClassPeriods(),
        includeRoom: Boolean = true,
        includeTeacher: Boolean = true
    ): ScheduleIcsResult = buildScheduleIcs(
        calendarName = "2026-2027学年第一学期",
        blocksByWeek = blocksByWeek,
        periods = periods,
        semesterStartMonday = semesterStart,
        maxWeek = maxWeek,
        adjustments = adjustments,
        hasNoonSections = true,
        includeRoom = includeRoom,
        includeTeacher = includeTeacher,
        generatedAt = Instant.parse("2026-09-01T00:00:00Z")
    )

    @Test
    fun writesOneEventPerWeekWithCampusTimesInUtc() {
        val result = build(
            blocksByWeek = mapOf(1 to listOf(block()), 2 to listOf(block()))
        ) as ScheduleIcsResult.Success

        assertEquals(2, result.eventCount)
        // 08:30–10:05（Asia/Shanghai）→ 00:30–02:05 UTC
        assertTrue(result.content.contains("DTSTART:20260907T003000Z"))
        assertTrue(result.content.contains("DTEND:20260907T020500Z"))
        assertTrue(result.content.contains("DTSTART:20260914T003000Z"))
        assertTrue(result.content.contains("SUMMARY:高等数学"))
        assertTrue(result.content.contains("LOCATION:06101"))
        assertTrue(result.content.contains("DESCRIPTION:教师：张三\\n节次：1-2\\n周次：第1周"))
        assertTrue(result.content.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(result.content.endsWith("END:VCALENDAR\r\n"))
        assertEquals(2, result.content.split("BEGIN:VEVENT").size - 1)
    }

    @Test
    fun excludesSuspendedWeeks() {
        val adjustment = SemesterAdjustment(
            id = "stop-1",
            type = "停课",
            title = "高等数学",
            teacher = "张三",
            originalWeek = 1,
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
        val result = build(
            blocksByWeek = mapOf(1 to listOf(block()), 2 to listOf(block())),
            adjustments = listOf(adjustment)
        ) as ScheduleIcsResult.Success

        assertEquals(1, result.eventCount)
        assertTrue(result.content.contains("DTSTART:20260914T003000Z"))
        assertTrue(!result.content.contains("DTSTART:20260907T003000Z"))
    }

    @Test
    fun marksMakeupOccurrences() {
        val adjustment = SemesterAdjustment(
            id = "move-1",
            type = "调课",
            title = "高等数学",
            teacher = "张三",
            originalWeek = 1,
            originalDay = 1,
            originalStartSection = 1,
            originalEndSection = 2,
            originalRoom = "06101",
            makeupWeek = 2,
            makeupDay = 3,
            makeupStartSection = 7,
            makeupEndSection = 8,
            makeupRoom = "06202"
        )
        val makeupBlock = block(day = 3, start = 7, end = 8)
        val result = build(
            blocksByWeek = mapOf(1 to listOf(block()), 2 to listOf(makeupBlock)),
            adjustments = listOf(adjustment)
        ) as ScheduleIcsResult.Success

        assertEquals(2, result.eventCount)
        assertTrue(result.content.contains("SUMMARY:高等数学 [调]"))
    }

    @Test
    fun escapesRfc5545SpecialCharacters() {
        val result = build(
            blocksByWeek = mapOf(
                1 to listOf(block(title = "数据结构;实验,一", room = "A,B;C"))
            )
        ) as ScheduleIcsResult.Success

        assertTrue(result.content.contains("SUMMARY:数据结构\\;实验\\,一"))
        assertTrue(result.content.contains("LOCATION:A\\,B\\;C"))
    }

    @Test
    fun foldsLongLinesWithoutBreakingUtf8OrTheOctetLimit() {
        val longTitle = "数据结构与算法综合实验课程设计".repeat(4)
        val result = build(
            blocksByWeek = mapOf(1 to listOf(block(title = longTitle)))
        ) as ScheduleIcsResult.Success

        val physicalLines = result.content.split("\r\n").filter { it.isNotEmpty() }
        assertTrue(physicalLines.size > 1)
        physicalLines.forEach { line ->
            assertTrue(
                "折行后每行不得超过 75 个八位组：$line",
                line.toByteArray(Charsets.UTF_8).size <= 75
            )
        }
        // 折行还原后必须仍然是完整的 UTF-8 文本。
        val unfolded = result.content.replace("\r\n ", "")
        assertTrue(unfolded.contains("SUMMARY:$longTitle"))
    }

    @Test
    fun reportsMissingTimesForGraduateProfiles() {
        val result = build(
            blocksByWeek = mapOf(1 to listOf(block())),
            periods = graduateClassPeriods()
        )

        assertEquals(ScheduleIcsResult.MissingClassTimes, result)
    }

    @Test
    fun reportsNoEventsForAnEmptySchedule() {
        val result = build(blocksByWeek = emptyMap())

        assertEquals(ScheduleIcsResult.NoEvents, result)
    }

    @Test
    fun canOmitTeacherAndRoom() {
        val result = build(
            blocksByWeek = mapOf(1 to listOf(block())),
            includeRoom = false,
            includeTeacher = false
        ) as ScheduleIcsResult.Success

        assertTrue(!result.content.contains("LOCATION:"))
        assertTrue(!result.content.contains("教师："))
        assertTrue(result.content.contains("DESCRIPTION:节次：1-2\\n周次：第1周"))
    }
}

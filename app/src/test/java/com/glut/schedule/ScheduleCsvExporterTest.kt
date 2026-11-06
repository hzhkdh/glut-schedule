package com.glut.schedule

import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.service.export.ScheduleCsvResult
import com.glut.schedule.service.export.buildScheduleCsv
import com.glut.schedule.service.export.csvSectionLabel
import com.glut.schedule.service.export.escapeCsvField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleCsvExporterTest {

    private fun block(
        courseId: String = "c1",
        title: String = "高等数学",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        room: String = "06101",
        teacher: String = "张三",
        occurrenceId: String = "$courseId-o1",
        weekText: String = "1-16周"
    ): CourseBlock {
        val occurrence = CourseOccurrence(
            id = occurrenceId,
            courseId = courseId,
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            weekText = weekText,
            note = room
        )
        return CourseBlock(
            course = ScheduleCourse(
                id = courseId,
                title = title,
                room = room,
                teacher = teacher,
                colorHex = "#4477AA",
                occurrences = listOf(occurrence)
            ),
            occurrence = occurrence
        )
    }

    @Test
    fun writesBomHeaderAndOneRowPerDistinctOccurrence() {
        val regular = block()
        val copy = block(
            courseId = "c2",
            title = "大学英语",
            day = 3,
            start = 5,
            end = 6,
            occurrenceId = "c2-o1-manual-copy-2026-10-06",
            weekText = "第7周"
        )
        // 常规课次每周都会出现，必须按课次 id 去重；调休副本只出现一次。
        val result = buildScheduleCsv(
            blocksByWeek = mapOf(1 to listOf(regular), 2 to listOf(regular, copy))
        ) as ScheduleCsvResult.Success

        assertEquals(2, result.rowCount)
        assertTrue(result.content.startsWith("\uFEFF课程,星期,节次,周次,教室,教师\r\n"))
        assertTrue(result.content.contains("高等数学,周一,第1-2节,1-16周,06101,张三\r\n"))
        assertTrue(result.content.contains("大学英语,周三,午1-午2,第7周,06101,张三\r\n"))
    }

    @Test
    fun ordersRowsByWeekdayAndSection() {
        val result = buildScheduleCsv(
            blocksByWeek = mapOf(
                1 to listOf(
                    block(courseId = "c3", title = "晚课", day = 5, start = 11, end = 12),
                    block(courseId = "c2", title = "早课", day = 2, start = 1, end = 2),
                    block(courseId = "c1", title = "同日上午", day = 2, start = 3, end = 4)
                )
            )
        ) as ScheduleCsvResult.Success

        val lines = result.content.split("\r\n").filter { it.isNotBlank() }
        assertTrue(lines[1].contains("早课"))
        assertTrue(lines[2].contains("同日上午"))
        assertTrue(lines[3].contains("晚课"))
    }

    @Test
    fun sectionLabelsStayTextInExcel() {
        // Excel 会把裸的 3-4 识别成日期；加「第…节」后不再触发。
        assertEquals("第3-4节", csvSectionLabel("3-4"))
        assertEquals("第6节", csvSectionLabel("6"))
        assertEquals("午1-午2", csvSectionLabel("午1-午2"))
    }

    @Test
    fun escapesCommasQuotesAndNewlines() {
        assertEquals("\"a,b\"", escapeCsvField("a,b"))
        assertEquals("\"他说\"\"你好\"\"\"", escapeCsvField("他说\"你好\""))
        assertEquals("\"第一行\n第二行\"", escapeCsvField("第一行\n第二行"))
        assertEquals("普通文本", escapeCsvField("普通文本"))

        val result = buildScheduleCsv(
            blocksByWeek = mapOf(
                1 to listOf(block(title = "数据结构,实验", teacher = "李\"四"))
            )
        ) as ScheduleCsvResult.Success
        assertTrue(result.content.contains("\"数据结构,实验\",周一,第1-2节,1-16周,06101,\"李\"\"四\""))
    }

    @Test
    fun fallsBackToCourseRoomWhenOccurrenceNoteIsBlank() {
        val block = block(room = "06101")
        val withoutNote = block.copy(
            occurrence = block.occurrence.copy(note = "")
        )
        val result = buildScheduleCsv(blocksByWeek = mapOf(1 to listOf(withoutNote))) as ScheduleCsvResult.Success

        assertTrue(result.content.contains(",06101,"))
    }

    @Test
    fun canOmitRoomAndTeacherColumns() {
        val result = buildScheduleCsv(
            blocksByWeek = mapOf(1 to listOf(block())),
            includeRoom = false,
            includeTeacher = false
        ) as ScheduleCsvResult.Success

        assertTrue(result.content.startsWith("\uFEFF课程,星期,节次,周次\r\n"))
        assertTrue(result.content.contains("高等数学,周一,第1-2节,1-16周\r\n"))
    }

    @Test
    fun reportsNoRowsForAnEmptySchedule() {
        assertEquals(ScheduleCsvResult.NoRows, buildScheduleCsv(emptyMap()))
    }
}

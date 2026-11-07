package com.glut.schedule

import com.glut.schedule.service.export.calendarPreviewEvents
import com.glut.schedule.service.export.csvPreviewRows
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

/** 预览读取真实导出文本，转义、换行和本地时区必须与文件内容保持一致。 */
class ExportPreviewModelsTest {
    @Test fun csvPreservesQuotedCommaQuoteAndLineBreak() {
        val text = "\uFEFF课程,备注\r\n\"课程,甲\",\"第一行\n第二行\"\r\n\"含\"\"引号\",普通\r\n"
        val rows = csvPreviewRows(text)
        assertEquals(listOf("课程", "备注"), rows[0])
        assertEquals(listOf("课程,甲", "第一行\n第二行"), rows[1])
        assertEquals(listOf("含\"引号", "普通"), rows[2])
    }

    @Test fun csvLimitDoesNotTruncateQuotedRows() {
        assertEquals(listOf(listOf("a", "b")), csvPreviewRows("a,b\n1,2\n3,4", 1))
        assertTrue(csvPreviewRows("", 3).isEmpty())
        assertTrue(csvPreviewRows("a,b", 0).isEmpty())
    }

    @Test fun calendarPreviewUnfoldsAndConvertsUtcToLocalTime() {
        val content = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART:20261102T003000Z\r\nSUMMARY:示例课程\\,甲\r\n 续行\r\nLOCATION:教学楼\\;101\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val event = calendarPreviewEvents(content, zone = ZoneId.of("Asia/Shanghai")).single()
        assertEquals("示例课程,甲续行", event.title)
        assertEquals("11月2日 08:30", event.time)
        assertEquals("教学楼;101", event.location)
    }

    @Test fun calendarPreviewLimitsEventsAndDoesNotInventMissingEvents() {
        val event = "BEGIN:VEVENT\nDTSTART:20261102T003000Z\nSUMMARY:示例\nEND:VEVENT\n"
        assertEquals(2, calendarPreviewEvents(event.repeat(4), 2).size)
        assertTrue(calendarPreviewEvents("BEGIN:VCALENDAR\nEND:VCALENDAR").isEmpty())
    }
}

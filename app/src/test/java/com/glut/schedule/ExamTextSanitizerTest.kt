package com.glut.schedule

import com.glut.schedule.data.local.ExamEntity
import com.glut.schedule.data.local.toModel
import com.glut.schedule.data.model.cleanExamText
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamTextSanitizerTest {
    @Test
    fun cleanExamTextRemovesHtmlSpaceEntitiesAndCollapsesWhitespace() {
        assertEquals("05307D", cleanExamText("05307D&nbsp;\n&nbsp;"))
        assertEquals("06206D", cleanExamText("06206D&NBSP;"))
        assertEquals("A & B", cleanExamText("A&nbsp;&amp;&nbsp;B"))
    }

    @Test
    fun examEntityToModelCleansLegacyStoredExamText() {
        val model = ExamEntity(
            id = "legacy",
            courseName = "数字逻辑&nbsp;",
            examDate = LocalDate.of(2026, 6, 16).toString(),
            startTime = "12:30",
            endTime = "14:20",
            location = "06206D&nbsp;",
            seatNumber = "&nbsp;",
            examType = "正常考试",
            note = "备注&nbsp;&lt;确认&gt;"
        ).toModel()

        assertEquals("数字逻辑", model.courseName)
        assertEquals("06206D", model.location)
        assertEquals("", model.seatNumber)
        assertEquals("备注 <确认>", model.note)
    }
}

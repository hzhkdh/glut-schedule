package com.glut.schedule

import com.glut.schedule.service.chaoxing.HomeworkState
import com.glut.schedule.service.chaoxing.homeworkState
import com.glut.schedule.service.chaoxing.parseRemainingMinutes
import com.glut.schedule.service.chaoxing.parseHomeworkList
import com.glut.schedule.service.chaoxing.hasRecognizableHomeworkList
import org.junit.Assert.assertEquals
import org.junit.Test

class ChaoxingHomeworkTest {
    @Test
    fun recognizesVerifiedEmptyWorkPageWithoutAcceptingErrorText() {
        val empty = """<div class="main task-list"><div class="has-content"><div class="null-data">暂无作业</div></div></div>"""
        org.junit.Assert.assertTrue(hasRecognizableHomeworkList(empty))
        org.junit.Assert.assertTrue(parseHomeworkList(empty, 0).isEmpty())
        org.junit.Assert.assertFalse(hasRecognizableHomeworkList("<p>暂无作业</p>"))
        org.junit.Assert.assertFalse(hasRecognizableHomeworkList("<div class='task-list'><div class='has-content'><div class='null-data'>无权限的操作</div></div></div>"))
    }
    @Test
    fun unknownStatusDoesNotBecomeCompleted() {
        assertEquals(HomeworkState.UNKNOWN, homeworkState("未知状态"))
        assertEquals(HomeworkState.TODO, homeworkState("未交"))
        assertEquals(HomeworkState.DONE, homeworkState("待批阅"))
    }

    @Test
    fun parsesCountdownAndPreservesMissingDeadline() {
        assertEquals(1562L, parseRemainingMinutes("剩余1天2小时2分钟"))
        assertEquals(null, parseRemainingMinutes("没有截止时间"))
        val rows = parseHomeworkList("""<ul><li aria-label="练习;未交"><div class="time notOver">剩余2小时</div></li><li aria-label="其他;未交"></li></ul>""", 1000L)
        assertEquals(2, rows.size)
        assertEquals(7201000L, rows[0].deadlineMillis)
        assertEquals(null, rows[1].deadlineMillis)
    }

    @Test
    fun usesLastSeparatorAndDoesNotInventDeadlines() {
        val rows = parseHomeworkList("""<ul><li aria-label="章节;练习;未交"><div class="time">已截止</div></li><li aria-label="其他;已互评"></li></ul>""", 1000L)
        assertEquals("章节;练习", rows[0].title)
        assertEquals(null, rows[0].deadlineMillis)
        assertEquals(HomeworkState.UNKNOWN, homeworkState(rows[1].status))
    }
}

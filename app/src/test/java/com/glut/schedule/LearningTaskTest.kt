package com.glut.schedule

import com.glut.schedule.service.chaoxing.*
import org.junit.Assert.*
import org.junit.Test

/** 三类任务的完成状态与身份必须独立，未知状态不能落入未交筛选。 */
class LearningTaskTest {
    @Test fun groupsSubmissionStatesAndKeepsUnknownOutOfTodo() {
        val base = CachedHomework("course", "class", "课程", "任务", "待批阅", null, "1")
        val discussion = base.copy(kind = "discussion", status = "已讨论")
        val pending = discussion.copy(taskId = "2", status = "未讨论")
        val unknown = base.copy(kind = "exam", status = "待确认")
        val rows = listOf(base, discussion, pending, unknown)
        assertEquals(listOf(base, discussion), filterHomework(rows, "done", ""))
        assertEquals(listOf(pending), filterHomework(rows, "todo", ""))
        assertEquals(listOf(discussion, pending), filterHomework(rows, "", "", "discussion"))
        assertNotEquals(base.taskKey(), discussion.taskKey())
        assertEquals("homework", base.kind)
        assertEquals("1", base.taskId)
    }

    @Test fun onlyCompleteReplyTraversalCanConfirmNoParticipation() {
        assertEquals("已讨论", discussionParticipation("me", listOf("other", "me"), false))
        assertEquals("未讨论", discussionParticipation("me", listOf("other"), true))
        assertEquals("待确认", discussionParticipation("me", listOf("other"), false))
        assertEquals("待确认", discussionParticipation("", emptyList(), true))
    }

    @Test fun keepsOnlyTeacherTopicsAndNeverUsesTotalRepliesAsParticipation() {
        // 教师标记在列表页行属性 isteacher 上；回复总数不是本人参与凭证。
        val html = """
            <table>
              <tr class="dataBody_topic" data-uuid="t" isteacher="1"><td><span class="topicli_title_text">教师讨论</span><span class="topic_reply">99</span></td></tr>
              <tr class="dataBody_topic" data-uuid="s" isteacher="0"><td><span class="topicli_title_text">学生讨论</span></td></tr>
              <tr class="dataBody_topic" data-uuid="d" isteacher="true"><td><span class="topicli_title_text">另一讨论</span></td></tr>
            </table>
        """.trimIndent()
        val rows = parseDiscussionTopicList(html, "bbs")
        assertEquals(2, rows.size)
        assertEquals("教师讨论", rows[0].title)
        assertEquals("待确认", rows[0].status)
        assertEquals(null, rows[0].deadlineMillis)
        assertEquals("d", rows[1].taskId)
    }

    @Test fun distinguishesEmptyDiscussionFromUnrecognizedPage() {
        assertTrue(parseDiscussionTopicList("<div class='dataCon_empty'>暂无讨论</div>", "bbs").isEmpty())
        assertThrows(IllegalStateException::class.java) { parseDiscussionTopicList("<p>请先登录</p>", "bbs") }
    }

    @Test fun refusesUnknownNonemptyExamAndGenericEmptyErrorPages() {
        assertTrue(parseLearningExamList("<div class='exam-list'><div class='null-data'>暂无考试</div></div>").isEmpty())
        assertThrows(IllegalStateException::class.java) { parseLearningExamList("<p>暂无考试</p>") }
        assertThrows(IllegalStateException::class.java) { parseLearningExamList("<div class='exam-list'><a>进入考试</a></div>") }
    }
}

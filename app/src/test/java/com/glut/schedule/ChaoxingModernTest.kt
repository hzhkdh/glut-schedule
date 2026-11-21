package com.glut.schedule

import com.glut.schedule.service.chaoxing.ChaoxingClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ChaoxingModernTest {
    @Test fun officialExamRedirectIsReadonlyAndLimitedToVerifiedHost() {
        val client = ChaoxingClient()
        assertTrue(client.validTaskUrl("https://mooc1.chaoxing.com/exam-ans/mooc2/exam/task?courseId=1&classId=2".toHttpUrl()))
        assertFalse(client.validTaskUrl("https://mooc2-ans.chaoxing.com/exam-ans/mooc2/exam/task".toHttpUrl()))
        assertFalse(client.validTaskUrl("https://mooc1.chaoxing.com/exam-ans/mooc2/exam/dowork".toHttpUrl()))
        assertFalse(client.validTaskUrl("http://mooc1.chaoxing.com/exam-ans/mooc2/exam/task".toHttpUrl()))
    }
    @Test fun modernStatusesRemainVisibleInTheirGroups() {
        assertEquals(com.glut.schedule.service.chaoxing.HomeworkState.TODO, com.glut.schedule.service.chaoxing.homeworkState("被打回"))
        assertEquals(com.glut.schedule.service.chaoxing.HomeworkState.TODO, com.glut.schedule.service.chaoxing.homeworkState("待重做"))
        assertEquals(com.glut.schedule.service.chaoxing.HomeworkState.DONE, com.glut.schedule.service.chaoxing.homeworkState("已互评"))
    }
    @Test fun modernRedirectUsesJsonAndNestedDiscussionNavigation() {
        // 匿名构造新版跳转，确保不依赖真实课程或登录票据。
        val client = ChaoxingClient { url, _ ->
            when (url.encodedPath) {
                "/mycourse/backclazzdata" -> url to """{"channelList":[{"cpi":"3","content":{"id":"2","course":{"data":[{"id":"1","name":"匿名"}]}}}]}"""
                "/visit/stucoursemiddle" -> "https://mooc2-ans.chaoxing.com/mooc2-ans-vue/fanyav3/stu".toHttpUrl() to "<input id='courseid'>"
                "/mooc-ans/work/api/task" -> url to """{"status":true,"total":1,"data":[{"workId":"7","title":"匿名任务","stuStatus":3,"endTime":1234567890000}]}"""
                "/mooc2-ans/coursemanagev3/stu-base-info" -> url to """{"status":true,"navigationList":[{"children":[{"linkUrl":"https://groupweb.chaoxing.com/course/topic/topicList?bbsid=anonymous"}]}]}"""
                "/course/topic/topicList" -> url to "<div>暂无讨论</div>"
                "/mooc-ans/mooc2/exam/task" -> "https://mooc1.chaoxing.com/exam-ans/mooc2/exam/task".toHttpUrl() to """{"status":true,"data":[{"examRelationId":9,"title":"匿名考试","stuStatus":3}]}"""
                else -> error("不应访问旧版入口")
            }
        }
        val result = client.sync()
        assertEquals("7", result.courses.first { it.kind == "homework" }.homework.single().workId)
        assertEquals(1234567890000L, result.courses.first().homework.single().deadlineMillis)
        assertEquals("9", result.courses.single { it.kind == "exam" }.homework.single().taskId)
        assertTrue(result.failedTasks.isEmpty())
        assertTrue(result.courses.single { it.kind == "discussion" }.homework.isEmpty())
    }
}

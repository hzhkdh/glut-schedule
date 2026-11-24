package com.glut.schedule

import com.glut.schedule.service.chaoxing.ChaoxingClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class ChaoxingLegacyTest {
    @Test fun signedLegacyNavigationIsPreferredAndModernRecoversOnlyFailedKind() {
        for (oldWorks in listOf(true, false)) {
            val paths = mutableListOf<String>()
            val client = ChaoxingClient { url, _ ->
                paths += url.encodedPath
                when (url.encodedPath) {
                    "/mycourse/backclazzdata" -> url to """{"channelList":[{"cpi":"3","content":{"id":"2","course":{"data":[{"id":"1"}]}}}]}"""
                    "/visit/stucoursemiddle" -> "https://mooc2-ans.chaoxing.com/mooc2-ans-vue/fanyav3/stu?enc=anonymous".toHttpUrl() to "<input id='courseid'>"
                    "/mooc2-ans/mycourse/stu" -> {
                        assertEquals("anonymous", url.queryParameter("enc"))
                        assertEquals("true", url.queryParameter("forceMooc2"))
                        url to "<input id='courseid' value='1'><input id='clazzid' value='2'><input id='cpi' value='3'><input id='workEnc' value='test'><input id='enc' value='test'><input id='bbsid' value='bbs'><a data-url='https://groupweb.chaoxing.com/course/topic/topicList'></a><a data-url='https://mooc1.chaoxing.com/exam-ans/mooc2/exam/exam-list'></a>"
                    }
                    "/mooc2/work/list" -> url to if (oldWorks) "<ul><li aria-label='匿名;未交' data='/mooc2/work/task?workId=7'></li></ul>" else "<div>未知结构</div>"
                    "/mooc-ans/work/api/task" -> url to """{"status":true,"total":1,"data":[{"workId":7,"title":"匿名","taskUrl":"/mooc2/work/task?workId=7","stuStatus":0,"taskStatus":1,"action":1,"style":1,"startTime":1000}]}"""
                    "/mooc2/work/task" -> url to "<div class='borderBox'><div class='pcon'>匿名要求</div></div>"
                    "/course/topic/topicList" -> {
                        assertEquals("bbs", url.queryParameter("bbsid"))
                        url to """<script>window.obj = {circle:{bbsid:'bbs'},courseId:'1',isManager:0,classTag:'classId2',managers:[{"puid":22}]};</script>"""
                    }
                    "/course/topic/bbs/getTopTopicList" -> url to """{"status":true,"datas":[{"uuid":"pinned","create_puid":22,"topicReplyTime":2}]}"""
                    "/course/topic/bbs/getTopicList" -> if (url.queryParameter("page") == "1") {
                        url to """{"status":true,"datas":[{"uuid":"pinned","create_puid":22,"topicReplyTime":2}],"poff":{"lastPage":0,"lastValue":5,"lastAuxValue":"cursor"}}"""
                    } else {
                        assertEquals("5", url.queryParameter("last_reply_time"))
                        assertEquals("cursor", url.queryParameter("lastAuxValue"))
                        url to """{"status":true,"datas":[{"uuid":"topic","title":"示例","create_puid":22,"topicReplyTime":0},{"uuid":"student","create_puid":10}],"poff":{"lastPage":1}}"""
                    }
                    "/exam-ans/mooc2/exam/exam-list" -> url to if (oldWorks)
                        """<div class="task-list"><div class="bottomList"><ul><li><div onclick="viewExamAnswer(9, 8)"><div class="right-content"><p class="overHidden2">示例考试</p><p class="status">已完成</p></div></div></li></ul></div></div>"""
                        else "<div>未知结构</div>"
                    "/mooc2-ans/coursemanagev3/stu-base-info" -> url to """{"status":true,"navigationList":[]}"""
                    "/mooc-ans/mooc2/exam/task" -> url to """{"status":true,"data":[{"examRelationId":9,"title":"示例考试","stuStatus":3}]}"""
                    else -> error("不应访问的入口")
                }
            }
            val result = client.sync()
            val homework = result.courses.first { it.kind == "homework" }.homework.single()
            assertEquals("7", homework.workId)
            // 相对 taskUrl + 已发布待提交字段：必须保持「未交」，不能被降级成「状态待确认」。
            assertEquals("未交", homework.status)
            assertTrue(client.homeworkAppEntry(com.glut.schedule.service.chaoxing.CachedHomework("1", "2", "匿名", "匿名", "未交", null, "7", "3")).contains("workId=7"))
            assertEquals(!oldWorks, paths.contains("/mooc-ans/work/api/task"))
            assertEquals(listOf("已讨论", "未讨论"), result.courses.first { it.kind == "discussion" }.homework.map { it.status })
            val exam = result.courses.first { it.kind == "exam" }.homework.single()
            assertEquals("9", exam.taskId)
            assertEquals("已完成", exam.status)
            assertEquals(!oldWorks, paths.contains("/mooc-ans/mooc2/exam/task"))
        }
    }
}

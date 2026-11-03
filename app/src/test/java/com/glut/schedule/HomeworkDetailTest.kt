package com.glut.schedule

import com.glut.schedule.service.chaoxing.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 使用匿名结构验证详情不会带入答题控件和脚本。 */
class HomeworkDetailTest {
    @Test fun followsCourseRedirectAndUsesFreshHomeworkIdentity() {
        val paths = mutableListOf<String>()
        val client = ChaoxingClient { url, _ ->
            paths += url.encodedPath
            when (url.encodedPath) {
                "/mycourse/backclazzdata" -> url to """{"channelList":[{"cpi":"3","content":{"id":"2","course":{"data":[{"id":"1","name":"匿名课程"}]}}}]}"""
                "/visit/stucoursemiddle" -> "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu?enc=test".toHttpUrl() to "<input id='workEnc' value='test-work'>"
                "/mooc2/work/list" -> url to """<ul><li aria-label="匿名作业;未交" data="/mooc-ans/mooc2/work/task?workId=7&amp;answerId=8&amp;enc=test"></li></ul>"""
                else -> error("意外请求")
            }
        }
        val result = client.sync()
        assertTrue(result.failed.isEmpty())
        assertEquals("7", result.courses.single().homework.single().workId)
        assertEquals(3, paths.size)
    }
    @Test fun missingEntranceKeepsCourseInFailureList() {
        val client = ChaoxingClient { url, _ -> url to if (url.encodedPath == "/mycourse/backclazzdata")
            """{"channelList":[{"cpi":"3","content":{"id":"2","course":{"data":[{"id":"1","name":"匿名课程"}]}}}]}"""
            else "<html>登录已失效</html>" }
        val result = client.sync()
        assertEquals(1, result.failed.size)
        assertTrue(result.courses.isEmpty())
    }
    @Test fun extractsReadOnlyDetails() {
        val html = """<div class="borderBox"><div class="pcon">要求甲<script>bad()</script></div></div><div class="questionLi"><h3 class="mark_name">1. 题目甲</h3><textarea>私人答案</textarea></div>"""
        val detail = parseHomeworkDetail(html)
        assertTrue(detail.contains("要求甲"))
        assertTrue(detail.contains("题目甲"))
        assertFalse(detail.contains("私人答案"))
        assertFalse(detail.contains("bad()"))
    }
    @Test fun refreshHonorsFreshnessAndFailureBackoff() {
        val now = 10_000_000L
        assertFalse(homeworkRefreshDue(now, now - 1000, 0))
        assertFalse(homeworkRefreshDue(now, 0, now - 1000))
        assertTrue(homeworkRefreshDue(now, now - 1_800_000, now - 300_000))
    }
}

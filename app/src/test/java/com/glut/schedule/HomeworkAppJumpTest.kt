package com.glut.schedule

import com.glut.schedule.service.chaoxing.homeworkAppDeepLink
import com.glut.schedule.service.chaoxing.ChaoxingClient
import com.glut.schedule.service.chaoxing.CachedHomework
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class HomeworkAppJumpTest {
    @Test fun onlyResolvesTaskPresentInFreshList() {
        var removed = false
        val client = ChaoxingClient { url, _ ->
            when (url.encodedPath) {
                "/visit/stucoursemiddle" -> "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu?enc=test".toHttpUrl() to "<input id='workEnc' value='test-work'>"
                "/mooc2/work/list" -> url to if (removed) "<ul></ul>" else "<li data='/mooc2/work/task?workId=7&amp;enc=test'></li>"
                else -> url to "<div class='questionLi'><h3 class='mark_name'>test</h3></div>"
            }
        }
        val row = CachedHomework("1", "2", "test", "test", "未交", null, "7", "3")
        client.detail(row) // 先留下详情用的旧入口缓存。
        assertTrue(client.homeworkAppEntry(row).contains("workId=7"))
        removed = true
        assertTrue(runCatching { client.homeworkAppEntry(row) }.isFailure)
    }
    @Test fun encodesEntireTaskUrlWithoutLeakingNestedParameters() {
        val link = homeworkAppDeepLink("https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=123&enc=test%26value")
        val inner = link.substringAfter("url=").substringBefore("&title=")
        assertEquals("mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=123&enc=test%26value", URLDecoder.decode(inner, "UTF-8"))
        assertFalse(link.contains("&enc="))
    }
    @Test fun allowsVerifiedHomeworkListFallback() {
        assertTrue(homeworkAppDeepLink("https://mooc1-api.chaoxing.com/work/stu-work").startsWith("chaoxingshareback://url="))
    }
    @Test fun rejectsUntrustedAndAnsweringEndpoints() {
        listOf("https://example.com/task?workId=123", "http://mooc1.chaoxing.com/mooc2/work/task?workId=123",
            "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/dowork?workId=123",
            "https://mooc1.chaoxing.com/mooc2/work/task").forEach {
            assertTrue(runCatching { homeworkAppDeepLink(it) }.isFailure)
        }
    }
}

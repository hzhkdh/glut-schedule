package com.glut.schedule

import com.glut.schedule.service.chaoxing.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChaoxingTaskRefinementTest {
    @Test fun recognizesOnlyVerifiedReleasedHomeworkVariant() {
        val item = JSONObject("""{"stuStatus":0,"taskStatus":1,"action":1,"style":1,"startTime":100}""")
        assertTrue(isReleasedHomeworkVariant(item, true, 100))
        assertFalse(isReleasedHomeworkVariant(item, false, 100))
        assertFalse(isReleasedHomeworkVariant(item, true, 99))
        item.put("taskStatus", 0)
        assertFalse(isReleasedHomeworkVariant(item, true, 100))
    }

    @Test fun discussionDetailsRequireMatchingIdentityAndEscapePlainText() {
        val response = JSONObject("""{"status":true,"datas":{"uuid":"topic","bbsid":"group","text_content":"A < B\n第二行"}}""")
        val html = parseLearningDiscussionDetail(response, "topic", "group")
        assertTrue(html.contains("A &lt; B"))
        assertTrue(html.contains("第二行"))
        assertThrows(IllegalArgumentException::class.java) { parseLearningDiscussionDetail(response, "other", "group") }
    }

    @Test fun cachedDiscussionUsesVerifiedDetailEndpoint() {
        val client = ChaoxingClient { url, _ ->
            assertEquals("/course/topic/topic/getTopic", url.encodedPath)
            assertNull(url.query)
            url to """{"status":true,"datas":{"uuid":"topic","bbsid":"group","rtf_content":"<p>示例正文</p><script>unsafe()</script>"}}"""
        }
        val row = CachedHomework("course", "class", "示例课", "示例任务", "未讨论", null,
            kind = "discussion", taskId = "topic", bbsId = "group")
        val detail = client.detail(row)
        assertTrue(detail.contains("示例正文"))
        assertFalse(detail.contains("unsafe"))
    }

    @Test fun emptySelectionStillReadsCourseCatalogWithoutLoadingTasks() {
        val client = ChaoxingClient { url, _ ->
            assertEquals("/mycourse/backclazzdata", url.encodedPath)
            url to """{"channelList":[{"cpi":"3","content":{"id":"2","course":{"data":[{"id":"1","name":"示例课"}]}}}]}"""
        }
        val result = client.sync { _, _ -> false }
        assertEquals("1:2", result.catalog?.single()?.subjectKey())
        assertTrue(result.courses.isEmpty())
        assertTrue(result.failedTasks.isEmpty())
    }
}

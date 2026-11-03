package com.glut.schedule

import com.glut.schedule.service.chaoxing.*
import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

/** 使用匿名结构验证详情不会带入答题控件和脚本。 */
class HomeworkDetailTest {
    @Test fun detailUsesFreshOfficialLinkFromSyncWithoutReenteringCourse() {
        var courseVisits = 0
        val client = ChaoxingClient { url, _ ->
            when (url.encodedPath) {
                "/mycourse/backclazzdata" -> url to """{"channelList":[{"cpi":"3","content":{"id":"2","course":{"data":[{"id":"1","name":"匿名课程"}]}}}]}"""
                "/visit/stucoursemiddle" -> {
                    courseVisits++
                    check(courseVisits == 1) { "重复进入课程会失败" }
                    "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu?enc=test".toHttpUrl() to "<input id='workEnc' value='test-work'>"
                }
                "/mooc2/work/list" -> url to """<ul><li aria-label="作业;未交" data="/mooc-ans/mooc2/work/task?workId=7&amp;enc=test"></li></ul>"""
                else -> url to "<div class='questionLi'><h3 class='mark_name'>匿名题目</h3></div>"
            }
        }
        client.sync()
        val row = CachedHomework("1", "2", "课程", "作业", "未交", null, "7", "3")
        assertTrue(client.detail(row).contains("匿名题目"))
        assertTrue(client.detail(row).contains("匿名题目"))
        assertEquals(1, courseVisits)
    }
    @Test fun reportsDetailFailureStageWithoutLeakingRequest() {
        val client = ChaoxingClient { url, _ ->
            when (url.encodedPath) {
                "/visit/stucoursemiddle" -> "https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu?enc=test".toHttpUrl() to "<input id='workEnc' value='test-work'>"
                "/mooc2/work/list" -> url to """<ul><li data="/mooc-ans/mooc2/work/task?workId=7&amp;enc=private"></li></ul>"""
                else -> throw java.io.IOException("private request")
            }
        }
        val error = assertThrows(HomeworkDetailException::class.java) {
            client.detail(CachedHomework("1", "2", "课程", "作业", "未交", null, "7", "3"))
        }
        assertEquals("详情请求失败，请检查网络后重试", error.message)
        assertFalse(error.message.orEmpty().contains("private"))
    }
    @Test fun buildsWrappingReadOnlyDocument() {
        val document = homeworkDetailDocument("<p>匿名详情</p>")
        assertTrue(document.contains("overflow-wrap:anywhere"))
        assertTrue(document.contains("<p>匿名详情</p>"))
    }
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
        assertFalse(detail.contains("题目甲"))
        assertFalse(detail.contains("私人答案"))
        assertFalse(detail.contains("bad()"))
    }
    @Test fun emptyQuestionHeadingsDoNotAppearInRequirements() {
        val detail = parseHomeworkDetail("""<div class="borderBox"><div class="pcon">匿名作业要求</div></div><div class="questionLi"><h3 class="mark_name">1. <span class="colorShallow">(简答题)</span></h3></div>""")
        assertTrue(detail.contains("匿名作业要求"))
        assertFalse(detail.contains("简答题"))
        assertFalse(detail.contains("1."))
    }
    @Test fun keepsMeaningfulRequirementBodyWhenThereIsNoSeparateDescription() {
        val detail = parseHomeworkDetail("""<div class="questionLi"><h3 class="mark_name">1. <span class="colorShallow">(简答题)</span><p>匿名实验要求</p></h3><textarea>不得展示</textarea></div>""")
        assertTrue(detail.contains("匿名实验要求"))
        assertFalse(detail.contains("简答题"))
        assertFalse(detail.contains("1."))
        assertFalse(detail.contains("不得展示"))
    }
    @Test fun showsEmptyRequirementHintInsteadOfEmptyQuestionForm() {
        val detail = parseHomeworkDetail("""<div class="questionLi"><h3 class="mark_name">1. <span class="colorShallow">(简答题)</span></h3></div>""")
        assertTrue(detail.contains("暂无可展示的要求"))
        assertFalse(detail.contains("简答题"))
    }
    @Test fun attachmentOnlyRequirementIsNotTreatedAsEmpty() {
        val detail = parseHomeworkDetail("""<div class="borderBox"><div class="pcon"><a href="https://mooc1.chaoxing.com/fixture.pdf"><i class="download"></i></a></div></div>""")
        assertTrue(detail.contains("附件链接"))
        assertTrue(detail.contains("fixture.pdf"))
        assertFalse(detail.contains("暂无可展示"))
    }
    @Test fun refreshHonorsFreshnessAndFailureBackoff() {
        val now = 10_000_000L
        assertFalse(homeworkRefreshDue(now, now - 1000, 0))
        assertFalse(homeworkRefreshDue(now, 0, now - 1000))
        assertTrue(homeworkRefreshDue(now, now - 1_800_000, now - 300_000))
    }
}

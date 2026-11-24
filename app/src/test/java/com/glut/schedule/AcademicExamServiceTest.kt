package com.glut.schedule

import com.glut.schedule.service.academic.AcademicExamService
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.parser.GlutExamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AcademicExamServiceTest {
    @Test
    fun selectExamResultPrefersParseableExamJsonFromProbeResults() {
        val service = AcademicExamService(GlutExamParser())
        val examJson = """
            {"data":[{"courseName":"高等数学","examDate":"2026-06-20","examTime":"09:00-11:00","examRoom":"03101","seatNumber":"18"}]}
        """.trimIndent()
        val result = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/student/examination/queryExam.do",
            method = "POST",
            httpCode = 200,
            contentType = "application/json",
            body = examJson,
            bodyLength = examJson.length
        )

        val selected = service.selectExamDataFromProbeResults(listOf(result))

        assertEquals("http://jw.glut.edu.cn/academic/student/examination/queryExam.do", selected?.url)
        assertEquals(1, selected?.exams?.size)
        assertEquals("高等数学", selected?.exams?.first()?.courseName)
    }

    /**
     * 教务的「学生考试安排」首页只列最近 7 天的考试，真正的全量列表在“查看全部考试安排”接口里。
     * 首页哪怕能解析出 1 条，也不能遮住后面的完整列表。
     */
    @Test
    fun recentOnlyExamPageDoesNotHideFullExamList() {
        val recentOnlyPage = """
            <table class="infolist_tab">
              <tr><th>课程号</th><th>课程名称</th><th>考试时间</th><th>考试地点</th><th>考试性质</th></tr>
              <tr><td>398720</td><td>图像处理与机器视觉</td><td>2026-12-16 12:10--14:10</td><td>06104D</td><td>正常考试</td></tr>
            </table>
            <table class="explain_tab"><tr><td>这里列出7天前(2026-11-17)开始的全部考试安排。今天以前的考试模糊显示。</td></tr></table>
            <form action="/academic/manager/examstu/studentQueryAllExamPre.do" method="post"><input type="submit" value="查看全部考试安排"></form>
        """.trimIndent()
        val fullPage = """
            <table class="logo_tab"><tr><td class="logo"></td></tr></table>
            <table class="infolist_tab">
              <tr><td>全部考试安排&nbsp;课程号</td><td>课程名称</td><td>考试时间</td><td>考试地点</td><td>考试性质</td></tr>
              <tr><td>398720</td><td>图像处理与机器视觉</td><td>2026-12-16 12:10--14:10</td><td>雁山6号楼 06104D</td><td>正常考试</td></tr>
              <tr><td>377740</td><td>算法设计与分析</td><td>2026-07-10 16:20--18:00</td><td>雁山5号楼 05307D</td><td>正常考试</td></tr>
              <tr><td>353510</td><td>操作系统B</td><td>2026-06-26 14:30--16:30</td><td>雁山6号楼 06408D</td><td>正常考试</td></tr>
            </table>
        """.trimIndent()
        val results = listOf(
            ApiProbeService.ProbeResult(
                "https://jw.glut.edu.cn/academic/accessModule.do?moduleId=2030",
                "GET", 200, "text/html;charset=gbk", recentOnlyPage, recentOnlyPage.length
            ),
            ApiProbeService.ProbeResult(
                "https://jw.glut.edu.cn/academic/manager/examstu/studentQueryAllExam.do?pagingNumberPerVLID=1000",
                "GET", 200, "text/html;charset=UTF-8", fullPage, fullPage.length
            )
        )

        val selected = AcademicExamService(GlutExamParser()).selectExamDataFromProbeResults(results)

        assertEquals(
            "https://jw.glut.edu.cn/academic/manager/examstu/studentQueryAllExam.do?pagingNumberPerVLID=1000",
            selected?.url
        )
        assertEquals(listOf("图像处理与机器视觉", "算法设计与分析", "操作系统B"), selected?.exams?.map { it.courseName })
    }

    @Test
    fun examProbeRequestsSkipLegacySummaryPage() {
        // 首页摘要只含最近 7 天，且全量接口已实测可直接访问；旧缓存地址也不能再探测。
        val summaryUrl = "https://jw.glut.edu.cn/academic/accessModule.do?moduleId=2030"
        val requests = ApiProbeService.buildExamProbeRequests(
            storedExamApiUrl = summaryUrl,
            baseUrl = "https://jw.glut.edu.cn"
        )

        assertFalse(requests.any { (url, _) -> url.contains("moduleId=2030") })
        assertEquals(
            "https://jw.glut.edu.cn/academic/manager/examstu/studentQueryAllExam.do?pagingNumberPerVLID=1000",
            requests.first().first
        )
    }

    @Test
    fun examProbeRequestsOnlyContainExamEndpoints() {
        val storedUrl = "http://jw.glut.edu.cn/academic/student/examination/queryExam.do"
        val requests = ApiProbeService.buildExamProbeRequests(
            storedExamApiUrl = storedUrl,
            baseUrl = "http://jw.glut.edu.cn"
        )

        assertEquals(storedUrl, requests.first().first)
        assertEquals("GET", requests.first().second)
        assertFalse(requests.any { (url, _) -> url.contains("showTimetable", ignoreCase = true) })
        assertFalse(requests.any { (url, _) -> url.contains("studyschedule", ignoreCase = true) })
        assertFalse(requests.any { (url, _) -> url.contains("skilltest", ignoreCase = true) })
        assertFalse(requests.any { (url, _) -> url.contains("score", ignoreCase = true) })
    }
}

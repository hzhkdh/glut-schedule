package com.glut.schedule

import com.glut.schedule.service.academic.AcademicImportConfig
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.academic.hasUsableAcademicCookie
import com.glut.schedule.service.academic.isAcademicPage
import com.glut.schedule.service.academic.sanitizeDebugUrl
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AcademicImportTest {
    @Test
    fun academicPageDetectionOnlyAcceptsGlutAcademicHost() {
        assertTrue(isAcademicPage("http://jw.glut.edu.cn/academic/preGotoAffairFrame.do#/menu"))
        assertTrue(isAcademicPage("http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do?id=1"))
        assertFalse(isAcademicPage("https://cas.glut.edu.cn/portal/login.html"))
        assertFalse(isAcademicPage("http://example.com/academic/preGotoAffairFrame.do"))
    }

    @Test
    fun cookieIsUsableWhenItContainsSessionLikeToken() {
        assertTrue(hasUsableAcademicCookie("JSESSIONID=abc123; Path=/academic; HttpOnly"))
        assertTrue(hasUsableAcademicCookie("route=server1; JSESSIONID=abc123"))
        assertFalse(hasUsableAcademicCookie(""))
        assertFalse(hasUsableAcademicCookie("remember-me=false"))
    }

    @Test
    fun importConfigPointsToKnownAcademicPages() {
        assertTrue(AcademicImportConfig.loginUrl.contains("jw.glut.edu.cn/academic"))
        assertTrue(AcademicImportConfig.timetableUrlPatterns.any { it.containsMatchIn("showTimetable.do?id=123&timetableType=STUDENT") })
    }

    @Test
    fun directTimetableUrlDoesNotHardcodeASpecificStudent() {
        val url = AcademicImportConfig.directTimetableUrl

        assertTrue(url.contains("preGotoAffairFrame.do"))
        assertFalse(url.contains("id=712170"))
        assertEquals("", AcademicImportConfig.extractStudentId(url))
    }

    @Test
    fun apiProbeUrlsUseCapturedTimetableUrlsInsteadOfDefaultStudentId() {
        val captured = "http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do?id=999999&yearid=46&termid=1&timetableType=STUDENT&sectionType=BASE"
        val urls = com.glut.schedule.service.academic.ApiProbeService.buildProbeUrls(listOf(captured))

        assertTrue(urls.contains(captured))
        assertFalse(urls.any { it.contains("id=712170") })
    }

    @Test
    fun apiProbeDoesNotTreatGraphicalBasicInfoAsTimetableJson() {
        val service = ApiProbeService()
        val metadata = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/manager/coursearrange/graphicalBasicInfo.do",
            method = "GET",
            httpCode = 200,
            contentType = "text/html;charset=UTF-8",
            body = """
                {
                  "code": 1,
                  "data": {
                    "arrangeCourseYear": 44,
                    "courseArrPropList": [{"name": "实验学时"}],
                    "detailHourList": [{"name": "讲课学时"}],
                    "sectionBaseList": [{"name": "第1节"}]
                  }
                }
            """.trimIndent(),
            bodyLength = 360
        )

        assertNull(service.extractTimetableJson(listOf(metadata)))
    }

    @Test
    fun apiProbeRecognizesCurrcourseArrangementHtmlWithoutWeekGridText() {
        val service = ApiProbeService()
        val currcourseHtml = """
            <html>
              <head><title>本学期课程安排</title></head>
              <body>
                <table>
                  <tr>
                    <th>课程名称</th><th>任课教师</th><th>上课时间、地点</th>
                  </tr>
                  <tr>
                    <td>习近平新时代中国特色社会主义思想概论</td>
                    <td>梁英</td>
                    <td>1-10周 星期一 第5、6节 07120D</td>
                  </tr>
                </table>
              </body>
            </html>
        """.trimIndent()
        val result = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/student/currcourse/currcourse.jsdo",
            method = "GET",
            httpCode = 200,
            contentType = "text/html;charset=gbk",
            body = currcourseHtml,
            bodyLength = currcourseHtml.length
        )

        assertEquals(currcourseHtml, service.extractTimetableHtml(listOf(result)))
    }

    @Test
    fun dynamicTimetableUrlsUseCurrentLoggedInStudentFromFramePage() {
        val framePage = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/personal/framePage.do",
            method = "POST",
            httpCode = 200,
            contentType = "text/html;charset=UTF-8",
            body = """
                {"code":1,"data":{"whichweek":9,"schoolCalendarAlias":"2026春","term":"春","user":{"id":888001}}}
            """.trimIndent(),
            bodyLength = 100
        )

        val urls = ApiProbeService.buildCurrentStudentTimetableUrls(listOf(framePage))

        assertTrue(urls.any { it.contains("id=888001") && it.contains("yearid=46") && it.contains("termid=1") })
        assertFalse(urls.any { it.contains("id=712170") })
    }

    @Test
    fun apiProbeExtractsAcademicCalendarFromFramePage() {
        val framePage = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/personal/framePage.do",
            method = "POST",
            httpCode = 200,
            contentType = "text/html;charset=UTF-8",
            body = """
                {"code":1,"data":{"whichweek":9,"schoolCalendarStartDate":"2026-03-09","schoolCalendarAlias":"2026春","user":{"id":888001}}}
            """.trimIndent(),
            bodyLength = 140
        )

        val calendar = ApiProbeService.extractAcademicCalendar(listOf(framePage))

        assertEquals(9, calendar?.currentWeekNumber)
        assertEquals(LocalDate.of(2026, 3, 9), calendar?.semesterStartMonday)
    }

    @Test
    fun apiProbeFindsCurrentTodayPlanAsFallbackJson() {
        val todayPlan = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/personal/currentTodayPlan.do?currentDate=2026-5-6",
            method = "POST",
            httpCode = 200,
            contentType = "text/html;charset=UTF-8",
            body = """
                {"code":1,"data":[{"name":"嵌入式系统","time":"第1、2节","arrangeDate":"2026-05-06 00:00:00","roomName":"06104D"}]}
            """.trimIndent(),
            bodyLength = 130
        )

        assertEquals(todayPlan, ApiProbeService.findTodayPlanJsonResult(listOf(todayPlan)))
    }

    @Test
    fun apiProbePrefersPersonalTimetableGridOverCurrcourseListPage() {
        val service = ApiProbeService()
        val currcourseHtml = """
            <html><head><title>本学期课程安排</title></head><body>
              <table>
                <tr><th>课程名称</th><th>任课教师</th><th>上课时间、地点</th></tr>
                <tr><td>数字逻辑</td><td>卢佩</td><td>1-12周 星期二 第1、2节 06408D</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val gridHtml = """
            <html><body>
              <table id="timetable">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th></tr>
                <tr><th>第1节</th><td></td><td>&lt;&lt;数字逻辑&gt;&gt;;2<br>06408D<br>卢佩<br>1-12周<br>讲课学时</td></tr>
              </table>
            </body></html>
        """.trimIndent()
        val currcourse = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/student/currcourse/currcourse.jsdo",
            method = "GET",
            httpCode = 200,
            contentType = "text/html;charset=gbk",
            body = currcourseHtml,
            bodyLength = currcourseHtml.length
        )
        val grid = ApiProbeService.ProbeResult(
            url = "http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do?id=888001&yearid=46&termid=1&timetableType=STUDENT&sectionType=BASE",
            method = "GET",
            httpCode = 200,
            contentType = "text/html;charset=UTF-8",
            body = gridHtml,
            bodyLength = gridHtml.length
        )

        assertEquals(gridHtml, service.extractTimetableHtml(listOf(currcourse, grid)))
    }

    @Test
    fun directTimetableUrlBuilderKeepsStudentYearAndTermTogether() {
        val url = AcademicImportConfig.buildStudentTimetableUrl(
            studentId = "100",
            yearId = "47",
            termId = "2"
        )

        assertTrue(url.startsWith("http://jw.glut.edu.cn/academic/manager/coursearrange/showTimetable.do?"))
        assertEquals("100", AcademicImportConfig.extractStudentId(url))
        assertEquals("47", AcademicImportConfig.extractYearId(url))
        assertEquals("2", AcademicImportConfig.extractTermId(url))
        assertTrue(url.contains("timetableType=STUDENT"))
        assertTrue(url.contains("sectionType=BASE"))
    }

    @Test
    fun debugUrlSanitizerRedactsLoginCredentials() {
        val sanitized = sanitizeDebugUrl(
            "http://jw.glut.edu.cn/academic/j_acegi_security_check?j_username=3242050858113&j_password=secret&j_captcha=undefined"
        )

        assertFalse(sanitized.contains("3242050858113"))
        assertFalse(sanitized.contains("secret"))
        assertTrue(sanitized.contains("j_username=<redacted>"))
        assertTrue(sanitized.contains("j_password=<redacted>"))
    }
}

package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicSemesterImportService
import com.glut.schedule.service.academic.AcademicSemesterRequestBuilder
import com.glut.schedule.service.academic.AcademicSemesterResponseKind
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.parser.AcademicScheduleParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 统一导入路径（只抓大节课表）的服务层测试。
 *
 * 三条铁律，用例都围绕它们：
 *  1. **只解析大节课表**——个人课表页退化为「取学号」的一跳，课程一律来自 `showTimetable.do`；
 *  2. **绝不逐周 POST**——`studentWeeklyTimetable.do` 只允许发一次落地页 GET，且失败只降级不报错；
 *  3. **绝不留 null 学期总周数**——否则课时统计会把整学期判为不可统计。
 */
class AcademicSemesterImportServiceTest {

    // ── URL 构造：统一路径下最要紧的是「发出去之前就没有重定向」 ──

    @Test
    fun guilinUrlsAreNormalizedToHttpsWhileNanningStaysHttp() {
        val guilin = AcademicSemesterRequestBuilder.timetableUrl(
            "http://jw.glut.edu.cn", "712170", semester()
        )
        assertTrue(guilin, guilin.startsWith("https://jw.glut.edu.cn/academic/"))

        val guilinLanding = AcademicSemesterRequestBuilder.weeklyTimetableUrl("http://jw.glut.edu.cn", semester())
        assertTrue(guilinLanding, guilinLanding.startsWith("https://jw.glut.edu.cn/academic/"))

        // 南宁维持 HTTP：它没有被强制跳转，改成 https 反而会连不上。
        val nanning = AcademicSemesterRequestBuilder.timetableUrl(
            "http://jw.glutnn.cn", "237607", nanningSemester()
        )
        assertTrue(nanning, nanning.startsWith("http://jw.glutnn.cn/academic/"))
    }

    // ── 认证与结构 ──

    @Test
    fun loginPageFailsAsAuthenticationExpiry() = runTest {
        val result = importFrom(
            currcourseBody = """<form action="j_acegi_security_check"><input type="password" /></form>"""
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("登录状态已失效"))
    }

    @Test
    fun randomSuccessfulHtmlFailsAsUnrecognizedStructure() = runTest {
        val result = importFrom(timetableBody = "<html><h1>系统公告</h1></html>")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("无法识别课表结构"))
    }

    @Test
    fun missingStudentIdFailsWithReadableMessage() = runTest {
        // 个人课表页里没有 showTimetable 链接，也拿不到兜底学号 —— 没法定位大节课表。
        val result = importFrom(
            currcourseBody = currcourseHtml(includeTimetableLink = false),
            studentIdFallback = ""
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("无法从教务页面解析学号"))
    }

    @Test
    fun selectedSemesterMismatchDoesNotReturnImportPayload() = runTest {
        val body = """
            <select name="year"><option value="44" selected>2024</option></select>
            <select name="term"><option value="2" selected>秋</option></select>
            <table id="manualArrangeCourseTable"></table>
        """.trimIndent()

        val result = importFrom(currcourseBody = body)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("返回学期与请求不一致"))
    }

    @Test
    fun selectedAttributeBeforeValueStillValidatesRequestedSemester() = runTest {
        val body = """
            <select name='year'><option selected='selected' value='44'>2024</option></select>
            <select name='term'><option selected value='2'>秋</option></select>
            <table id="manualArrangeCourseTable"></table>
        """.trimIndent()

        val result = importFrom(currcourseBody = body)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("返回学期与请求不一致"))
    }

    // ── 课程结果 ──

    @Test
    fun validEmptyScheduleReturnsSuccessfulEmptyPayload() = runTest {
        val result = importFrom(courses = emptyList())

        assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)
        assertEquals(emptyList<ScheduleCourse>(), result.getOrThrow().courses)
        assertEquals(AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE, result.getOrThrow().responseKind)
    }

    @Test
    fun validNonEmptyScheduleReturnsSuccessfulNonEmptyPayload() = runTest {
        val course = course()
        val result = importFrom(courses = listOf(course))

        assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)
        // 大节课表网格自带教师与教室，课程原样进入 payload，不经任何绑定/改写。
        assertEquals(listOf(course), result.getOrThrow().courses)
        assertEquals(AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE, result.getOrThrow().responseKind)
    }

    @Test
    fun coursesComeFromTimetablePageNotFromPersonalPage() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(currcourseHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(timetableHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(weeklyLandingHtml()))

            val timetableCourses = listOf(course().copy(title = "来自大节课表"))
            val personalCourses = listOf(course().copy(title = "来自个人课表"))
            val result = AcademicSemesterImportService(
                ApiProbeService(sessionUrlValidator = { true }),
                SourceAwareParser(
                    timetableCourses = timetableCourses,
                    personalCourses = personalCourses
                )
            ).importSemester(
                cookie = "JSESSIONID=test",
                baseUrl = server.url("/").toString(),
                semester = semester(),
                studentIdFallback = "student-internal-id"
            )

            assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)
            // 个人课表里的课程不得出现在结果中——它已经不是数据源了。
            assertEquals(listOf("来自大节课表"), result.getOrThrow().courses.map { it.title })
        }
    }

    // ── 请求编排：这是统一路径最核心的契约 ──

    @Test
    fun unifiedImportNeverPostsWeeksAndHitsWeeklyLandingAtMostOnce() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(currcourseHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(timetableHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(weeklyLandingHtml((1..19).toList())))

            val result = AcademicSemesterImportService(
                ApiProbeService(sessionUrlValidator = { true }),
                FixedParser(listOf(course()))
            ).importSemester(
                cookie = "JSESSIONID=test",
                baseUrl = server.url("/").toString(),
                semester = semester(),
                studentIdFallback = "student-internal-id"
            )

            assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)

            val requests = List(server.requestCount) { server.takeRequest() }
            val paths = requests.map { it.path.orEmpty() }
            // 逐周 POST 属于旧的模式1，整条链路已删除：任何请求都不允许再是 POST 表单。
            assertTrue(paths.toString(), requests.none { it.method == "POST" })
            assertTrue(paths.toString(), paths.none { it.contains("whichWeek") })
            assertTrue(paths.toString(), paths.count { it.contains("studentWeeklyTimetable") } <= 1)
            assertTrue(paths.toString(), paths.any { it.contains("showTimetable.do") })
            assertTrue(paths.toString(), paths.any { it.contains("currcourse.jsdo") })
        }
    }

    @Test
    fun rotatedSessionCookieIsCarriedIntoSubsequentRequests() = runTest {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.contains("currcourse.jsdo") -> MockResponse()
                            .setResponseCode(200)
                            .addHeader("Set-Cookie", "JSESSIONID=rotated; Path=/")
                            .setBody(currcourseHtml())
                        path.contains("studentWeeklyTimetable") -> MockResponse()
                            .setResponseCode(200)
                            .setBody(weeklyLandingHtml())
                        path.contains("showTimetable.do") -> MockResponse()
                            .setResponseCode(200)
                            .setBody(timetableHtml())
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val result = AcademicSemesterImportService(
                ApiProbeService(sessionUrlValidator = { true }),
                FixedParser(listOf(course()))
            ).importSemester(
                cookie = "JSESSIONID=initial",
                baseUrl = server.url("/").toString(),
                semester = semester(),
                studentIdFallback = "student-internal-id"
            )

            assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)
            assertEquals("JSESSIONID=rotated", result.getOrThrow().updatedCookie)

            // 教务在切学期时会轮换会话，后续请求必须接续新 Cookie，否则会掉登录。
            val cookies = List(server.requestCount) { server.takeRequest().getHeader("Cookie").orEmpty() }
            assertTrue(cookies.toString(), cookies.drop(1).all { it.contains("JSESSIONID=rotated") })
        }
    }

    @Test
    fun weeklyLandingIsSkippedWhenSemesterCarriesCalendarDates() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(currcourseHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(timetableHtml()))

            val start = LocalDate.of(2026, 9, 7)
            val end = LocalDate.of(2027, 1, 24)
            val payload = AcademicSemesterImportService(
                ApiProbeService(sessionUrlValidator = { true }),
                FixedParser(listOf(course()))
            ).importSemester(
                cookie = "JSESSIONID=test",
                baseUrl = server.url("/").toString(),
                semester = semester().copy(semesterStartDate = start, semesterEndDate = end),
                studentIdFallback = "student-internal-id"
            ).getOrThrow()

            // 校历已足够权威，不为拿周数再发落地页请求。
            assertEquals(2, server.requestCount)
            assertEquals(academicMaxWeekForCalendar(start, end), payload.portalMaxWeek)
        }
    }

    // ── 学期总周数：三级回退 ──

    @Test
    fun portalMaxWeekComesFromWeeklyLandingPage() = runTest {
        val payload = importFrom(
            courses = listOf(course()),
            landingWeeks = (1..19).toList()
        ).getOrThrow()

        // 落地页给出的门户周次才是学期长度。旧实现只能反推到「最后一个有课周」16，
        // 历史学期因此翻不到 17-19 周，课时统计也会少算第 17 周以后的课。
        assertEquals(19, payload.portalMaxWeek)
    }

    @Test
    fun portalMaxWeekIgnoresWeeklyLandingWhoseSemesterLabelDiffers() = runTest {
        val payload = importFrom(
            courses = listOf(course()),
            landingWeeks = (1..21).toList(),
            landingLabel = "2024秋"
        ).getOrThrow()

        // 采信了错学期就会是 21；正确行为是丢弃并退化到春季估算 19。
        assertEquals(19, payload.portalMaxWeek)
    }

    @Test
    fun portalMaxWeekKeepsDerivedValueAsLowerBound() = runTest {
        val payload = importFrom(
            courses = listOf(course()),
            landingWeeks = (1..15).toList()
        ).getOrThrow()

        // 落地页只给出 1-15 周，但课次里有「1-16周」——反推值是**下界**，不能被抹掉。
        assertEquals(16, payload.portalMaxWeek)
    }

    @Test
    fun portalMaxWeekSurvivesUnusableWeeklyLanding() = runTest {
        val payload = importFrom(
            courses = listOf(course()),
            landingBody = """<form action="j_acegi_security_check"><input type="password" /></form>"""
        ).getOrThrow()

        // 落地页被重定向到登录页：按契约只让周数退化，绝不能让导入失败。
        // 春季估算 19 周；若错误地退回「最后一个有课周」，这里会是 16。
        assertEquals(19, payload.portalMaxWeek)
    }

    // ── 测试夹具 ──

    private suspend fun importFrom(
        currcourseBody: String = currcourseHtml(),
        timetableBody: String = timetableHtml(),
        courses: List<ScheduleCourse> = emptyList(),
        studentIdFallback: String = "student-internal-id",
        semester: AcademicSemester = semester(),
        landingWeeks: List<Int> = listOf(1),
        landingLabel: String = "2025春",
        landingBody: String? = null
    ) = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(currcourseBody))
        server.enqueue(MockResponse().setResponseCode(200).setBody(timetableBody))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(landingBody ?: weeklyLandingHtml(landingWeeks, landingLabel))
        )
        AcademicSemesterImportService(
            ApiProbeService(sessionUrlValidator = { true }),
            FixedParser(courses)
        ).importSemester(
            cookie = "JSESSIONID=test",
            baseUrl = server.url("/").toString(),
            semester = semester,
            studentIdFallback = studentIdFallback
        )
    }

    /**
     * 个人课表页。默认带一条大节课表链接——内部学号只能从这里取到。
     *
     * 注意：它**不再**是课程数据源，这里放什么课程内容都不影响结果。
     */
    private fun currcourseHtml(
        term: String = "1",
        includeTimetableLink: Boolean = true
    ): String {
        val link = if (includeTimetableLink) {
            """<a href="showTimetable.do?id=712170&yearid=45&termid=$term&timetableType=STUDENT&sectionType=BASE">学生课表</a>"""
        } else {
            ""
        }
        return """
            <form><select name="year"><option value="45" selected>2025</option></select>
            <select name="term"><option value="$term" selected>春</option></select></form>
            $link
        """.trimIndent()
    }

    /** 大节课表页。含 `id="timetable"` 才算可识别的课表结构。 */
    private fun timetableHtml(): String = """
        <html><body><table id="timetable">
        <tr><th></th><th>周一</th><th>周二</th></tr>
        <tr><th>第1节</th><td id="1-1">&nbsp;</td><td id="2-1">&nbsp;</td></tr>
        </table></body></html>
    """.trimIndent()

    private fun weeklyLandingHtml(weeks: List<Int> = listOf(1), label: String = "2025春"): String = """
        <html><body><form><span>$label 第 </span><select name="whichWeek">
        <option value=""></option>${weeks.joinToString("") { "<option value=\"$it\">$it</option>" }}</select><span> 周 周次课表</span></form>
        <table><tr><th>日期</th><th>课程名</th><th>选课属性</th><th>考试性质</th><th>星期</th>
        <th>节次</th><th>开始时间</th><th>结束时间</th><th>教学楼</th><th>教室</th><th></th></tr></table>
        </body></html>
    """.trimIndent()

    private fun semester() = AcademicSemester.create(
        CampusType.GUILIN, 2025, "45", SemesterSeason.SPRING, "1", isCurrent = true
    )

    private fun nanningSemester() = AcademicSemester.create(
        CampusType.NANNING, 2025, "45", SemesterSeason.SPRING, "2", isCurrent = true
    )

    private fun course() = ScheduleCourse(
        id = "course-1",
        title = "测试课程",
        room = "A101",
        teacher = "教师",
        colorHex = "#4477AA",
        occurrences = listOf(CourseOccurrence("occ-1", "course-1", 1, 1, 2, "1-16周", ""))
    )

    private class FixedParser(private val courses: List<ScheduleCourse>) : AcademicScheduleParser {
        override fun parsePersonalSchedule(html: String): List<ScheduleCourse> = courses
    }

    /** 按 HTML 里的表 id 区分大节课表页与个人课表页，用于验证「谁才是数据源」。 */
    private class SourceAwareParser(
        private val timetableCourses: List<ScheduleCourse>,
        private val personalCourses: List<ScheduleCourse>
    ) : AcademicScheduleParser {
        override fun parsePersonalSchedule(html: String): List<ScheduleCourse> =
            if (html.contains("id=\"timetable\"")) timetableCourses else personalCourses
    }
}

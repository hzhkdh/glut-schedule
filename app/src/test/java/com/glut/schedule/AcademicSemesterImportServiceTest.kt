package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicSemesterImportService
import com.glut.schedule.service.academic.AcademicSemesterResponseKind
import com.glut.schedule.service.academic.ApiProbeService
import com.glut.schedule.service.parser.AcademicScheduleParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class AcademicSemesterImportServiceTest {
    @Test
    fun loginPageFailsAsAuthenticationExpiry() = runTest {
        val result = importFrom("""<form action="j_acegi_security_check"><input type="password" /></form>""")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("登录状态已失效"))
    }

    @Test
    fun randomSuccessfulHtmlFailsAsUnrecognizedStructure() = runTest {
        val result = importFrom("<html><h1>系统公告</h1></html>")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("无法识别课表结构"))
    }

    @Test
    fun validEmptyScheduleReturnsSuccessfulEmptyPayload() = runTest {
        val result = importFrom(validScheduleHtml(), courses = emptyList())

        assertTrue(result.isSuccess)
        assertEquals(emptyList<ScheduleCourse>(), result.getOrThrow().courses)
        assertEquals(AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE, result.getOrThrow().responseKind)
    }

    @Test
    fun validNonEmptyScheduleReturnsSuccessfulNonEmptyPayload() = runTest {
        val course = course()
        val result = importFrom(validScheduleHtml(), courses = listOf(course))

        assertTrue(result.isSuccess)
        assertEquals(listOf(course.copy(occurrences = emptyList())), result.getOrThrow().courses)
        assertEquals(AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE, result.getOrThrow().responseKind)
    }

    @Test
    fun parsedHistoricalCoursesAreNotRejectedByUnknownPageWrapper() = runTest {
        val course = course()
        val result = importFrom(
            "<html><body><table class=\"infolist\"><tr><td>历史课程</td></tr></table></body></html>",
            courses = listOf(course)
        )

        assertTrue(result.isSuccess)
        assertEquals(listOf(course.copy(occurrences = emptyList())), result.getOrThrow().courses)
    }

    @Test
    fun selectedSemesterMismatchDoesNotReturnImportPayload() = runTest {
        val body = """
            <select name="year"><option value="44" selected>2024</option></select>
            <select name="term"><option value="2" selected>秋</option></select>
            <table id="manualArrangeCourseTable"></table>
        """.trimIndent()

        val result = importFrom(body, courses = listOf(course()))

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

        val result = importFrom(body, courses = listOf(course()))

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("返回学期与请求不一致"))
    }

    @Test
    fun accurateImportGetsLandingBeforePostingEachWeek() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(validScheduleHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(weeklyLandingHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(weeklyWeekHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(validScheduleHtml()))
            val progress = mutableListOf<Pair<Int, Int>>()

            val result = AcademicSemesterImportService(ApiProbeService(), FixedParser(listOf(course())))
                .importSemester(
                    cookie = "JSESSIONID=test",
                    baseUrl = server.url("/").toString(),
                    semester = semester(),
                    studentIdFallback = "student-internal-id",
                    useWeeklyTimetable = true,
                    onProgress = { completed, total -> progress += completed to total }
                )

            assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)
            val occurrence = result.getOrThrow().courses.single().occurrences.single()
            assertEquals(2, occurrence.dayOfWeek)
            assertEquals("第1周", occurrence.weekText)
            assertEquals(1, result.getOrThrow().portalMaxWeek)
            assertEquals(listOf(1 to 1), progress)
            val currcourseRequest = server.takeRequest()
            val landingRequest = server.takeRequest()
            val weekRequest = server.takeRequest()
            val timetableRequest = server.takeRequest()
            assertTrue(currcourseRequest.path.orEmpty().contains("currcourse.jsdo"))
            assertTrue(landingRequest.path.orEmpty().contains("studentWeeklyTimetable.do?yearid=45&termid=1"))
            assertEquals("POST", weekRequest.method)
            assertEquals("yearid=45&termid=1&whichWeek=1", weekRequest.body.readUtf8())
            assertEquals(server.url("academic/manager/coursearrange/studentWeeklyTimetable.do?yearid=45&termid=1").toString(), weekRequest.getHeader("Referer"))
            assertTrue(timetableRequest.path.orEmpty().contains("showTimetable.do"))
        }
    }

    @Test
    fun accurateImportRequestsWeeksStrictlySequentiallyInSortedOrder() = runTest {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)
        val requestedWeeks = Collections.synchronizedList(mutableListOf<Int>())
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    return when {
                        request.path.orEmpty().contains("currcourse.jsdo") ->
                            MockResponse().setResponseCode(200).setBody(validScheduleHtml())
                        request.method == "GET" ->
                            MockResponse().setResponseCode(200)
                                .setBody(weeklyLandingHtml(listOf(6, 3, 1, 5, 2, 4)))
                        else -> {
                            val week = Regex("whichWeek=(\\d+)").find(request.body.readUtf8())
                                ?.groupValues?.get(1)?.toInt() ?: 0
                            requestedWeeks += week
                            val now = active.incrementAndGet()
                            maximumActive.updateAndGet { current -> maxOf(current, now) }
                            Thread.sleep(120)
                            active.decrementAndGet()
                            MockResponse().setResponseCode(200).setBody(weeklyWeekHtml(week, (1..6).toList()))
                        }
                    }
                }
            }

            val result = AcademicSemesterImportService(ApiProbeService(), FixedParser(listOf(course())))
                .importSemester(
                    cookie = "JSESSIONID=test",
                    baseUrl = server.url("/").toString(),
                    semester = semester(),
                    studentIdFallback = "",
                    useWeeklyTimetable = true
                )

            assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)
            assertEquals(6, result.getOrThrow().portalMaxWeek)
            assertEquals(1, maximumActive.get())
            assertEquals((1..6).toList(), requestedWeeks)
        }
    }

    @Test
    fun accurateImportRejectsSelectedWeekWhoseBodyBelongsToAnotherNaturalWeek() = runTest {
        MockWebServer().use { server ->
            val oldCache = listOf(course().copy(title = "旧缓存课程"))
            var visibleCache = oldCache
            var replaceCount = 0
            val progress = mutableListOf<Pair<Int, Int>>()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path.orEmpty().contains("currcourse.jsdo")) {
                        return MockResponse().setResponseCode(200).setBody(validScheduleHtml())
                    }
                    if (request.method == "GET") {
                        return MockResponse().setResponseCode(200)
                            .setBody(weeklyLandingHtml(listOf(1, 2)))
                    }
                    val week = Regex("whichWeek=(\\d+)").find(request.body.readUtf8())
                        ?.groupValues?.get(1)?.toInt() ?: 0
                    return MockResponse().setResponseCode(200).setBody(
                        weeklyWeekHtml(week, listOf(1, 2), bodyWeek = 1)
                    )
                }
            }

            val result = AcademicSemesterImportService(ApiProbeService(), FixedParser(listOf(course())))
                .importSemester(
                    cookie = "JSESSIONID=test",
                    baseUrl = server.url("/").toString(),
                    semester = semester(),
                    studentIdFallback = "",
                    useWeeklyTimetable = true,
                    onProgress = { completed, total -> progress += completed to total }
                )

            // 调用方协议：只有完整导入成功才允许执行原子 replace；失败 Result 不得消费 payload。
            result.onSuccess { payload ->
                replaceCount += 1
                visibleCache = payload.courses
            }

            assertTrue(result.isFailure)
            assertEquals(0, replaceCount)
            assertEquals(oldCache, visibleCache)
            assertEquals(listOf(1 to 2), progress)
        }
    }

    @Test
    fun nanningDiscardsPersonalOccurrencesAndKeepsWeeklySectionsOneThroughElevenUnshifted() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(validScheduleHtml(term = "2")))
            server.enqueue(MockResponse().setResponseCode(200).setBody(weeklyLandingHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(nanningWeeklyWeekHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(validScheduleHtml(term = "2")))
            val parser = FailIfPersonalOccurrencesAreAppliedParser(listOf(course()))

            val result = AcademicSemesterImportService(ApiProbeService(), parser)
                .importSemester(
                    cookie = "JSESSIONID=test",
                    baseUrl = server.url("/").toString(),
                    semester = nanningSemester(),
                    studentIdFallback = "student-internal-id",
                    useWeeklyTimetable = true
                )

            assertTrue(result.exceptionOrNull()?.stackTraceToString().orEmpty(), result.isSuccess)
            val occurrences = result.getOrThrow().courses.single().occurrences
            assertEquals(1, occurrences.size)
            assertEquals(3, occurrences.single().dayOfWeek)
            assertEquals(10, occurrences.single().startSection)
            assertEquals(11, occurrences.single().endSection)
            assertEquals("第1周", occurrences.single().weekText)
            assertEquals(0, parser.applyAdjustmentsCalls)
        }
    }

    @Test
    fun accurateImportRejectsRawRowsWithoutUsableOccurrences() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(validScheduleHtml()))
            server.enqueue(MockResponse().setResponseCode(200).setBody(weeklyLandingHtml()))
            server.enqueue(
                MockResponse().setResponseCode(200)
                    .setBody(weeklyWeekHtml().replace("星期二", "未知星期"))
            )

            val result = AcademicSemesterImportService(ApiProbeService(), FixedParser(listOf(course())))
                .importSemester(
                    cookie = "JSESSIONID=test",
                    baseUrl = server.url("/").toString(),
                    semester = semester(),
                    studentIdFallback = "",
                    useWeeklyTimetable = true
            )

            // 坏行被静默跳过 → 整页无可解析课程 → 导入失败并保留缓存
            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(message.contains("未返回课程") || message.contains("未解析到有效上课时间"))
        }
    }

    private suspend fun importFrom(
        body: String,
        courses: List<ScheduleCourse> = emptyList()
    ) = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        AcademicSemesterImportService(ApiProbeService(), FixedParser(courses)).importSemester(
            cookie = "JSESSIONID=test",
            baseUrl = server.url("/").toString(),
            semester = semester(),
            studentIdFallback = "",
            useWeeklyTimetable = false
        )
    }

    private fun validScheduleHtml(term: String = "1") = """
        <form><select name="year"><option value="45" selected>2025</option></select>
        <select name="term"><option value="$term" selected>春</option></select></form>
        <table id="manualArrangeCourseTable"></table>
    """.trimIndent()

    private fun weeklyLandingHtml(weeks: List<Int> = listOf(1)) = """
        <html><body><form><span>2025春 第 </span><select name="whichWeek">
        <option value=""></option>${weeks.joinToString("") { "<option value=\"$it\">$it</option>" }}</select><span> 周 周次课表</span></form>
        <table><tr><th>日期</th><th>课程名</th><th>选课属性</th><th>考试性质</th><th>星期</th>
        <th>节次</th><th>开始时间</th><th>结束时间</th><th>教学楼</th><th>教室</th><th></th></tr></table>
        </body></html>
    """.trimIndent()

    private fun weeklyWeekHtml(
        week: Int = 1,
        weeks: List<Int> = listOf(1),
        bodyWeek: Int = week
    ): String {
        val date = LocalDate.of(2025, 3, 4).plusWeeks((bodyWeek - 1).toLong())
        return """
        <html><body><form><span>2025春 第 </span><select name="whichWeek">
        ${weeks.joinToString("") { "<option value=\"$it\"${if (it == week) " selected" else ""}>$it</option>" }}</select><span> 周 周次课表</span></form>
        <table><tr><th>日期</th><th>课程名</th><th>选课属性</th><th>考试性质</th><th>星期</th>
        <th>节次</th><th>开始时间</th><th>结束时间</th><th>教学楼</th><th>教室</th><th></th></tr>
        <tr><td>$date</td><td>测试课程</td><td>必修</td><td>正常考试</td><td>星期二</td>
        <td>第1、2节</td><td>08:20</td><td>10:00</td><td>教学楼</td><td>A101</td><td></td></tr></table>
        </body></html>
    """.trimIndent()
    }

    private fun nanningWeeklyWeekHtml() = """
        <html><body><form><span>2025春 第 </span><select name="whichWeek">
        <option value="1" selected>1</option></select><span> 周 周次课表</span></form>
        <table><tr><th>日期</th><th>课程名</th><th>选课属性</th><th>考试性质</th><th>星期</th>
        <th>节次</th><th>开始时间</th><th>结束时间</th><th>教学楼</th><th>教室</th><th></th></tr>
        <tr><td>2025-03-05</td><td>测试课程</td><td>必修</td><td>正常考试</td><td>星期三</td>
        <td>第10、11节</td><td>18:30</td><td>20:10</td><td>教学楼</td><td>A101</td><td></td></tr></table>
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

    private class FailIfPersonalOccurrencesAreAppliedParser(
        private val courses: List<ScheduleCourse>
    ) : AcademicScheduleParser {
        var applyAdjustmentsCalls = 0

        override fun parsePersonalSchedule(html: String): List<ScheduleCourse> = courses

        override fun applyAdjustmentsToCourses(
            courses: List<ScheduleCourse>,
            adjustmentHtml: String
        ): List<ScheduleCourse> {
            applyAdjustmentsCalls += 1
            error("个人课表 occurrence 不得进入周次结果")
        }
    }
}

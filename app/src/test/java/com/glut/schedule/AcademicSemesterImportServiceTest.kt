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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals(listOf(course), result.getOrThrow().courses)
        assertEquals(AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE, result.getOrThrow().responseKind)
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
            studentIdFallback = ""
        )
    }

    private fun validScheduleHtml() = """
        <form><select name="year"></select><select name="term"></select></form>
        <table id="manualArrangeCourseTable"></table>
    """.trimIndent()

    private fun semester() = AcademicSemester.create(
        CampusType.GUILIN, 2025, "45", SemesterSeason.SPRING, "1", isCurrent = true
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
}

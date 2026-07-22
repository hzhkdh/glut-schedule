package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.parser.AcademicSemesterParser
import com.glut.schedule.service.academic.AcademicSemesterRequestBuilder
import com.glut.schedule.service.academic.AcademicSemesterResponseValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AcademicSemesterCatalogTest {
    @Test
    fun semesterKeyAndLabelUseCampusPortalYearAndSeason() {
        val semester = AcademicSemester.create(
            campus = CampusType.GUILIN,
            portalYear = 2025,
            portalYearId = "45",
            season = SemesterSeason.SPRING,
            portalTermId = "1",
            isCurrent = true
        )

        assertEquals("guilin:2025:spring", semester.id)
        assertEquals("2024-2025 学年 · 春", semester.displayName)
    }

    @Test
    fun parserUsesActualCampusTermOptionsAndFiltersBeforeEnrollment() {
        val guilinHtml = semesterForm(
            selectedYear = 2025,
            springTerm = "1",
            autumnTerm = "2"
        )
        val nanningHtml = semesterForm(
            selectedYear = 2025,
            springTerm = "1",
            autumnTerm = "3"
        )

        val guilin = AcademicSemesterParser.parseCatalog(
            html = guilinHtml,
            campus = CampusType.GUILIN,
            enrollmentDate = LocalDate.of(2024, 9, 1),
            today = LocalDate.of(2025, 5, 1)
        )
        val nanning = AcademicSemesterParser.parseCatalog(
            html = nanningHtml,
            campus = CampusType.NANNING,
            enrollmentDate = LocalDate.of(2024, 9, 1),
            today = LocalDate.of(2025, 5, 1)
        )

        assertEquals(listOf("guilin:2025:spring", "guilin:2024:autumn"), guilin.map { it.id })
        assertEquals("2", guilin.last().portalTermId)
        assertEquals("3", nanning.last().portalTermId)
        assertTrue(guilin.first().isCurrent)
        assertFalse(guilin.last().isCurrent)
    }

    @Test
    fun enrollmentParserPrefersEntranceDateAndCrossChecksEnrollmentYear() {
        val html = """
            <form>
              <input type="hidden" name="entranceDate" value="2024-09-08" />
              <input value="2024" name="enrollYearId" type="hidden" />
            </form>
        """.trimIndent()

        val enrollment = AcademicSemesterParser.parseEnrollment(html)

        assertEquals(LocalDate.of(2024, 9, 8), enrollment?.entranceDate)
        assertEquals(2024, enrollment?.enrollmentYear)
        assertTrue(enrollment?.isConsistent == true)
    }

    @Test
    fun historicalRequestUsesCatalogYearAndTermValues() {
        val semester = AcademicSemester.create(
            CampusType.NANNING, 2024, "44", SemesterSeason.AUTUMN, "3", isCurrent = false
        )

        assertEquals(
            "http://jw.glutnn.cn/academic/student/currcourse/currcourse.jsdo?year=44&term=3",
            AcademicSemesterRequestBuilder.currcourseUrl("http://jw.glutnn.cn", semester)
        )
        assertEquals(
            "http://jw.glutnn.cn/academic/manager/coursearrange/showTimetable.do?id=student-id&yearid=44&termid=3&timetableType=STUDENT&sectionType=BASE",
            AcademicSemesterRequestBuilder.timetableUrl("http://jw.glutnn.cn", "student-id", semester)
        )
    }

    @Test
    fun loginPageIsRejectedEvenWhenTheServerReturnsSuccess() {
        val loginHtml = """
            <form action="j_acegi_security_check">
              <input type="password" name="j_password" />
            </form>
        """.trimIndent()

        assertFalse(AcademicSemesterResponseValidator.isSchedulePage(loginHtml))
        assertTrue(AcademicSemesterResponseValidator.isSchedulePage("<table id=\"manualArrangeCourseTable\"></table>"))
    }

    private fun semesterForm(selectedYear: Int, springTerm: String, autumnTerm: String): String = """
        <select name="year">
          <option value="43">2023</option>
          <option value="44">2024</option>
          <option value="45" selected>$selectedYear</option>
        </select>
        <select name="term">
          <option value="$springTerm" selected>春季学期</option>
          <option value="$autumnTerm">秋季学期</option>
        </select>
    """.trimIndent()
}

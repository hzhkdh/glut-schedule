package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.AcademicEnrollmentSource
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
    fun nextSemesterAfterSpringUsesEachCampusActualAutumnTermWithoutFarFutureExposure() {
        listOf(CampusType.GUILIN to "2", CampusType.NANNING to "3").forEach { (campus, autumnTerm) ->
            val plan = AcademicSemesterParser.parseCatalogPlan(
                html = semesterFormWithFutureYears(
                    selectedYear = 2025,
                    selectedSeason = SemesterSeason.SPRING,
                    autumnTerm = autumnTerm
                ),
                campus = campus,
                enrollmentDate = LocalDate.of(2024, 9, 1),
                today = LocalDate.of(2025, 5, 1)
            )

            assertEquals(2025, plan.nextSemester?.portalYear)
            assertEquals(SemesterSeason.AUTUMN, plan.nextSemester?.season)
            assertEquals("45", plan.nextSemester?.portalYearId)
            assertEquals(autumnTerm, plan.nextSemester?.portalTermId)
            assertFalse(plan.semesters.any { it.portalYear > 2025 })
            assertEquals(1, plan.semesters.count { it.isCurrent })
        }
    }

    @Test
    fun nextSemesterAfterAutumnUsesNextSpringYearWithoutFarFutureExposure() {
        listOf(CampusType.GUILIN to "2", CampusType.NANNING to "3").forEach { (campus, autumnTerm) ->
            val plan = AcademicSemesterParser.parseCatalogPlan(
                html = semesterFormWithFutureYears(
                    selectedYear = 2025,
                    selectedSeason = SemesterSeason.AUTUMN,
                    autumnTerm = autumnTerm
                ),
                campus = campus,
                enrollmentDate = LocalDate.of(2024, 9, 1),
                today = LocalDate.of(2025, 10, 1)
            )

            assertEquals(2026, plan.nextSemester?.portalYear)
            assertEquals(SemesterSeason.SPRING, plan.nextSemester?.season)
            assertEquals("46", plan.nextSemester?.portalYearId)
            assertEquals("1", plan.nextSemester?.portalTermId)
            assertFalse(plan.semesters.any { it.portalYear > 2025 })
            assertFalse(plan.semesters.any { it.portalYear == 2027 })
            assertEquals(1, plan.semesters.count { it.isCurrent })
        }
    }

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
    fun guilinEnrollmentPrefersEntranceDateAndNormalizesPortalOffsets() {
        val html = """
            <form>
              <input type="hidden" name="entranceDate" value="2024-03-08" />
              <input value="44" name="enrollYearId" type="hidden" />
              <input value="33666" name="gradeId" type="hidden" />
            </form>
        """.trimIndent()

        val enrollment = AcademicSemesterParser.parseEnrollment(html, "3242050858113")

        assertEquals(LocalDate.of(2024, 3, 8), enrollment?.entranceDate)
        assertEquals(2024, enrollment?.enrollmentYear)
        assertEquals(AcademicEnrollmentSource.ENTRANCE_DATE, enrollment?.source)
        assertTrue(enrollment?.isConsistent == true)

        val catalog = AcademicSemesterParser.parseCatalog(
            html = semesterForm(selectedYear = 2025, springTerm = "1", autumnTerm = "2"),
            campus = CampusType.GUILIN,
            enrollmentDate = enrollment!!.entranceDate!!,
            today = LocalDate.of(2025, 5, 1)
        )
        assertEquals(listOf("guilin:2025:spring", "guilin:2024:autumn"), catalog.map { it.id })
    }

    @Test
    fun nanningEnrollmentUsesFirstValidTwoDigitPortalYear() {
        val html = """
            <form>
              <input value="44" name="enrollYearId" type="hidden" />
              <input value="43" name="entranceGradeId" type="hidden" />
              <input value="33666" name="gradeId" type="hidden" />
            </form>
        """.trimIndent()

        val enrollment = AcademicSemesterParser.parseEnrollment(html, "not-a-student-number")

        assertEquals(2024, enrollment?.enrollmentYear)
        assertEquals(AcademicEnrollmentSource.PORTAL_FIELD, enrollment?.source)
        assertEquals(LocalDate.of(2024, 9, 1), enrollment?.catalogStartDate)

        val fourDigitEnrollment = AcademicSemesterParser.parseEnrollment(
            "<input value=\"2024\" name=\"enrollYearId\" type=\"hidden\" />",
            currentYear = 2025
        )
        assertEquals(2024, fourDigitEnrollment?.enrollmentYear)
        assertEquals(AcademicEnrollmentSource.PORTAL_FIELD, fourDigitEnrollment?.source)
    }

    @Test
    fun studentNumberFallbackUsesCharactersTwoAndThree() {
        listOf("3242050858113", "5241994207").forEach { studentNumber ->
            val enrollment = AcademicSemesterParser.parseEnrollment("", studentNumber)

            assertEquals(2024, enrollment?.enrollmentYear)
            assertEquals(AcademicEnrollmentSource.STUDENT_NUMBER, enrollment?.source)
            assertEquals(LocalDate.of(2024, 9, 1), enrollment?.catalogStartDate)
        }
    }

    @Test
    fun malformedStudentNumberAndInvalidPortalIdsDoNotInventEnrollment() {
        val html = """
            <input value="33666" name="enrollYearId" type="hidden" />
            <input value="1999" name="entranceGradeId" type="hidden" />
            <input value="9999" name="gradeId" type="hidden" />
        """.trimIndent()

        assertEquals(null, AcademicSemesterParser.parseEnrollment(html, "3x"))
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

    private fun semesterFormWithFutureYears(
        selectedYear: Int,
        selectedSeason: SemesterSeason,
        autumnTerm: String
    ): String = """
        <select name="year">
          <option value="44">2024</option>
          <option value="45"${if (selectedYear == 2025) " selected" else ""}>2025</option>
          <option value="46">2026</option>
          <option value="47">2027</option>
        </select>
        <select name="term">
          <option value="1"${if (selectedSeason == SemesterSeason.SPRING) " selected" else ""}>春季学期</option>
          <option value="$autumnTerm"${if (selectedSeason == SemesterSeason.AUTUMN) " selected" else ""}>秋季学期</option>
        </select>
    """.trimIndent()
}

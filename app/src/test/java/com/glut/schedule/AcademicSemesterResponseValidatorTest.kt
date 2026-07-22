package com.glut.schedule

import com.glut.schedule.service.academic.AcademicSemesterResponseKind
import com.glut.schedule.service.academic.AcademicSemesterResponseValidator
import org.junit.Assert.assertEquals
import org.junit.Test

class AcademicSemesterResponseValidatorTest {
    @Test
    fun classifiesAuthenticationExpirySeparately() {
        val body = """<form action="j_acegi_security_check"><input type="password" /></form>"""

        assertEquals(
            AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED,
            AcademicSemesterResponseValidator.classify(body, courseCount = 0)
        )
    }

    @Test
    fun rejectsRandomSuccessfulHtmlWithoutTimetableStructure() {
        val body = "<html><body><h1>系统公告</h1><p>欢迎使用教务系统</p></body></html>"

        assertEquals(
            AcademicSemesterResponseKind.INVALID_STRUCTURE,
            AcademicSemesterResponseValidator.classify(body, courseCount = 0)
        )
    }

    @Test
    fun distinguishesValidEmptyAndNonEmptySchedules() {
        val body = """
            <form><select name="year"></select><select name="term"></select></form>
            <table id="manualArrangeCourseTable"></table>
        """.trimIndent()

        assertEquals(
            AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE,
            AcademicSemesterResponseValidator.classify(body, courseCount = 0)
        )
        assertEquals(
            AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE,
            AcademicSemesterResponseValidator.classify(body, courseCount = 1)
        )
    }
}

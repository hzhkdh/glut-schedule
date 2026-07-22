package com.glut.schedule.service.academic

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.parser.AcademicScheduleParser
import java.net.URLEncoder

object AcademicSemesterRequestBuilder {
    fun currcourseUrl(baseUrl: String, semester: AcademicSemester): String =
        "${baseUrl.trimEnd('/')}/academic/student/currcourse/currcourse.jsdo" +
            "?year=${encode(semester.portalYearId)}&term=${encode(semester.portalTermId)}"

    fun timetableUrl(baseUrl: String, studentId: String, semester: AcademicSemester): String =
        "${baseUrl.trimEnd('/')}/academic/manager/coursearrange/showTimetable.do" +
            "?id=${encode(studentId)}" +
            "&yearid=${encode(semester.portalYearId)}" +
            "&termid=${encode(semester.portalTermId)}" +
            "&timetableType=STUDENT&sectionType=BASE"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

object AcademicSemesterResponseValidator {
    fun isSchedulePage(body: String): Boolean {
        if (body.isBlank()) return false
        val normalized = body.lowercase()
        val isLoginPage = normalized.contains("j_acegi_security_check") ||
            normalized.contains("name=\"j_password\"") ||
            normalized.contains("name='j_password'") ||
            normalized.contains("type=\"password\"") ||
            normalized.contains("type='password'")
        return !isLoginPage
    }
}

data class AcademicSemesterImportPayload(
    val courses: List<ScheduleCourse>,
    val adjustments: List<SemesterAdjustment>,
    val currcourseHtml: String,
    val timetableHtml: String
)

class AcademicSemesterImportService(
    private val apiProbeService: ApiProbeService,
    private val scheduleParser: AcademicScheduleParser
) {
    suspend fun importSemester(
        cookie: String,
        baseUrl: String,
        semester: AcademicSemester,
        studentIdFallback: String
    ): Result<AcademicSemesterImportPayload> = runCatching {
        val currcourse = requireNotNull(
            apiProbeService.probeUrl(cookie, AcademicSemesterRequestBuilder.currcourseUrl(baseUrl, semester))
        ) { "无法下载${semester.displayName}课表" }
        require(currcourse.httpCode in 200..299) { "教务系统返回 ${currcourse.httpCode}" }
        require(AcademicSemesterResponseValidator.isSchedulePage(currcourse.body)) {
            "登录状态已失效，请重新登录后再导入"
        }

        val studentId = ApiProbeService.extractInternalIdFromCurrcourse(currcourse.body)
            .orEmpty().ifBlank { studentIdFallback }
        val timetable = if (studentId.isNotBlank()) {
            apiProbeService.probeUrl(
                cookie,
                AcademicSemesterRequestBuilder.timetableUrl(baseUrl, studentId, semester)
            )
        } else null
        val timetableHtml = timetable?.takeIf { it.httpCode in 200..299 }?.body.orEmpty()
        require(timetableHtml.isBlank() || AcademicSemesterResponseValidator.isSchedulePage(timetableHtml)) {
            "登录状态已失效，请重新登录后再导入"
        }
        val adjustments = if (timetableHtml.isBlank()) emptyList()
            else scheduleParser.parseAdjustments(timetableHtml)
        var courses = scheduleParser.parsePersonalSchedule(currcourse.body)
        if (semester.campus == CampusType.NANNING && timetableHtml.isNotBlank()) {
            courses = scheduleParser.applyAdjustmentsToCourses(courses, timetableHtml)
        }
        AcademicSemesterImportPayload(
            courses = courses,
            adjustments = adjustments,
            currcourseHtml = currcourse.body,
            timetableHtml = timetableHtml
        )
    }
}

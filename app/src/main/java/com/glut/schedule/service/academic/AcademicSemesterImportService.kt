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
    fun classify(body: String, courseCount: Int): AcademicSemesterResponseKind {
        if (isLoginPage(body)) return AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED
        if (!hasScheduleStructure(body)) return AcademicSemesterResponseKind.INVALID_STRUCTURE
        return if (courseCount > 0) {
            AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE
        } else {
            AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE
        }
    }

    fun isSchedulePage(body: String): Boolean {
        return !isLoginPage(body) && hasScheduleStructure(body)
    }

    private fun isLoginPage(body: String): Boolean {
        val normalized = body.lowercase()
        return normalized.contains("j_acegi_security_check") ||
            normalized.contains("name=\"j_password\"") ||
            normalized.contains("name='j_password'") ||
            normalized.contains("type=\"password\"") ||
            normalized.contains("type='password'")
    }

    private fun hasScheduleStructure(body: String): Boolean {
        if (body.isBlank()) return false
        val normalized = body.lowercase()
        val timetableId = Regex("""id\s*=\s*['\"](?:timetable|manualarrangecoursetable)['\"]""")
            .containsMatchIn(normalized)
        val scheduleHeading = listOf("学生课表", "学生个人课表", "个人课表", "当前课程", "本学期课程")
            .any(body::contains)
        return timetableId || scheduleHeading
    }
}

enum class AcademicSemesterResponseKind {
    AUTHENTICATION_EXPIRED,
    INVALID_STRUCTURE,
    VALID_EMPTY_SCHEDULE,
    VALID_NON_EMPTY_SCHEDULE
}

data class AcademicSemesterImportPayload(
    val courses: List<ScheduleCourse>,
    val adjustments: List<SemesterAdjustment>,
    val currcourseHtml: String,
    val timetableHtml: String,
    val responseKind: AcademicSemesterResponseKind
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
        when (AcademicSemesterResponseValidator.classify(currcourse.body, courseCount = 0)) {
            AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED ->
                error("登录状态已失效，请重新登录后再导入")
            AcademicSemesterResponseKind.INVALID_STRUCTURE ->
                error("无法识别课表结构，未覆盖已有缓存")
            else -> Unit
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
        if (timetableHtml.isNotBlank()) {
            when (AcademicSemesterResponseValidator.classify(timetableHtml, courseCount = 0)) {
                AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED ->
                    error("登录状态已失效，请重新登录后再导入")
                AcademicSemesterResponseKind.INVALID_STRUCTURE ->
                    error("无法识别课表结构，未覆盖已有缓存")
                else -> Unit
            }
        }
        val adjustments = if (timetableHtml.isBlank()) emptyList()
            else scheduleParser.parseAdjustments(timetableHtml)
        var courses = scheduleParser.parsePersonalSchedule(currcourse.body)
        if (semester.campus == CampusType.NANNING && timetableHtml.isNotBlank()) {
            courses = scheduleParser.applyAdjustmentsToCourses(courses, timetableHtml)
        }
        val responseKind = AcademicSemesterResponseValidator.classify(currcourse.body, courses.size)
        AcademicSemesterImportPayload(
            courses = courses,
            adjustments = adjustments,
            currcourseHtml = currcourse.body,
            timetableHtml = timetableHtml,
            responseKind = responseKind
        )
    }
}

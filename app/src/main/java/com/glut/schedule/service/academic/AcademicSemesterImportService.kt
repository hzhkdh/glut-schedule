package com.glut.schedule.service.academic

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.parser.WeeklyTimetableParser
import com.glut.schedule.service.parser.validateFor
import java.net.URLEncoder
import java.time.LocalDate

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

    fun weeklyTimetableUrl(baseUrl: String, semester: AcademicSemester): String =
        "${baseUrl.trimEnd('/')}/academic/manager/coursearrange/studentWeeklyTimetable.do" +
            "?yearid=${encode(semester.portalYearId)}&termid=${encode(semester.portalTermId)}"

    fun weeklyTimetablePostUrl(baseUrl: String): String =
        "${baseUrl.trimEnd('/')}/academic/manager/coursearrange/studentWeeklyTimetable.do"

    fun weeklyTimetableForm(semester: AcademicSemester, week: Int): String =
        "yearid=${encode(semester.portalYearId)}&termid=${encode(semester.portalTermId)}&whichWeek=$week"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}

object AcademicSemesterResponseValidator {
    fun classify(body: String, courseCount: Int): AcademicSemesterResponseKind {
        if (isLoginPage(body)) return AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED
        if (courseCount > 0) return AcademicSemesterResponseKind.VALID_NON_EMPTY_SCHEDULE
        return if (hasScheduleStructure(body)) {
            AcademicSemesterResponseKind.VALID_EMPTY_SCHEDULE
        } else {
            AcademicSemesterResponseKind.INVALID_STRUCTURE
        }
    }

    fun isSchedulePage(body: String): Boolean {
        return !isLoginPage(body) && hasScheduleStructure(body)
    }

    fun matchesRequestedSemester(body: String, semester: AcademicSemester): Boolean {
        val selectedYear = selectedOptionValue(body, "year")
        val selectedTerm = selectedOptionValue(body, "term")
        return (selectedYear == null || selectedYear == semester.portalYearId) &&
            (selectedTerm == null || selectedTerm == semester.portalTermId)
    }

    private fun selectedOptionValue(body: String, selectName: String): String? {
        val selectRegex = Regex(
            """<select\b([^>]*)>([\s\S]*?)</select>""",
            RegexOption.IGNORE_CASE
        )
        return selectRegex.findAll(body).firstNotNullOfOrNull { select ->
            val attributes = select.groupValues[1]
            val name = attributeValue(attributes, "name")
            if (!name.equals(selectName, ignoreCase = true)) return@firstNotNullOfOrNull null
            val option = Regex(
                """<option\b((?=[^>]*\bselected\b)[^>]*)>""",
                RegexOption.IGNORE_CASE
            ).find(select.groupValues[2]) ?: return@firstNotNullOfOrNull null
            attributeValue(option.groupValues[1], "value")
        }
    }

    private fun attributeValue(attributes: String, name: String): String? {
        val match = Regex(
            """\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>]+))""",
            RegexOption.IGNORE_CASE
        ).find(attributes) ?: return null
        return match.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.trim()
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
    val responseKind: AcademicSemesterResponseKind,
    val portalMaxWeek: Int? = null
)

class AcademicSemesterImportService(
    private val apiProbeService: ApiProbeService,
    private val scheduleParser: AcademicScheduleParser,
    private val weeklyTimetableParser: WeeklyTimetableParser = WeeklyTimetableParser()
) {
    suspend fun importSemester(
        cookie: String,
        baseUrl: String,
        semester: AcademicSemester,
        studentIdFallback: String,
        useWeeklyTimetable: Boolean = true,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<AcademicSemesterImportPayload> = runCatching {
        val currcourse = apiProbeService.probeUrl(cookie, AcademicSemesterRequestBuilder.currcourseUrl(baseUrl, semester))
            ?: error("无法连接教务服务器，下载${semester.displayName}课表失败")
        require(currcourse.httpCode in 200..299) {
            "教务系统返回 HTTP ${currcourse.httpCode}，下载${semester.displayName}课表失败"
        }
        when (AcademicSemesterResponseValidator.classify(currcourse.body, courseCount = 0)) {
            AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED ->
                error("登录状态已失效，请重新登录后再导入")
            else -> Unit
        }
        require(AcademicSemesterResponseValidator.matchesRequestedSemester(currcourse.body, semester)) {
            "教务系统返回学期与请求不一致，已保留现有缓存"
        }

        val personalCourses = scheduleParser.parsePersonalSchedule(currcourse.body)
        val responseKind = AcademicSemesterResponseValidator.classify(currcourse.body, personalCourses.size)
        if (responseKind == AcademicSemesterResponseKind.INVALID_STRUCTURE) {
            error("无法识别课表结构，未覆盖已有缓存")
        }
        var courses = personalCourses.map { it.copy(occurrences = emptyList()) }

        var adjustments = emptyList<SemesterAdjustment>()
        var resolvedTimetableHtml = ""
        var portalMaxWeek: Int? = null
        if (useWeeklyTimetable) {
            val landingUrl = AcademicSemesterRequestBuilder.weeklyTimetableUrl(baseUrl, semester)
            val landing = apiProbeService.probeUrl(cookie, landingUrl)
                ?: error("无法连接教务服务器，打开${semester.displayName}周次课表失败")
            require(landing.httpCode in 200..299) {
                "周次课表返回 HTTP ${landing.httpCode}"
            }
            val landingPage = weeklyTimetableParser.parsePage(
                landing.body,
                hasNoon = semester.campus != CampusType.NANNING
            )
            require(landingPage.semesterLabel == semesterPortalLabel(semester)) {
                "周次课表返回学期与请求不一致，已保留现有缓存"
            }
            require(landingPage.availableWeeks.isNotEmpty()) { "周次课表未提供可下载周次" }
            portalMaxWeek = landingPage.availableWeeks.maxOrNull()
            var expectedSemesterMonday: LocalDate? = null
            val pages = buildList {
                landingPage.availableWeeks.sorted().forEach { week ->
                    val response = apiProbeService.probeForm(
                        cookie = cookie,
                        url = AcademicSemesterRequestBuilder.weeklyTimetablePostUrl(baseUrl),
                        body = AcademicSemesterRequestBuilder.weeklyTimetableForm(semester, week),
                        referer = landingUrl
                    ) ?: error("第${week}周课表下载失败：网络请求返回空")
                    require(response.httpCode in 200..299) {
                        "第${week}周课表返回 HTTP ${response.httpCode}"
                    }
                    val page = weeklyTimetableParser.parsePage(
                        response.body,
                        hasNoon = semester.campus != CampusType.NANNING
                    )
                    expectedSemesterMonday = page.validateFor(
                        expectedWeek = week,
                        expectedSemesterLabel = semesterPortalLabel(semester),
                        expectedSemesterMonday = expectedSemesterMonday
                    )
                    add(page)
                    onProgress(size, landingPage.availableWeeks.size)
                }
            }
            require(pages.any { it.rows.isNotEmpty() } || courses.isEmpty()) {
                "周次课表未返回课程，已保留现有缓存"
            }
            val mergedCourses = weeklyTimetableParser.mergeWithMetadata(courses, pages)
            require(courses.isEmpty() || mergedCourses.any { it.occurrences.isNotEmpty() }) {
                "周次课表未解析到有效上课时间，已保留现有缓存"
            }
            courses = mergedCourses
            val weeklyAdjustments = scheduleParser.parseAdjustments(landing.body)
            if (weeklyAdjustments.isNotEmpty()) adjustments = weeklyAdjustments
            resolvedTimetableHtml = landing.body
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
                else -> Unit
            }
            if (adjustments.isEmpty()) adjustments = scheduleParser.parseAdjustments(timetableHtml)
            if (!useWeeklyTimetable) resolvedTimetableHtml = timetableHtml
        }
        AcademicSemesterImportPayload(
            courses = courses,
            adjustments = adjustments,
            currcourseHtml = currcourse.body,
            timetableHtml = resolvedTimetableHtml,
            responseKind = responseKind,
            portalMaxWeek = portalMaxWeek
        )
    }

    private fun semesterPortalLabel(semester: AcademicSemester): String {
        val season = when (semester.season) {
            com.glut.schedule.data.model.SemesterSeason.SPRING -> "春"
            com.glut.schedule.data.model.SemesterSeason.AUTUMN -> "秋"
        }
        return "${semester.portalYear}$season"
    }
}

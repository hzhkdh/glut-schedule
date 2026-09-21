package com.glut.schedule.service.academic

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.MIN_ACADEMIC_WEEK
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.countUnparsedWeekTexts
import com.glut.schedule.data.model.derivedAcademicMaxWeek
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.SemesterImportMode
import com.glut.schedule.service.parser.AcademicScheduleParser
import com.glut.schedule.service.parser.WeeklyTimetableParser
import com.glut.schedule.service.parser.validateFor
import java.net.URLEncoder
import java.time.LocalDate

object AcademicSemesterRequestBuilder {
    fun currcourseUrl(baseUrl: String, semester: AcademicSemester): String =
        "${normalizedBaseUrl(baseUrl)}/academic/student/currcourse/currcourse.jsdo" +
            "?year=${encode(semester.portalYearId)}&term=${encode(semester.portalTermId)}"

    fun timetableUrl(baseUrl: String, studentId: String, semester: AcademicSemester): String =
        "${normalizedBaseUrl(baseUrl)}/academic/manager/coursearrange/showTimetable.do" +
            "?id=${encode(studentId)}" +
            "&yearid=${encode(semester.portalYearId)}" +
            "&termid=${encode(semester.portalTermId)}" +
            "&timetableType=STUDENT&sectionType=BASE"

    fun weeklyTimetableUrl(baseUrl: String, semester: AcademicSemester): String =
        "${normalizedBaseUrl(baseUrl)}/academic/manager/coursearrange/studentWeeklyTimetable.do" +
            "?yearid=${encode(semester.portalYearId)}&termid=${encode(semester.portalTermId)}"

    fun weeklyTimetablePostUrl(baseUrl: String): String =
        "${normalizedBaseUrl(baseUrl)}/academic/manager/coursearrange/studentWeeklyTimetable.do"

    fun weeklyTimetableForm(semester: AcademicSemester, week: Int): String =
        "yearid=${encode(semester.portalYearId)}&termid=${encode(semester.portalTermId)}&whichWeek=$week"

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun normalizedBaseUrl(baseUrl: String): String =
        AcademicUrlPolicy.normalizeCampusBaseUrl(baseUrl).trimEnd('/')
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
    val portalMaxWeek: Int? = null,
    val semesterStartMonday: LocalDate? = null,
    val skippedRowCount: Int = 0,
    /** 本次串行下载完成后的最新会话，只允许交回会话存储，不得用于日志。 */
    val updatedCookie: String = "",
    /** 本次导入使用的线路，随学期落库，便于诊断以及向用户解释数据来源。 */
    val importMode: SemesterImportMode = SemesterImportMode.WEEKLY,
    /**
     * 无法识别周次的课次数量。这类课次不会出现在任何一周里，必须如实反馈给用户，
     * 否则表现就是「课程莫名其妙少了几门」，无从排查。
     */
    val unparsedWeekTextCount: Int = 0
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
        mode: SemesterImportMode = SemesterImportMode.WEEKLY,
        onProgress: (completed: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<AcademicSemesterImportPayload> = runCatching {
        var sessionCookie = cookie
        fun consumeSessionCookie(response: ApiProbeService.ProbeResult) {
            if (response.updatedCookie.isNotBlank()) sessionCookie = response.updatedCookie
        }

        val currcourse = apiProbeService.probeUrl(sessionCookie, AcademicSemesterRequestBuilder.currcourseUrl(baseUrl, semester))
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
        consumeSessionCookie(currcourse)

        val personalCourses = scheduleParser.parsePersonalSchedule(currcourse.body)
        val responseKind = AcademicSemesterResponseValidator.classify(currcourse.body, personalCourses.size)
        if (responseKind == AcademicSemesterResponseKind.INVALID_STRUCTURE) {
            error("无法识别课表结构，未覆盖已有缓存")
        }
        var courses = personalCourses

        val studentId = ApiProbeService.extractInternalIdFromCurrcourse(currcourse.body)
            .orEmpty().ifBlank { studentIdFallback }
        val timetable = if (studentId.isNotBlank()) {
            apiProbeService.probeUrl(
                sessionCookie,
                AcademicSemesterRequestBuilder.timetableUrl(baseUrl, studentId, semester)
            )
        } else null
        timetable?.let(::consumeSessionCookie)
        val timetableHtml = timetable?.takeIf { it.httpCode in 200..299 }?.body.orEmpty()
        if (timetableHtml.isNotBlank() &&
            AcademicSemesterResponseValidator.classify(timetableHtml, courseCount = 0) ==
            AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED
        ) {
            error("登录状态已失效，请重新登录后再导入")
        }
        // 个人课表把每个教室精确绑定到实际教师，仅作为逐周课表的优先元数据；
        // 解析失败时保持空列表，继续回退到课程安排页，避免新增导入失败条件。
        val preferredMetadataCourses = if (timetableHtml.isBlank()) {
            emptyList()
        } else {
            runCatching { scheduleParser.parsePersonalSchedule(timetableHtml) }
                .getOrDefault(emptyList())
        }

        var adjustments = if (timetableHtml.isBlank()) {
            emptyList()
        } else {
            scheduleParser.parseAdjustments(timetableHtml)
        }
        var resolvedTimetableHtml = if (mode == SemesterImportMode.WEEKLY) "" else timetableHtml
        var portalMaxWeek: Int? = null
        var semesterStartMonday: LocalDate? = null
        if (mode == SemesterImportMode.WEEKLY) {
            // 模式1：个人课表只提供教师/颜色等元数据，时间与教室以逐周课表为准，
            // 因此先清空 occurrences，随后由 mergeWithMetadata 逐周回填。
            courses = personalCourses.map { it.copy(occurrences = emptyList()) }
            val landingUrl = AcademicSemesterRequestBuilder.weeklyTimetableUrl(baseUrl, semester)
            val landing = apiProbeService.probeUrl(sessionCookie, landingUrl)
                ?: error("无法连接教务服务器，打开${semester.displayName}周次课表失败")
            require(landing.httpCode in 200..299) {
                "周次课表返回 HTTP ${landing.httpCode}"
            }
            consumeSessionCookie(landing)
            validateWeeklyProbeTransport(landing, "周次课表")
            val landingPage = runCatching {
                weeklyTimetableParser.parsePage(
                    landing.body,
                    hasNoon = semester.campus != CampusType.NANNING
                )
            }.getOrElse {
                error("周次课表页面无法识别，请重新登录后重试")
            }
            require(landingPage.semesterLabel == semesterPortalLabel(semester)) {
                "周次课表返回学期与请求不一致，已保留现有缓存"
            }
            require(landingPage.availableWeeks.isNotEmpty()) { "周次课表未提供可下载周次" }
            portalMaxWeek = landingPage.availableWeeks.maxOrNull()
            var skippedRowCount = 0
            val pages = buildList {
                landingPage.availableWeeks.sorted().forEach { week ->
                    val response = apiProbeService.probeForm(
                        cookie = sessionCookie,
                        url = AcademicSemesterRequestBuilder.weeklyTimetablePostUrl(baseUrl),
                        body = AcademicSemesterRequestBuilder.weeklyTimetableForm(semester, week),
                        referer = landingUrl
                    ) ?: error("第${week}周课表下载失败：网络请求返回空")
                    require(response.httpCode in 200..299) {
                        "第${week}周课表返回 HTTP ${response.httpCode}"
                    }
                    consumeSessionCookie(response)
                    validateWeeklyProbeTransport(response, "第${week}周课表")
                    val page = runCatching {
                        weeklyTimetableParser.parsePage(
                            response.body,
                            hasNoon = semester.campus != CampusType.NANNING
                        )
                    }.getOrElse {
                        error("第${week}周课表页面无法识别，请重新登录后重试")
                    }
                    semesterStartMonday = page.validateFor(
                        expectedWeek = week,
                        expectedSemesterLabel = semesterPortalLabel(semester),
                        expectedSemesterMonday = semesterStartMonday
                    )
                    skippedRowCount += page.skippedRowCount
                    add(page)
                    onProgress(size, landingPage.availableWeeks.size)
                }
            }
            require(pages.any { it.rows.isNotEmpty() } || courses.isEmpty()) {
                "周次课表未返回课程，已保留现有缓存"
            }
            val mergedCourses = weeklyTimetableParser.mergeWithMetadata(
                baseCourses = courses,
                pages = pages,
                preferredMetadataCourses = preferredMetadataCourses
            )
            require(courses.isEmpty() || mergedCourses.any { it.occurrences.isNotEmpty() }) {
                "周次课表未解析到有效上课时间，已保留现有缓存"
            }
            courses = mergedCourses
            val weeklyAdjustments = scheduleParser.parseAdjustments(landing.body)
            if (weeklyAdjustments.isNotEmpty()) adjustments = weeklyAdjustments
            resolvedTimetableHtml = landing.body
            return@runCatching AcademicSemesterImportPayload(
                courses = courses,
                adjustments = adjustments,
                currcourseHtml = currcourse.body,
                timetableHtml = resolvedTimetableHtml,
                responseKind = responseKind,
                portalMaxWeek = portalMaxWeek,
                semesterStartMonday = semesterStartMonday,
                skippedRowCount = skippedRowCount,
                updatedCookie = sessionCookie,
                importMode = SemesterImportMode.WEEKLY,
                unparsedWeekTextCount = countUnparsedWeekTexts(courses)
            )
        }

        // ===== 模式2（PERSONAL_ONLY）：不逐周下载，时间/教室/教师全部来自个人课表 =====
        // 个人课表在这里就是权威时间来源，必须保留 occurrences（模式1 会先清空再回填）。
        //
        // 个人课表是「只做加法」的：它会把补课时段直接列出来，却**不会**把被调走的那一周从原
        // 课次里去掉；「哪一周被停掉」只写在课程安排页（timetableHtml）的调课表里。
        // 因此桂林也必须用它——只做移除，补课时段经**宽松去重**后追加（个人课表多半已列出，
        // 去重会命中而不重复成两张卡片）。
        //
        // 南宁仍走 applyAdjustmentsToCourses（移除 + 追加，教室宽松匹配），行为不变。
        //
        // 注：历史上曾把「数据库原理及应用B 整门课消失」归因于这里的调课移除，据此让桂林
        // 什么都不做。复盘见 docs/桂林教务HTTPS跳转导致周次课表导入失败问题总结.md：真因是
        // CompositeScheduleParser 把桂林页面交给了不做中午偏移的南宁解析器，导致「第5、6节」
        // 落进中午槽位被隐藏，已由 offsetSectionForNoon 修复，与本移除逻辑无关。
        courses = if (semester.campus == CampusType.NANNING) {
            scheduleParser.applyAdjustmentsToCourses(personalCourses, timetableHtml)
        } else {
            scheduleParser.applyAdjustmentRemovalsOnly(personalCourses, timetableHtml)
        }
        // 模式2 也必须给出学期总周数。这里绝不能留 null：CourseTimeStats 会把 portalMaxWeek
        // 为 null 的学期**整学期**判为不可统计，用户看到的是「这个学期的统计没了」。
        //
        // 三级来源，精度从高到低：
        //   1) 学期自带的起止日期 —— 与刷新路径 academicMaxWeekForCalendar 同一算法。可用即为
        //      权威值，此时**不再发任何请求**（当前学期走模式2 时由 AcademicSemesterCalendarResolver
        //      提供，可能是门户校历或估算）；
        //   2) 周次课表**落地页**的门户周次列表 —— 只 GET 一次、绝不逐周 POST（契约见
        //      probeWeeklyLandingMaxWeek 的注释）。历史学期没有起止日期，这是唯一能拿到门户真实周数的途径；
        //   3) 学期长度估算 —— 与当前学期模式2 的兜底同一份实现。
        // 最后与被反推值取较大者：反推值是「学期至少有这么长」的下界，取大保证不丢任何课次。
        // 缺了第 2、3 级，历史学期就只能反推到「最后一个有课周」，翻不到期末、课时统计也会少算。
        val calendarMaxWeek = semester.semesterStartDate
            ?.let { start ->
                semester.semesterEndDate?.let { end -> academicMaxWeekForCalendar(start, end) }
            }
        val landingMaxWeek = if (calendarMaxWeek == null) {
            probeWeeklyLandingMaxWeek(sessionCookie, baseUrl, semester, ::consumeSessionCookie)
        } else {
            null
        }
        val estimatedMaxWeek = AcademicSemesterCalendarEstimator.estimate(semester, LocalDate.now())
            .let { academicMaxWeekForCalendar(it.startMonday, it.endDate) }
        portalMaxWeek = calendarMaxWeek
            ?: maxOf(
                landingMaxWeek ?: estimatedMaxWeek,
                derivedAcademicMaxWeek(courses) ?: MIN_ACADEMIC_WEEK
            ).coerceIn(MIN_ACADEMIC_WEEK, MAX_ACADEMIC_WEEK)
        AcademicSemesterImportPayload(
            courses = courses,
            adjustments = adjustments,
            currcourseHtml = currcourse.body,
            timetableHtml = resolvedTimetableHtml,
            responseKind = responseKind,
            portalMaxWeek = portalMaxWeek,
            semesterStartMonday = semesterStartMonday,
            skippedRowCount = 0,
            updatedCookie = sessionCookie,
            importMode = SemesterImportMode.PERSONAL_ONLY,
            unparsedWeekTextCount = countUnparsedWeekTexts(courses)
        )
    }

    /**
     * 模式2 的「最佳努力」周次探测：取门户周次课表**落地页**里的可下载周次最大值。
     *
     * **契约（2026-09-21 有意放宽）**：模式2 原先「绝不请求 studentWeeklyTimetable」，现放宽为
     * 「允许发一次落地页 GET、绝不逐周 POST」。理由有两条：
     *   1. 模式1 整体失败的真因是表单 POST→GET 重定向丢参数（见
     *      docs/桂林教务HTTPS跳转导致周次课表导入失败问题总结.md），落地页 GET 本身是安全的；
     *   2. 不拿门户周数就只能反推到「最后一个有课周」，历史学期翻不到期末，课时统计也会少算。
     *
     * 因此这里**任何失败都只返回 null**（网络异常、非 2xx、登录页、结构异常、学期标签不符），
     * 由调用方退化到学期长度估算——导入成功与否绝不受它影响。
     */
    private suspend fun probeWeeklyLandingMaxWeek(
        cookie: String,
        baseUrl: String,
        semester: AcademicSemester,
        onSessionRotated: (ApiProbeService.ProbeResult) -> Unit
    ): Int? = runCatching {
        val landing = apiProbeService.probeUrl(
            cookie,
            AcademicSemesterRequestBuilder.weeklyTimetableUrl(baseUrl, semester)
        ) ?: return@runCatching null
        if (landing.httpCode !in 200..299) return@runCatching null
        // 会话轮换必须吃回去，否则后续请求会掉登录。
        onSessionRotated(landing)
        if (
            AcademicSemesterResponseValidator.classify(landing.body, courseCount = 0) ==
            AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED
        ) {
            return@runCatching null
        }
        weeklyTimetableParser
            .parsePage(landing.body, hasNoon = semester.campus != CampusType.NANNING)
            // 落地页可能停在别的学期；标签不符时它的周次列表不属于本次请求，一律丢弃。
            .takeIf { it.semesterLabel == semesterPortalLabel(semester) }
            ?.availableWeeks
            ?.maxOrNull()
    }.getOrNull()

    private fun semesterPortalLabel(semester: AcademicSemester): String {
        val season = when (semester.season) {
            com.glut.schedule.data.model.SemesterSeason.SPRING -> "春"
            com.glut.schedule.data.model.SemesterSeason.AUTUMN -> "秋"
        }
        return "${semester.portalYear}$season"
    }
}

/**
 * 在解析 HTML 前识别传输层失败，避免把登录页或 POST→GET 重定向误报为表格结构变化。
 */
internal fun validateWeeklyProbeTransport(
    response: ApiProbeService.ProbeResult,
    pageLabel: String
) {
    if (response.redirected && !response.method.equals(response.finalMethod, ignoreCase = true)) {
        error(
            "$pageLabel 请求重定向后由 ${response.method.uppercase()} 变为 " +
                "${response.finalMethod.uppercase()}，表单未正确提交"
        )
    }
    if (
        AcademicSemesterResponseValidator.classify(response.body, courseCount = 0) ==
        AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED
    ) {
        error("登录状态已失效，请重新登录后再导入")
    }
}

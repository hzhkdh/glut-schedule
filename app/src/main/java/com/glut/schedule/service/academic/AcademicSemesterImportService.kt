package com.glut.schedule.service.academic

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.MIN_ACADEMIC_WEEK
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.countUnparsedWeekTexts
import com.glut.schedule.data.model.derivedAcademicMaxWeek
import com.glut.schedule.service.parser.AcademicScheduleParser
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.time.LocalDate

object AcademicSemesterRequestBuilder {
    fun currcourseUrl(baseUrl: String, semester: AcademicSemester): String =
        "${normalizedBaseUrl(baseUrl)}/academic/student/currcourse/currcourse.jsdo" +
            "?year=${encode(semester.portalYearId)}&term=${encode(semester.portalTermId)}"

    /**
     * 大节课表（`sectionType=BASE`）——**唯一**的课程数据源。
     *
     * 它与「小节课表」（`sectionType=COMBINE`）只是行粒度不同：BASE 一行一个小节
     * （并含桂林特有的「中午1/中午2」两行），COMBINE 一行一个大节且两行内容相同。
     * 取 BASE 是因为单元格 id 的节次分量与内部节次号能直接对应。
     */
    fun timetableUrl(baseUrl: String, studentId: String, semester: AcademicSemester): String =
        "${normalizedBaseUrl(baseUrl)}/academic/manager/coursearrange/showTimetable.do" +
            "?id=${encode(studentId)}" +
            "&yearid=${encode(semester.portalYearId)}" +
            "&termid=${encode(semester.portalTermId)}" +
            "&timetableType=STUDENT&sectionType=BASE"

    /**
     * 周次课表**落地页**（GET）。
     *
     * 统一导入路径后周次课表不再是课程数据源，本方法只剩一个用途：读页面上的
     * `select[name=whichWeek]` 得到学期总周数——大节课表本身没有周次下拉。
     *
     * **绝不逐周 POST**：表单 POST 曾被 301 重定向降级为 GET 而整体失败，
     * 详见 docs/桂林教务HTTPS跳转导致周次课表导入失败问题总结.md。
     */
    fun weeklyTimetableUrl(baseUrl: String, semester: AcademicSemester): String =
        "${normalizedBaseUrl(baseUrl)}/academic/manager/coursearrange/studentWeeklyTimetable.do" +
            "?yearid=${encode(semester.portalYearId)}&termid=${encode(semester.portalTermId)}"

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
    /**
     * 无法识别周次的课次数量。这类课次不会出现在任何一周里，必须如实反馈给用户，
     * 否则表现就是「课程莫名其妙少了几门」，无从排查。
     */
    val unparsedWeekTextCount: Int = 0,
    val unscheduledCourseCount: Int = 0
)

/**
 * 周次课表**落地页**的最小解析结果。
 *
 * 只保留两项：[semesterLabel] 用于判断落地页是否停在别的学期，[availableWeeks]
 * 用于取学期总周数。周次课表本身已不再是课程数据源，因此不保留整套行解析。
 */
internal data class WeeklyLandingSummary(
    val semesterLabel: String,
    val availableWeeks: List<Int>,
    val selectedWeek: Int
)

internal object WeeklyLandingPageParser {
    /** 与门户「2026秋」标签同格式：门户年份 + 中文季节字。 */
    private val semesterLabelRegex = Regex("""(20\d{2})\s*([春秋])""")

    fun parse(html: String): WeeklyLandingSummary {
        val document = Jsoup.parse(html)
        val semesterLabel = semesterLabelRegex.find(document.body().text())
            ?.let { "${it.groupValues[1]}${it.groupValues[2]}" }
            .orEmpty()
        val availableWeeks = document.selectFirst("select[name=whichWeek]")
            ?.select("option")
            ?.mapNotNull { it.attr("value").trim().toIntOrNull() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        val selectedWeek = document.selectFirst("select[name=whichWeek] option[selected]")
            ?.attr("value")
            ?.trim()
            ?.toIntOrNull()
            ?: 0
        return WeeklyLandingSummary(semesterLabel, availableWeeks, selectedWeek)
    }
}

/**
 * 学期课表导入：**统一只抓一次大节课表**。
 *
 * 为什么一个端点就够（2026-09-23 双端教务实测结论）：
 *  1. 网格单元格形如 `<<课程名>>;课序号 ⏎ 教室 ⏎ 教师 ⏎ 周次 ⏎ 学时类型`，
 *     **教师与教室同格**——个人课表那种「教师与时间分列两格、必须靠绑定器猜」的
 *     前提不复存在，`CourseTeacherBinder` 随之作废；
 *  2. 页面**底部自带调课表**（类型/原时段/补课时段），无需再从周次课表取调课；
 *  3. 节次跨度由相邻行重复表达，`mergeAdjacentOccurrences` 已能合并成区间。
 *
 * 于是旧的「模式1 逐周 POST + 周次课表」与「模式2 个人课表 + 大节课表补教师」
 * 两条链路整体删除，导入请求数从最多 20 次降到 2 次。
 *
 * 仍然保留的那次个人课表**请求**只做两件事：校验学期、从链接里取内部学号
 * （`showTimetable.do?id=...` 的 id）。**课程不再从该页解析。** 南宁的周次课表
 * 实测不返回任何排课行，因此这次请求对南宁同样必需。
 *
 * 唯一缺口是学期总周数（大节课表没有 `whichWeek` 下拉），由
 * [probeWeeklyLandingMaxWeek] 发一次落地页 GET 补齐——**绝不逐周 POST**。
 */
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
        var sessionCookie = cookie
        fun consumeSessionCookie(response: ApiProbeService.ProbeResult) {
            if (response.updatedCookie.isNotBlank()) sessionCookie = response.updatedCookie
        }

        // ── 1. 个人课表页：只用来取内部学号与校验学期，课程不从这一页解析 ──
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

        val studentId = ApiProbeService.extractInternalIdFromCurrcourse(currcourse.body)
            .orEmpty().ifBlank { studentIdFallback }
        require(studentId.isNotBlank()) {
            "无法从教务页面解析学号，下载${semester.displayName}课表失败"
        }

        // ── 2. 大节课表：唯一的数据源 ──
        val timetable = apiProbeService.probeUrl(
            sessionCookie,
            AcademicSemesterRequestBuilder.timetableUrl(baseUrl, studentId, semester)
        ) ?: error("无法连接教务服务器，下载${semester.displayName}课表失败")
        require(timetable.httpCode in 200..299) {
            "教务系统返回 HTTP ${timetable.httpCode}，下载${semester.displayName}课表失败"
        }
        consumeSessionCookie(timetable)
        val timetableHtml = timetable.body
        if (AcademicSemesterResponseValidator.classify(timetableHtml, courseCount = 0) ==
            AcademicSemesterResponseKind.AUTHENTICATION_EXPIRED
        ) {
            error("登录状态已失效，请重新登录后再导入")
        }

        // ── 3. 解析：网格 + 底部调课表一次做完 ──
        // parsePersonalSchedule 内部已按调课表移除被调走的周次，并把补课时段去重追加，
        // 因此这里不需要任何后续的绑定/调整步骤。
        val courses = scheduleParser.parsePersonalSchedule(timetableHtml)
        val responseKind = AcademicSemesterResponseValidator.classify(timetableHtml, courses.size)
        require(responseKind != AcademicSemesterResponseKind.INVALID_STRUCTURE) {
            "无法识别课表结构，未覆盖已有缓存"
        }
        val adjustments = scheduleParser.parseAdjustments(timetableHtml)

        // ── 4. 学期总周数 ──
        // 这里绝不能留 null：CourseTimeStats 会把 portalMaxWeek 为 null 的学期**整学期**
        // 判为不可统计，用户看到的是「这个学期的统计没了」。
        //
        // 三级来源，精度从高到低：
        //   1) 学期自带的起止日期 —— 与刷新路径 academicMaxWeekForCalendar 同一算法。
        //      可用即为权威值，此时**不再发任何请求**；
        //   2) 周次课表**落地页**的门户周次列表 —— 只 GET 一次、绝不逐周 POST
        //      （契约见 probeWeeklyLandingMaxWeek 的注释）。历史学期没有起止日期，
        //      这是唯一能拿到门户真实周数的途径；
        //   3) 学期长度估算 —— 兜底，同一份实现也服务于日历解析。
        // 最后与被反推值取较大者：反推值是「学期至少有这么长」的下界，取大保证不丢任何课次。
        // 缺了第 2、3 级，历史学期就只能反推到「最后一个有课周」，翻不到期末、课时统计也会少算。
        val calendarMaxWeek = semester.semesterStartDate
            ?.let { start ->
                semester.semesterEndDate?.let { end -> academicMaxWeekForCalendar(start, end) }
            }
        val landingMetadata = if (calendarMaxWeek == null) {
            probeWeeklyLandingMetadata(sessionCookie, baseUrl, semester, ::consumeSessionCookie)
        } else {
            null
        }
        val estimatedMaxWeek = AcademicSemesterCalendarEstimator.estimate(semester, LocalDate.now())
            .let { academicMaxWeekForCalendar(it.startMonday, it.endDate) }
        val portalMaxWeek = calendarMaxWeek
            ?: maxOf(
                landingMetadata?.maxWeek ?: estimatedMaxWeek,
                derivedAcademicMaxWeek(courses) ?: MIN_ACADEMIC_WEEK
            ).coerceIn(MIN_ACADEMIC_WEEK, MAX_ACADEMIC_WEEK)

        AcademicSemesterImportPayload(
            courses = courses,
            adjustments = adjustments,
            currcourseHtml = currcourse.body,
            timetableHtml = timetableHtml,
            responseKind = responseKind,
            portalMaxWeek = portalMaxWeek,
            semesterStartMonday = landingMetadata?.startMonday,
            skippedRowCount = 0,
            updatedCookie = sessionCookie,
            unparsedWeekTextCount = countUnparsedWeekTexts(courses),
            unscheduledCourseCount = scheduleParser.countUnscheduledCourses(timetableHtml)
        )
    }

    /**
     * 「最佳努力」的周次探测：取门户周次课表**落地页**里的可下载周次最大值。
     *
     * **契约**：允许发一次落地页 GET、**绝不逐周 POST**。理由有两条：
     *   1. 逐周 POST 曾因 301 转发把表单降级为 GET 而整体失败（见
     *      docs/桂林教务HTTPS跳转导致周次课表导入失败问题总结.md），落地页 GET 本身是安全的；
     *   2. 不拿门户周数就只能反推到「最后一个有课周」，历史学期翻不到期末，课时统计也会少算。
     *
     * 因此这里**任何失败都只返回 null**（网络异常、非 2xx、登录页、结构异常、学期标签不符），
     * 由调用方退化到学期长度估算——导入成功与否绝不受它影响。
     */
    private data class WeeklyLandingMetadata(
        val maxWeek: Int?,
        val startMonday: LocalDate?
    )

    private suspend fun probeWeeklyLandingMetadata(
        cookie: String,
        baseUrl: String,
        semester: AcademicSemester,
        onSessionRotated: (ApiProbeService.ProbeResult) -> Unit
    ): WeeklyLandingMetadata? = runCatching {
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
        val summary = WeeklyLandingPageParser.parse(landing.body)
            // 落地页可能停在别的学期；标签不符时它的周次列表不属于本次请求，一律丢弃。
            .takeIf { it.semesterLabel == semesterPortalLabel(semester) }
            ?: return@runCatching null
        val maxWeek = summary.availableWeeks.maxOrNull()
        val startMonday = if (
            semester.isCurrent &&
            maxWeek != null &&
            summary.selectedWeek in 1..maxWeek &&
            landing.serverDate != null
        ) {
            val currentMonday = landing.serverDate.minusDays((landing.serverDate.dayOfWeek.value - 1).toLong())
            currentMonday.minusWeeks((summary.selectedWeek - 1).toLong())
        } else {
            null
        }
        WeeklyLandingMetadata(maxWeek, startMonday)
    }.getOrNull()

    private fun semesterPortalLabel(semester: AcademicSemester): String {
        val season = when (semester.season) {
            com.glut.schedule.data.model.SemesterSeason.SPRING -> "春"
            com.glut.schedule.data.model.SemesterSeason.AUTUMN -> "秋"
        }
        return "${semester.portalYear}$season"
    }
}

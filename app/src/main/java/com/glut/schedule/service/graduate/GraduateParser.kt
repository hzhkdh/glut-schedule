package com.glut.schedule.service.graduate

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseStatus
import com.glut.schedule.data.model.GRADUATE_SECTION_COUNT
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.STUDY_PLAN_READ_ONLY_ATTRIBUTE
import com.glut.schedule.data.model.academicWeeksForText
import com.glut.schedule.data.settings.CampusType
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 研究生课表的一次解析结果。
 *
 * [adjustmentWarning] 非空时只作提示，绝不修改主课表。
 * [skippedCount] 是无法识别节次/周次而被跳过的课程条数——必须带回界面如实告知，
 * 否则表现就是「课程莫名其妙少了几门」，无从排查。
 */
data class GraduateScheduleParseResult(
    val courses: List<ScheduleCourse>,
    val adjustmentWarning: String = "",
    val skippedCount: Int = 0
)

/**
 * 研究生调课记录的一次解析结果。
 *
 * [warningCount] 是**认不出**的行数：缺课程名、或两侧时间串读不懂。必须如实回传，
 * 否则表现就是「调课记录少了几条」而用户无从知道少了什么。
 */
data class GraduateAdjustmentParseResult(
    val adjustments: List<SemesterAdjustment> = emptyList(),
    val warningCount: Int = 0
)

/**
 * 研究生门户的独立解析器。
 *
 * 与本科/南宁的 HTML 解析器没有任何共享：门户用 JSON + 单元格内嵌多行文本，
 * 节次、周次、地点写在课程块自己的「节次:」「周次:」「地点:」标签里。
 * **关键差异**：研究生一天 12 节且没有中午节次，因此这里的节次一律**不做偏移**，
 * 写进课次的编号就是门户原始编号。
 */
object GraduateParser {

    /** 培养计划的只读属性标签，用于与本科「必修/选修」等口径区分。 */
    const val GRADUATE_PLAN_ATTRIBUTE = STUDY_PLAN_READ_ONLY_ATTRIBUTE

    private val DAY_FIELDS = listOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    )

    /** 节次/周次/地点等标签行：`<名字>:<值>` 或 `<名字>：<值>`。 */
    private val labelPattern = Regex("""^[^:：]+[:：]""")

    /** 周次标签：只有出现过它，后面的非标签行才可能是下一门课的开头。 */
    private val weekLabelPattern = Regex("""^周次\s*[:：]""")

    /**
     * 解析课表。
     *
     * 支持：单元格内多门课、跨大节的同一门课（同一门课分别出现在「3,4」「5,6」两行，
     * 但标签里写的是 `节次:3,4,5,6`）、离散周次与单双周、以及缺失的教师/地点字段。
     * **只合并完全一致的重复课次**（课程、星期、节次区间、周次文本全同），
     * 不同时段或不同周次一律保留为独立课次。
     *
     * 单条记录识别不了节次/周次时**跳过该条并计数**，绝不当成「全周都有课」——
     * 一个坏单元格不该让首次导入的用户拿到一个完全空白的 App。
     * 只有当**一条都解析不出来**时才中止（见 [scheduleWithDiagnostics]）。
     */
    fun schedule(raw: String): List<ScheduleCourse> = scheduleWithDiagnostics(raw).courses

    fun scheduleWithDiagnostics(raw: String): GraduateScheduleParseResult {
        val rows = rowArray(raw, "课表")
        val result = linkedMapOf<String, ScheduleCourse>()
        var skipped = 0
        var sawAnyBlock = false
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val rowSections = parseSections(row.optString("JieCi"))
            DAY_FIELDS.forEachIndexed { dayIndex, field ->
                val cell = row.optString(field).takeUnless { it == "null" }.orEmpty()
                    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
                    .replace("\r", "")
                if (cell.isBlank()) return@forEachIndexed
                splitCellRecords(cell).forEach { lines ->
                    sawAnyBlock = true
                    // 只吞「这条记录读不懂」（require 抛的 IllegalArgumentException），
                    // 其它异常照旧上抛，不会被这里伪装成「跳过了一条」。
                    val course = try {
                        parseBlock(lines, dayIndex + 1, rowSections)
                    } catch (_: IllegalArgumentException) {
                        skipped++
                        return@forEach
                    }
                    val previous = result[course.id]
                    result[course.id] = if (previous == null) {
                        course
                    } else {
                        // 同一门课可能分布在多个大节行或多个星期，必须累加而不是覆盖。
                        previous.copy(
                            occurrences = (previous.occurrences + course.occurrences)
                                .distinctBy { it.id }
                        )
                    }
                }
            }
        }
        // 门户确实给了课程块，却一条都读不出来 ⇒ 这不是「本学期没课」，而是格式变了，
        // 必须中止而不是写一张空课表进去覆盖缓存。
        if (sawAnyBlock && result.isEmpty()) {
            error("研究生课表全部 $skipped 条记录都无法识别，请检查门户格式或稍后重试")
        }
        return GraduateScheduleParseResult(result.values.toList(), skippedCount = skipped)
    }

    /**
     * 把一格文本切成若干门课。
     *
     * **不能只按空行切**：门户既有「课程名 / 教师 / 节次: … / 周次: …」这种规整写法，
     * 也有「课程名」独占一行、空行之后才跟节次/周次的写法（合成夹具里的晚间课程就是这种）。
     * 只按空行切会把后者拆成「只有标题」和「只有标签」两条废记录，整门课丢失。
     *
     * 采用**按行扫描**：出现过「周次」标签之后，再遇到非标签行就说明下一门课开始了。
     * 这条规则同时也覆盖了「两门课之间根本没有空行」的写法——那种情况下按空行切会把
     * 第二门课整门并进第一门（标签取值只取第一次出现），静默丢课。
     *
     * 与小程序端 `utils/graduateParser.js#splitCellRecords` **同一条规则**：
     * 两端必须对同一份合成夹具给出相同结果。
     */
    private fun splitCellRecords(cell: String): List<List<String>> {
        val records = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var seenWeekLabel = false
        cell.split('\n').forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            val isLabel = labelPattern.containsMatchIn(line)
            if (seenWeekLabel && !isLabel) {
                if (current.isNotEmpty()) records.add(current)
                current = mutableListOf()
                seenWeekLabel = false
            }
            current.add(line)
            if (weekLabelPattern.containsMatchIn(line)) seenWeekLabel = true
        }
        if (current.isNotEmpty()) records.add(current)
        return records
    }

    private fun parseBlock(lines: List<String>, dayOfWeek: Int, rowSections: List<Int>): ScheduleCourse {
        require(lines.isNotEmpty()) { "研究生课表存在空白课程块，请检查门户课表" }
        // 不含冒号的行才是内容行，带冒号的是标签。教师既可能单占一行，
        // 也可能写成 `教师: 张三`；两种都认，取值口径与小程序端一致。
        val plainLines = lines.filterNot { it.contains(':') || it.contains('：') }
        val title = plainLines.firstOrNull().orEmpty()
        require(title.isNotEmpty()) { "研究生课表存在无法识别的课程名，请检查门户课表" }
        val teacher = fieldValue(lines, "教师")
            .ifEmpty { fieldValue(lines, "任课教师") }
            .ifEmpty { plainLines.getOrNull(1).orEmpty() }
        val room = fieldValue(lines, "地点")

        // 节次标签「在」与「能读懂」是两件事：标签缺失时可以退回该大节行的 `JieCi`
        // （它同样是门户给出的真实节次，不是「猜」）；但标签存在却读不懂（例如 `节次:待定`）
        // 必须报错——静默退回行值会把「门户改了格式」伪装成解析成功。
        val sectionsLabel = fieldValue(lines, "节次")
        val sections = if (sectionsLabel.isBlank()) rowSections else parseSections(sectionsLabel)
        require(sections.isNotEmpty() && sections.all { it in 1..GRADUATE_SECTION_COUNT }) {
            "研究生课表节次无法识别（$title），请检查门户课表"
        }
        val weeks = normalizeWeeks(fieldValue(lines, "周次"))
        require(weeks.isNotBlank() && academicWeeksForText(weeks).isNotEmpty()) {
            "研究生课表周次无法识别（$title），请检查门户课表"
        }

        val courseId = GraduateProtocol.md5("$title|$teacher|$room")
        // 非连续节次拆成独立区间，避免把中间没课的时段也补进去。
        val runs = mutableListOf<MutableList<Int>>()
        sections.forEach { section ->
            if (runs.lastOrNull()?.last()?.plus(1) == section) runs.last().add(section)
            else runs.add(mutableListOf(section))
        }
        val occurrences = runs.map { run ->
            CourseOccurrence(
                id = GraduateProtocol.md5("$courseId|$dayOfWeek|$run|$weeks"),
                courseId = courseId,
                dayOfWeek = dayOfWeek,
                startSection = run.first(),
                endSection = run.last(),
                weekText = weeks,
                note = ""
            )
        }
        return ScheduleCourse(
            id = courseId,
            title = title,
            room = room,
            teacher = teacher,
            colorHex = "",
            occurrences = occurrences
        )
    }

    /** `节次:5,6` / `节次：3-4` / `第1,2节` 等写法都归一到节次列表。 */
    internal fun parseSections(raw: String): List<Int> {
        val cleaned = raw.replace("第", "").replace("节", "").trim()
        if (cleaned.isEmpty()) return emptyList()
        return cleaned.split(Regex("[,，、;；/\\s]+"))
            .filter(String::isNotBlank)
            .flatMap { token ->
                val parts = token.split('-', '－', '—')
                if (parts.size == 2) {
                    val start = parts[0].toIntOrNull()
                    val end = parts[1].toIntOrNull()
                    if (start != null && end != null && start <= end) {
                        (start..end).toList()
                    } else {
                        emptyList()
                    }
                } else {
                    listOfNotNull(token.toIntOrNull())
                }
            }
            .distinct()
            .sorted()
    }

    /** 任意成对括号（半角/全角）。 */
    private val parenthesizedGroup = Regex("""[（(]([^（()）]*)[）)]""")

    /**
     * 周次归一化。
     *
     * 真实门户的周次字段会把**任课教师**写在括号里：
     * `4-6(黄发国);7-11(潘家芳)`、`9-9(陈良骥)`。括号对周次没有语义，必须整块剥掉——
     * 不剥的后果不是少算几周，而是正则整体失配，**这条课程会被当成异常整条丢弃**。
     *
     * 但 `(单)` / `(双)` 是语义，必须保留。这里保留其内容、只去掉括号本身：
     * 课时统计的周次校验不接受括号（见 `CourseTimeStats.validWeekCharacters`），
     * 留着括号会让整学期统计不可用。于是 `10-14(双)` 归一为 `10-14双`、`4-6(黄发国)` 归一为 `4-6`。
     *
     * 其余归一：全角分隔符统一成半角，去掉空白。`待定` 这类文本原样保留，
     * 由调用方判为「读不懂」→ 跳过该条并计数。
     */
    internal fun normalizeWeeks(raw: String): String = parenthesizedGroup
        .replace(raw) { match ->
            val inner = match.groupValues[1].trim()
            // 必须**恰好**是「单」或「双」才算周次语义。
            //
            // 用 contains 会把教师名误判成语义：单是真实存在的姓氏，`5-8(单强)` 会被保留成
            // `5-8单强`，于是周次被静默按单周算成 [5,7]；同时课时统计的严格周次校验因为
            // 「强」不是合法字符而把该课次整条丢弃。真实抓包里的括号内容全是教师名
            // （黄发国 / 潘家芳 / 陈良骥），而语义写法是 `10-14(双)` 这种，恰好一词。
            // 小程序端同一函数用的是等价规则，两端保持一致。
            if (inner == "单" || inner == "双") inner else ""
        }
        .replace(';', ',')
        .replace('；', ',')
        .replace('－', '-')
        .replace('—', '-')
        .replace(Regex("\\s+"), "")
        .trim()

    private fun fieldValue(lines: List<String>, name: String): String =
        lines.firstOrNull { it.startsWith(name) }?.let(::valueAfterColon).orEmpty()

    private fun valueAfterColon(line: String): String {
        val index = line.indexOfFirst { it == ':' || it == '：' }
        return if (index < 0) "" else line.substring(index + 1).trim()
    }

    // ---- 学期目录 ----

    /**
     * 由门户学年/学期构造学期目录。
     *
     * 缓存键完全按门户语义：第一学期是**学年起始年**的秋季，第二学期是**次年**的春季。
     * 即 2026-2027 第一学期 → `graduate:2026:autumn`，第二学期 → `graduate:2027:spring`。
     * 这里绝不套用本科（学期号 1/2）或南宁（学期号 3）的编号规则。
     *
     * 历史学期只保留门户原始学年/学期参数，**不臆造开学日期**；只有当前学期使用门户校历。
     */
    fun catalog(
        year: String,
        term: String,
        grade: String,
        currentStartMonday: LocalDate?
    ): List<AcademicSemester> {
        val start = year.substringBefore('-').toIntOrNull()
        require(start != null && start in 2000..2100 && term in setOf("1", "2")) {
            "研究生门户学期信息无效，请重新登录后重试"
        }
        val currentYear = start + if (term == "2") 1 else 0
        val currentSeason = if (term == "2") SemesterSeason.SPRING else SemesterSeason.AUTUMN
        val gradeYear = grade.toIntOrNull()
        val earliest = if (gradeYear != null && gradeYear in (start - 3)..start) gradeYear else start - 3

        val semesters = mutableListOf<AcademicSemester>()
        // 由近及远：同一学年内先第二学期（春季）再第一学期（秋季）。
        // **上界是 start 而不是 start+1**：门户当前学年就是最新的一年，
        // 从 start+1 起会凭空多出两个未来学期（2027-2028），它们既不存在于门户，
        // 又会被当成「历史学期」渲染出来，点下载必然失败。
        for (portalYearStart in start downTo earliest) {
            for (portalTerm in listOf("2", "1")) {
                val season = if (portalTerm == "1") SemesterSeason.AUTUMN else SemesterSeason.SPRING
                val naturalYear = if (portalTerm == "2") portalYearStart + 1 else portalYearStart
                val isCurrent = naturalYear == currentYear && season == currentSeason
                semesters += AcademicSemester.create(
                    campus = CampusType.GRADUATE,
                    portalYear = naturalYear,
                    portalYearId = "$portalYearStart-${portalYearStart + 1}",
                    season = season,
                    portalTermId = portalTerm,
                    isCurrent = isCurrent,
                    semesterStartDate = currentStartMonday.takeIf { isCurrent }
                )
            }
        }
        return semesters
    }

    /** 门户 `/Date(<毫秒>±HHMM)/` → 中国时区日期；无法识别时返回 null，绝不臆造。 */
    fun portalDate(value: String): LocalDate? = Regex("""/Date\((\d{10,})""").find(value)
        ?.groupValues?.get(1)?.toLongOrNull()
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate() }

    // ---- 成绩与培养计划 ----

    /** 研究生成绩：只有真实分数与学分，**门户不给绩点**，因此 gpa 恒为 0 且界面不得推算。 */
    fun scores(raw: String): List<ScoreInfo> {
        val list = rowArray(raw, "成绩")
        return (0 until list.length()).mapNotNull { index ->
            val row = list.optJSONObject(index) ?: return@mapNotNull null
            val name = row.optString("CourseName").trim()
            if (name.isEmpty()) return@mapNotNull null
            // 门户的 Year 是「2026-2027」这种学年串，而成绩页按「学年起始年」分组，
            // 因此统一取前四位；已经是纯年份时原样保留。
            val year = normalizeScoreYear(row.optString("Year"))
            val term = row.optString("Term").trim().toIntOrNull() ?: 0
            val score = row.optString("TotalMark").trim().ifBlank { row.optString("Score1").trim() }
            ScoreInfo(
                id = ScoreInfo.stableId(name, year, term),
                courseName = name,
                score = score,
                gpa = 0.0,
                credit = row.optString("Credit").trim().toDoubleOrNull() ?: 0.0,
                year = year,
                term = term,
                category = row.optString("CourseTypeName").trim(),
                examType = ""
            )
        }
    }

    /** `2026-2027` / `2026` → 学年起始年 `2026`；无法识别时原样返回。 */
    internal fun normalizeScoreYear(raw: String): String {
        val trimmed = raw.trim()
        return Regex("""^(\d{4})""").find(trimmed)?.groupValues?.get(1) ?: trimmed
    }

    /**
     * 培养计划：只产出**只读课程列表**。
     *
     * 刻意不做任何「是否达标」判断——门户不给培养方案的学分要求，凭空推算毕业达标
     * 只会给出错误结论；也不提供选退课入口。
     */
    fun studyPlan(raw: String): Pair<List<StudyPlanGroup>, List<StudyPlanCourse>> {
        val rows = rowArray(raw, "培养计划")
        val groups = linkedMapOf<String, StudyPlanGroup>()
        val courses = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val name = row.optString("CourseName").trim()
            if (name.isEmpty()) return@mapNotNull null
            val category = row.optString("CourseTypeName").trim().ifBlank { "培养计划课程" }
            val groupId = StudyPlanGroup.stableId(category, GRADUATE_PLAN_ATTRIBUTE)
            groups[groupId] = StudyPlanGroup(
                id = groupId,
                groupName = category,
                attribute = GRADUATE_PLAN_ATTRIBUTE,
                creditRequired = 0.0,
                creditEarned = 0.0,
                countRequired = 0,
                countPassed = 0,
                isPassed = false
            )
            val year = row.optString("Year").trim()
            val term = row.optString("Term").trim()
            StudyPlanCourse(
                id = StudyPlanCourse.stableId(groupId, name + row.optString("CourseID").trim()),
                groupId = groupId,
                courseName = name,
                credit = row.optString("Credit").trim().toDoubleOrNull() ?: 0.0,
                hours = row.optString("StudyHour").trim(),
                assessment = row.optString("TestSty").trim(),
                semester = listOf(year, term.takeIf { it.isNotBlank() }?.let { "第${it}学期" }.orEmpty())
                    .filter(String::isNotBlank)
                    .joinToString(" "),
                status = CourseStatus.UNKNOWN
            )
        }
        return groups.values.toList() to courses
    }

    // ---- 调课记录 ----

    /** 门户 `GetCheckedAdjustCourseList` 目前只给「原时段 → 目标时段」这一类。 */
    const val ADJUSTMENT_TYPE_ADJUSTED = "调课"

    /** 教学周的安全上界；与课表解析同一口径。 */
    private const val MAX_ADJUSTMENT_WEEK = 30

    private val CHINESE_DAY_INDEX = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7
    )

    /**
     * 门户把两侧时间各塞进**一个中文字符串**，如 `6-6周 【星期二】3,4节 `。
     *
     * 与本科的列式调课表完全不同（那边是「星期 / 节次 / 周次 / 教室」四列），所以只能自己拆。
     * 周次是闭区间；节次两种写法都认（`3,4节` 与 `3-4节`）。
     */
    private val adjustmentTimePattern = Regex(
        """^(\d{1,2})(?:\s*[-~－—]\s*(\d{1,2}))?\s*周\s*[【\[]?\s*星期\s*([一二三四五六日天])\s*[】\]]?\s*第?\s*(\d{1,2})(?:\s*(?:[,，]|[-~－—])\s*(\d{1,2}))?\s*节"""
    )

    /**
     * 拆一个时间串，返回 `[首周, 末周, 星期, 起始节, 结束节]`。
     *
     * 读不懂返回 null —— 由调用方计入告警，**不要在这里猜一个默认值**：猜出来的周次会直接
     * 进课时统计，错一周就是凭空多算或少算一次课。
     */
    private fun parseAdjustmentTime(value: String): IntArray? {
        val match = adjustmentTimePattern.find(value.trim()) ?: return null
        val firstWeek = match.groupValues[1].toInt()
        val lastWeek = match.groupValues[2].ifBlank { match.groupValues[1] }.toInt()
        val day = CHINESE_DAY_INDEX[match.groupValues[3].first()] ?: return null
        val startSection = match.groupValues[4].toInt()
        val endSection = match.groupValues[5].ifBlank { match.groupValues[4] }.toInt()
        if (firstWeek < 1 || lastWeek < firstWeek || lastWeek > MAX_ADJUSTMENT_WEEK) return null
        if (day < 1 || startSection < 1 || endSection < startSection) return null
        if (endSection > GRADUATE_SECTION_COUNT) return null
        return intArrayOf(firstWeek, lastWeek, day, startSection, endSection)
    }

    /**
     * 解析调课记录 → 本地 `SemesterAdjustment`。
     *
     * **只落记录，不碰课表**：门户的 `GetTimeTableByStudent` 已经把调课落实在目标时段了——
     * 生成一条课程名带 `[调课]` 前缀的块，并把原时段那一周从周次里摘掉（2026-10-15 真实账号确认）。
     * 再走一遍本科的 makeup 建卡链路会多出一张重复卡：那一步的去重靠标题相等，`[调课]` 前缀过不了。
     *
     * **不认「停课」**：停课在课时统计里要扣掉那一周的课时，把「缺目标时段」的记录猜成停课
     * 就是凭空少算。现有样本 4 条全是「原时段 → 目标时段」，没有任何停课/代课形态，
     * 所以认不出的行只计入 [GraduateAdjustmentParseResult.warningCount]，让用户去门户核对。
     */
    fun adjustments(raw: String): GraduateAdjustmentParseResult {
        val rows = rowsOf(raw) ?: return GraduateAdjustmentParseResult()
        val adjustments = mutableListOf<SemesterAdjustment>()
        var warningCount = 0
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index)
            val title = row?.optString("CourseName").orEmpty().trim()
            val original = parseAdjustmentTime(row?.optString("PreClassTime").orEmpty())
            val makeup = parseAdjustmentTime(row?.optString("ToClassTime").orEmpty())
            if (row == null || title.isEmpty() || original == null || makeup == null) {
                warningCount++
                continue
            }
            val originalSpan = original[1] - original[0]
            val makeupSpan = makeup[1] - makeup[0]
            // 两侧都是单周（现有样本全是），或两侧是**等长**区间时，逐周一一对应展开：
            // SemesterAdjustment 一条只承载单周，揉成一条会把中间几周漏掉、周次判定也会错位。
            // 区间不等长是个没有样本的形态，配错周比不配更糟，如实计入告警。
            if (originalSpan != makeupSpan) {
                warningCount++
                continue
            }
            val teacher = row.optString("TeacherName").trim()
            val originalRoom = row.optString("PreClassRoomName").trim()
            val makeupRoom = row.optString("ToClassRoomName").trim()
            for (offset in 0..originalSpan) {
                val originalWeek = original[0] + offset
                val makeupWeek = makeup[0] + offset
                adjustments += SemesterAdjustment(
                    // 与本科 `stableId` 同一口径：按两侧时段取摘要，刷新重放时 id 稳定，
                    // 同一条调课不会在本地越攒越多。
                    id = GraduateProtocol.md5(
                        listOf(
                            "adj", ADJUSTMENT_TYPE_ADJUSTED, title, teacher,
                            originalWeek, original[2], original[3], original[4], originalRoom,
                            makeupWeek, makeup[2], makeup[3], makeup[4], makeupRoom
                        ).joinToString("|")
                    ).take(12),
                    type = ADJUSTMENT_TYPE_ADJUSTED,
                    title = title,
                    teacher = teacher,
                    originalWeek = originalWeek,
                    originalDay = original[2],
                    originalStartSection = original[3],
                    originalEndSection = original[4],
                    originalRoom = originalRoom,
                    makeupWeek = makeupWeek,
                    makeupDay = makeup[2],
                    makeupStartSection = makeup[3],
                    makeupEndSection = makeup[4],
                    makeupRoom = makeupRoom
                )
            }
        }
        return GraduateAdjustmentParseResult(adjustments, warningCount)
    }

    /**
     * 取行数组，**无法识别时返回 null 而不是抛错**。
     *
     * 门户对这些接口有四种外层包装，全部要认：
     * 1. 裸数组 `[…]`——`GetTimeTableByStudent` 实测就是这种（响应体以 `[{` 开头）；
     * 2. `{items:[…]}`；
     * 3. `{data:{items:[…]}}`；
     * 4. `{data:[…]}`。
     *
     * 只认前三种里的一种、或对裸数组直接 `JSONObject(raw)`，就会把「接口返回了数据」
     * 误判成「无法识别的数据」——课表导入正是这么在真机上第一步就失败的。
     * 需要「不认识就报错」的调用方用 [rowArray]。
     */
    internal fun rowsOf(raw: String): JSONArray? {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return runCatching { JSONArray(trimmed) }.getOrNull()
        }
        val root = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return root.optJSONArray("items")
            ?: root.optJSONObject("data")?.optJSONArray("items")
            ?: root.optJSONArray("data")
    }

    /**
     * 取行数组，无法识别时明确报错。
     *
     * 静默返回空列表会把「接口变了」显示成「这个学期没课」，所以这里必须抛。
     */
    private fun rowArray(raw: String, what: String): JSONArray =
        rowsOf(raw) ?: error("研究生${what}数据格式无法识别")
}

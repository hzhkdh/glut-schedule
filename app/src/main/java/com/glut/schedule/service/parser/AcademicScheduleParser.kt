package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.normalizeRoomKey
import com.glut.schedule.data.model.offsetSectionForNoon
import com.glut.schedule.data.model.weekTextWithoutWeek
import java.security.MessageDigest
import java.text.Normalizer
import org.jsoup.Jsoup

interface AcademicScheduleParser {
    /**
     * 解析课表页为课程列表。**统一导入路径后只对「大节课表」调用**：
     * 网格、底部调课表、补课时段的移除与追加都由这一次解析完成，
     * 调用方拿到的就是可直接落库的结果。
     */
    fun parsePersonalSchedule(html: String): List<ScheduleCourse>

    fun parseAdjustments(html: String): List<SemesterAdjustment> = emptyList()

    fun countUnscheduledCourses(html: String): Int = 0
}

/**
 * 调课记录的教师与该课次教师是否指同一位老师。
 *
 * 默认是**精确相等**；[tolerant] 打开后按空白切词、任一 token 相同即算命中。
 *
 * 为什么要宽容版：一门课由多位老师分担时，课程侧的教师可能是「蒋志军 陈守学 康燕萍」
 * 这样的拼接串（个人课表把多个链接写在同一格），而调课表里只写实际被调走那节课的老师
 * （如「陈守学」）。精确相等会让这类调课**永远匹配不上**，被调走的原周次留在卡上。
 *
 * 宽容匹配是精确相等的**严格超集**（只会多命中、绝不少命中），而多命中的前提是
 * 课程名 + 星期 + 节次 + 原教室 + 周次全部一致——那本来就是同一节课，误删概率可忽略。
 */
internal fun teacherMatches(adjustmentTeacher: String, courseTeacher: String, tolerant: Boolean): Boolean {
    val left = adjustmentTeacher.trim()
    val right = courseTeacher.trim()
    if (left == right) return true
    if (!tolerant || left.isEmpty() || right.isEmpty()) return false
    val rightTokens = right.split(Regex("""\s+""")).filter { it.isNotEmpty() }.toSet()
    return left.split(Regex("""\s+""")).any { it.isNotEmpty() && it in rightTokens }
}

/**
 * 解析个人课表里的显示节次范围，并统一映射为内部节次。
 *
 * 门户除“第5、6节”外还会返回“中午”“中午1-第8节”“第1节-中午2”。
 * 两条个人课表解析路径必须共享这一规则，否则解析路由变化会让课程静默消失。
 */
internal fun parseDisplaySectionRange(value: String, hasNoon: Boolean): Pair<Int, Int>? {
    val text = value.replace(Regex("""\s+"""), "")
    if (text == "中午") return if (hasNoon) 5 to 6 else null

    val tokens = Regex("""中午[12]|第?\d{1,2}节?""").findAll(text).map { it.value }.toList()
    if (tokens.isEmpty() || tokens.size > 2) return null
    fun mapEndpoint(token: String): Int? = when (token) {
        "中午1" -> 5.takeIf { hasNoon }
        "中午2" -> 6.takeIf { hasNoon }
        else -> Regex("""\d{1,2}""").find(token)?.value?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { offsetSectionForNoon(it, hasNoon) }
    }

    val start = mapEndpoint(tokens[0]) ?: return null
    val end = mapEndpoint(tokens.getOrElse(1) { tokens[0] }) ?: return null
    return (start to end).takeIf { end >= start }
}

class GlutAcademicScheduleParser : AcademicScheduleParser {
    override fun countUnscheduledCourses(html: String): Int {
        if (html.isBlank()) return 0
        val table = Jsoup.parse(html).selectFirst("table#noArrangement") ?: return 0
        val rows = table.select("tr")
        val headers = rows.firstOrNull()?.select("th,td")?.map { it.text().trim() }.orEmpty()
        val titleIndex = headers.indexOfFirst { it.contains("课程名称") || it.contains("课程名") }
        if (titleIndex < 0) return 0
        return rows.drop(1).count { row ->
            val title = row.select("th,td").getOrNull(titleIndex)?.text()?.trim().orEmpty()
            title.isNotBlank() && title.replace(" ", "") !in courseTitleHeaders
        }
    }

    /** Parse just the course adjustment (调课/补课) rows from the timetable HTML. */
    override fun parseAdjustments(html: String): List<SemesterAdjustment> {
        if (html.isBlank()) return emptyList()
        val hasNoonInTimetable = html.contains("中午")
        return parseSupplementalAdjustmentRows(html, hasNoonInTimetable).map { adj ->
            val adjId = stableId(
                listOf(
                    "adj", adj.type, adj.title, adj.teacher,
                    adj.originalWeek, adj.originalDay, adj.originalStartSection,
                    adj.originalEndSection, adj.originalRoom,
                    adj.makeupWeek, adj.makeupDay, adj.makeupStartSection,
                    adj.makeupEndSection, adj.makeupRoom
                ).joinToString("|")
            )
            SemesterAdjustment(
                id = adjId,
                type = adj.type,
                title = adj.title,
                teacher = adj.teacher,
                originalWeek = adj.originalWeek,
                originalDay = adj.originalDay,
                originalStartSection = adj.originalStartSection,
                originalEndSection = adj.originalEndSection,
                originalRoom = adj.originalRoom,
                makeupWeek = adj.makeupWeek,
                makeupDay = adj.makeupDay,
                makeupStartSection = adj.makeupStartSection,
                makeupEndSection = adj.makeupEndSection,
                makeupRoom = adj.makeupRoom
            )
        }
    }

    // applyAdjustmentsToCourses / applyAdjustmentRemovalsOnly 已随导入路径统一而删除：
    // 它们是「课程来自 A 页、调课来自 B 页」时代的胶水，现在课程与调课表同在大节课表，
    // 由下面的 parsePersonalSchedule 一次做完（内部走 applyAdjustmentRemovals）。

    override fun parsePersonalSchedule(html: String): List<ScheduleCourse> {
        require(html.isNotBlank()) { "课表 HTML 不能为空" }
        if (looksLikeNonTimetablePage(html)) return emptyList()

        // 教务"个人课表"同一页可能同时包含属性单元格、完整大节课表、课程安排表和底部补课表。
        // 旧逻辑在读到任意前置表格后提前返回，会丢掉后面的完整课表和补课时间地点。
        // 南宁课表无中午时段（第1-11节直排），不能应用桂林的中午偏移。
        val hasNoonInTimetable = html.contains("中午")
        val adjustments = parseSupplementalAdjustmentRows(html, hasNoonInTimetable)
        val gridCourses = parseExplicitCells(html) +
            parseGlutStudentTimetableGrid(html, hasNoonInTimetable) +
            parseCourseArrangementRows(html)
        val afterRemoval = applyAdjustmentRemovals(
            gridCourses,
            adjustments,
            tolerantTeacher = true
        )
        // 教务课表网格已包含调课/补课后的结果，跳过与网格重复的 makeup
        val dedupedMakeups = adjustments.mapNotNull { adj ->
            // 停课只有原时段，没有补课侧；绝不能生成第0周/0节的伪课程。
            if (adj.makeupWeek <= 0 || adj.makeupDay <= 0 ||
                adj.makeupStartSection <= 0 || adj.makeupEndSection < adj.makeupStartSection
            ) return@mapNotNull null
            val mk = adj.toMakeupCourse()
            val mkOcc = mk.occurrences.single()
            if (isMakeupCoveredByGrid(afterRemoval, mk.title, mkOcc.dayOfWeek,
                    mkOcc.startSection, mkOcc.endSection, mkOcc.note, adj.makeupWeek,
                    ignoreRoom = true)) null else mk
        }
        val primary = mergeCompatibleCourses(afterRemoval + dedupedMakeups)
        if (primary.isNotEmpty()) return CourseColorMapper.assignColors(primary)

        val secondary = (parseGridTable(html, hasNoonInTimetable) + parseSimpleTable(html))
            .distinctBy { it.id }
        if (secondary.isNotEmpty()) return CourseColorMapper.assignColors(secondary)

        return CourseColorMapper.assignColors(parseTextBased(html))
    }

    private fun parseExplicitCells(html: String): List<ScheduleCourse> {
        return cellRegex.findAll(html)
            .mapNotNull { match -> parseCell(match.value, match.groupValues[2]) }
            .toList()
    }

    private fun parseCell(rawCell: String, rawBody: String): ScheduleCourse? {
        val day = readIntAttribute(rawCell, "data-day")
            ?: readIntAttribute(rawCell, "day")
            ?: readIntAttribute(rawCell, "data-col")
            ?: readIntAttribute(rawCell, "col")
            ?: return null
        val start = readIntAttribute(rawCell, "data-start")
            ?: readIntAttribute(rawCell, "start")
            ?: readIntAttribute(rawCell, "data-section")
            ?: return null
        val end = readIntAttribute(rawCell, "data-end")
            ?: readIntAttribute(rawCell, "end")
            ?: readIntAttribute(rawCell, "data-end-section")
            ?: start

        val lines = htmlToLines(rawBody)
        if (lines.isEmpty()) return null

        val title = lines.firstOrNull { line ->
            !line.startsWith("@") && !looksLikeRoom(line) && !looksLikeWeekText(line)
        }.orEmpty().takeUnless { it.isBlank() } ?: return null

        val room = lines.firstOrNull { it.startsWith("@") || looksLikeRoom(it) }
            ?.removePrefix("@")
            .orEmpty()

        val teacher = lines.firstOrNull { line ->
            line != title &&
                line.removePrefix("@") != room &&
                !looksLikeWeekText(line) &&
                line.isNotBlank()
        }.orEmpty().ifBlank { "待确认" }

        val weekText = lines.firstOrNull { looksLikeWeekText(it) }.orEmpty()
        val id = "import-${stableId("$title-$room-$teacher-$day-$start-$end")}"

        return buildCourse(id, title, room, teacher, day, start, end, weekText)
    }

    private fun parseCourseArrangementRows(html: String): List<ScheduleCourse> {
        var titleIndex = 2
        var teacherIndex = 3
        var timeIndex = 9
        val hasNoonInTimetable = html.contains("中午")

        return rowRegex.findAll(html).flatMap { rowMatch ->
            val rawCells = tableCellRegex.findAll(rowMatch.value)
                .map { it.groupValues[1] }
                .toList()
            if (rawCells.isEmpty()) return@flatMap emptyList()

            val cells = rawCells.map { htmlToLines(it).joinToString(" ") }

            val headerTitleIndex = cells.indexOfFirst { it.contains("课程名称") }
            val headerTimeIndex = cells.indexOfFirst { cell ->
                cell.contains("上课时间") && cell.contains("地点")
            }
            if (headerTitleIndex >= 0 && headerTimeIndex >= 0) {
                titleIndex = headerTitleIndex
                teacherIndex = cells.indexOfFirst { it.contains("任课教师") || it.contains("教师") }
                    .takeIf { it >= 0 } ?: teacherIndex
                timeIndex = headerTimeIndex
                return@flatMap emptyList()
            }

            val title = cells.getOrNull(titleIndex)?.trim()
                // 只排除精确表头；“课程设计”等是正常课程名，按包含关系会静默丢课。
                ?.takeUnless { it.isBlank() || it.replace(" ", "") in courseTitleHeaders }
                ?: return@flatMap emptyList()
            val timeText = cells.getOrNull(timeIndex).orEmpty()
            val teacher = cells.getOrNull(teacherIndex).orEmpty().ifBlank { "待确认" }

            val baseId = "import-${stableId("$title-$teacher-$timeText")}"
            val occurrences = parseArrangementOccurrences(baseId, timeText, hasNoonInTimetable)
            if (occurrences.isEmpty()) return@flatMap emptyList()

            listOf(
                ScheduleCourse(
                    id = baseId,
                    title = title,
                    room = occurrences.firstOrNull()?.note.orEmpty(),
                    teacher = teacher,
                    colorHex = CourseColorMapper.colorForCourse(baseId, title),
                    occurrences = occurrences
                )
            )
        }.toList()
    }

    private fun parseGridTable(html: String, hasNoonInTimetable: Boolean): List<ScheduleCourse> {
        val rows = rowRegex.findAll(html).toList()
        if (rows.size < 2) return emptyList()

        val firstRowText = rows.firstOrNull()?.value.orEmpty()
        val hasDayHeaders = dayNames.any { firstRowText.contains(it) }
        if (!hasDayHeaders) return emptyList()

        val courses = mutableListOf<ScheduleCourse>()

        rows.drop(1).forEachIndexed { rowIndex, rowMatch ->
            val cells = tableCellRegex.findAll(rowMatch.value)
                .map { it.groupValues[1] }
                .toList()

            val periodCell = cells.firstOrNull()?.let { htmlToLines(it).joinToString(" ") }.orEmpty()
            val sectionNumber = mapDisplaySection(periodCell, hasNoonInTimetable)
                ?: periodNumberRegex.find(periodCell)?.groupValues?.get(1)?.toIntOrNull()
                ?: (rowIndex + 1)

            cells.drop(1).forEachIndexed { colIndex, cellHtml ->
                if (colIndex >= 7) return@forEachIndexed
                val lines = htmlToLines(cellHtml)
                if (lines.isEmpty() || lines.all { it.isBlank() }) return@forEachIndexed

                val day = colIndex + 1
                val cellCourses = extractCoursesFromCell(lines, day, sectionNumber)
                courses.addAll(cellCourses)
            }
        }

        return mergeCourseOccurrences(courses)
    }

    private fun parseGlutStudentTimetableGrid(html: String, hasNoonInTimetable: Boolean): List<ScheduleCourse> {
        val timetable = timetableTableRegex.find(html)?.value ?: return emptyList()
        val rows = rowRegex.findAll(timetable).drop(1).toList()
        if (rows.isEmpty()) return emptyList()

        val courses = mutableListOf<ScheduleCourse>()
        rows.forEach { rowMatch ->
            val cells = tableCellWithAttrsRegex.findAll(rowMatch.value).toList()
            if (cells.size < 2) return@forEach

            val periodText = htmlToLines(cells.first().groupValues[2]).joinToString(" ")
            val sectionNumber = mapDisplaySection(periodText, hasNoonInTimetable)
                ?: periodNumberRegex.find(periodText)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@forEach

            cells.drop(1).forEachIndexed { index, cellMatch ->
                val attrs = cellMatch.groupValues[1]
                val cellBody = cellMatch.groupValues[2]
                val lines = htmlToLines(cellBody)
                if (lines.isEmpty()) return@forEachIndexed

                val day = cellIdRegex.find(attrs)?.groupValues?.get(1)?.toIntOrNull()
                    ?: (index + 1)
                courses.addAll(parseGlutTimetableCell(lines, day, sectionNumber))
            }
        }

        return mergeCourseOccurrences(courses)
    }

    private fun parseGlutTimetableCell(
        lines: List<String>,
        day: Int,
        sectionNumber: Int
    ): List<ScheduleCourse> {
        val titleIndexes = lines.mapIndexedNotNull { index, line ->
            index.takeIf { glutCourseTitleRegex.containsMatchIn(line) }
        }
        if (titleIndexes.isEmpty()) return emptyList()

        return titleIndexes.mapIndexedNotNull { index, titleIndex ->
            val nextTitleIndex = titleIndexes.getOrNull(index + 1) ?: lines.size
            val titleLine = lines[titleIndex]
            val title = glutCourseTitleRegex.find(titleLine)
                ?.groupValues
                ?.get(1)
                ?.trim()
                .orEmpty()
                .takeUnless { it.isBlank() }
                ?: return@mapIndexedNotNull null

            val rawDetailLines = lines.subList(titleIndex + 1, nextTitleIndex)
                .map { it.trim() }
                .filter { it.isNotBlank() }
            val detailLines = rawDetailLines.filter { !looksLikeClassHourType(it) }
            val roomCandidate = detailLines.getOrNull(0).orEmpty()
            val room = roomCandidate.takeIf { looksLikeRoom(it) }.orEmpty()
            val teacherStartIndex = if (room.isBlank()) 0 else 1
            val contentLines = detailLines.drop(teacherStartIndex)
            // 实验课块里会出现"2-1"这类课序字段，它不是周次；优先使用带"周"的显式周次。
            val weekText = contentLines.drop(1).firstOrNull { looksLikeExplicitWeekText(it) }
                ?: contentLines.drop(1).firstOrNull { looksLikeFragmentedOddEvenWeekText(it) }
                ?: contentLines.drop(1).firstOrNull { looksLikeCompactWeekText(it) }
                ?: rawDetailLines.firstOrNull { looksLikeExplicitWeekText(it) }
                ?: contentLines.firstOrNull { looksLikeFragmentedOddEvenWeekText(it) }
                ?: contentLines.firstOrNull { looksLikeCompactWeekText(it) }
                // 课程块最后一个非课时字段就是周次；未知文本原样保留，由告警层处理。
                ?: contentLines.lastOrNull().takeIf { contentLines.size >= 2 }
                ?: ""
            val teacher = contentLines.filter { line ->
                line != weekText &&
                    !looksLikeRoom(line) &&
                    !looksLikeWeekText(line) &&
                    !line.any(Char::isDigit)
            }.joinToString(" ")

            val id = "import-${stableId("glut-grid-$title-$room-$teacher-$weekText")}"
            buildCourse(
                id = id,
                title = title,
                room = room,
                teacher = teacher.ifBlank { "待确认" },
                day = day,
                startSection = sectionNumber,
                endSection = sectionNumber,
                weekText = weekText
            )
        }
    }

    private fun extractCoursesFromCell(
        lines: List<String>,
        day: Int,
        sectionNumber: Int
    ): List<ScheduleCourse> {
        val results = mutableListOf<ScheduleCourse>()
        val nonEmptyLines = lines.filter { it.isNotBlank() }

        val title = nonEmptyLines.firstOrNull().orEmpty()
        if (title.isBlank()) return results

        val room = nonEmptyLines.firstOrNull {
            looksLikeRoom(it)
        }?.removePrefix("@").orEmpty()

        val teacher = nonEmptyLines.firstOrNull {
            it != title && it != room.removePrefix("@") && !looksLikeWeekText(it)
        }.orEmpty()

        val weekText = nonEmptyLines.firstOrNull {
            looksLikeWeekText(it)
        }.orEmpty()

        val id = "import-${stableId("grid-$title-$room-$teacher-$day")}"
        results.add(
            buildCourse(
                id = id,
                title = title,
                room = room,
                teacher = teacher.ifBlank { "待确认" },
                day = day,
                startSection = sectionNumber,
                endSection = sectionNumber,
                weekText = weekText
            )
        )

        return results
    }

    // 教务多行调课：首行含完整课程信息，续行 MM-DD 开头仅含时间/教室
    private val continuationRowDateRegex = Regex("""^\d{2}-\d{2}$""")

    private fun parseSupplementalAdjustmentRows(html: String, hasNoonInTimetable: Boolean): List<ScheduleAdjustment> {
        val results = mutableListOf<ScheduleAdjustment>()
        var pendingType = ""
        var pendingTitle = ""
        var pendingTeacher = ""

        for (rowMatch in rowRegex.findAll(html)) {
            val cells = tableCellRegex.findAll(rowMatch.value)
                .map { htmlToLines(it.groupValues[1]).joinToString(" ").trim() }
                .toList()
            if (cells.size < 5) continue

            val typeCell = cells.first()

            // 主行：类型（调课/补课/停课/代课）
            if (typeCell in KNOWN_TYPES && cells.size >= 12) {
                val title = cells.getOrNull(2)
                    ?.takeUnless { it.isBlank() || it.contains("课程名") }
                    ?: continue
                val teacher = cells.getOrNull(4).orEmpty().ifBlank { "待确认" }
                pendingType = typeCell; pendingTitle = title; pendingTeacher = teacher
                val adj = parseAdjustmentRow(cells, typeCell, title, teacher, hasNoonInTimetable)
                if (adj != null) {
                    // 补课主行若没有补课时间，就只用于给续行提供课程信息，本身不入列。
                    //
                    // 代课主行则要入列：代课保留 original 侧，主行有 original 时段就是一条有效记录。
                    // （rowspan 覆盖两个时段时，第二个时段由续行补充成另一条记录。）
                    val keepMainRow = when (typeCell) {
                        "代课" -> adj.originalWeek > 0 || adj.makeupWeek > 0
                        "补课" -> adj.makeupWeek > 0
                        else -> true
                    }
                    if (keepMainRow) results.add(adj)
                }
            }
            // 续行：MM-DD 日期开头，10 列纯时间数据，继承课程信息
            else if (continuationRowDateRegex.matches(typeCell) && cells.size >= 10 && pendingTitle.isNotBlank()) {
                val contAdj = parseContinuationRow(cells, pendingType, pendingTitle, pendingTeacher, hasNoonInTimetable)
                if (contAdj != null) results.add(contAdj)
            }
        }
        return results
    }

    private fun parseAdjustmentRow(cells: List<String>, type: String, title: String, teacher: String, hasNoon: Boolean): ScheduleAdjustment? {
        val makeupBase = cells.size - 5
        val cellsOrigWeek = cells.getOrNull(makeupBase - 4)
            ?.let { weekNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val cellsOrigDay = cells.getOrNull(makeupBase - 3)
            ?.let { parseWeekdayText(it) } ?: 0
        val (cellsOrigStart, cellsOrigEnd) = cells.getOrNull(makeupBase - 2)
            ?.let { parseDisplaySectionRange(it, hasNoon) } ?: Pair(0, 0)
        val cellsOrigRoom = cells.getOrNull(makeupBase - 1).orEmpty()
        val cellsMkWeek = cells.getOrNull(makeupBase + 1)
            ?.let { weekNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val cellsMkDay = cells.getOrNull(makeupBase + 2)
            ?.let { parseWeekdayText(it) } ?: 0
        val (cellsMkStart, cellsMkEnd) = cells.getOrNull(makeupBase + 3)
            ?.let { parseDisplaySectionRange(it, hasNoon) } ?: Pair(0, 0)
        val cellsMkRoom = cells.getOrNull(makeupBase + 4).orEmpty()

        // 补课：停/代课侧有数据但补课侧为空时，把这一组时间直接当补课时段——补课记录本来
        // 就只有补课侧有意义。
        //
        // 代课**不参与**这个交换：代课只是换个授课人，课仍在原时段上，角标锚在原卡上，
        // 必须保留 original 侧。一旦搬到 makeup 侧，originalWeek 就归零，反查永远落空。
        val originalWeek: Int; val originalDay: Int; val originalStart: Int; val originalEnd: Int; val originalRoom: String
        val makeupWeek: Int; val makeupDay: Int; val makeupStart: Int; val makeupEnd: Int; val makeupRoom: String
        if (type == "补课" && cellsMkWeek == 0 && cellsOrigWeek > 0) {
            originalWeek = 0; originalDay = 0; originalStart = 0; originalEnd = 0; originalRoom = ""
            makeupWeek = cellsOrigWeek; makeupDay = cellsOrigDay; makeupStart = cellsOrigStart; makeupEnd = cellsOrigEnd; makeupRoom = cellsOrigRoom
        } else {
            originalWeek = cellsOrigWeek; originalDay = cellsOrigDay; originalStart = cellsOrigStart; originalEnd = cellsOrigEnd; originalRoom = cellsOrigRoom
            makeupWeek = cellsMkWeek; makeupDay = cellsMkDay; makeupStart = cellsMkStart; makeupEnd = cellsMkEnd; makeupRoom = cellsMkRoom
        }

        val hasValidTime = (originalWeek > 0 && originalDay > 0) || (makeupWeek > 0 && makeupDay > 0)
        if (!hasValidTime && type != "停课") return null
        return ScheduleAdjustment(type, title, teacher,
            originalWeek, originalDay, originalStart, originalEnd, originalRoom,
            makeupWeek, makeupDay, makeupStart, makeupEnd, makeupRoom)
    }

    /**
     * 续行: [0]原日期 [1]原周 [2]原星期 [3]原节次 [4]原教室 [5]补日期 [6]补周 [7]补星期 [8]补节次 [9]补教室
     * 注意: 当主行是"代课"等 rowspan 场景时，续行可能只有 5 列有意义的补课数据。
     * 此时 cells[0]-[4] 实际是补课时间，cells[5]-[9] 为空。通过检测 makeup 时间是否为空来判别。
     */
    private fun parseContinuationRow(cells: List<String>, type: String, title: String, teacher: String, hasNoon: Boolean): ScheduleAdjustment? {
        val firstDateWeek = cells.getOrNull(1)
            ?.let { weekNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val firstDay = cells.getOrNull(2)?.let { parseWeekdayText(it) } ?: 0
        val (firstStartSection, firstEndSection) = cells.getOrNull(3)
            ?.let { parseDisplaySectionRange(it, hasNoon) } ?: Pair(0, 0)
        val firstRoom = cells.getOrNull(4).orEmpty()
        val secondWeek = cells.getOrNull(6)
            ?.let { weekNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val secondDay = cells.getOrNull(7)?.let { parseWeekdayText(it) } ?: 0
        val (secondStartSection, secondEndSection) = cells.getOrNull(8)
            ?.let { parseDisplaySectionRange(it, hasNoon) } ?: Pair(0, 0)
        val secondRoom = cells.getOrNull(9).orEmpty()

        // 续行的 cells[0]-[4] 在**调课/补课**记录里可能装的是纯补课时间（第二组全空），
        // 那时要搬到 makeup 侧。
        //
        // 停课与代课的时间一律写在「停/代课时间地点」侧、第二组恒为空：对它们来说
        // cells[0]-[4] 就是**原时段**，必须留在 original 侧。搬走会让第二个时段静默消失，
        // 卡片上就少一个「停」/「代」角标。
        val secondHasValidTime = secondWeek > 0 && secondDay > 0
        val firstHasValidTime = firstDateWeek > 0 && firstDay > 0
        val firstGroupIsMakeup = type !in setOf("停课", "代课") && !secondHasValidTime && firstHasValidTime
        val originalWeek: Int; val originalDay: Int; val originalStart: Int; val originalEnd: Int; val originalRoom: String
        val makeupWeek: Int; val makeupDay: Int; val makeupStart: Int; val makeupEnd: Int; val makeupRoom: String
        if (firstGroupIsMakeup) {
            // 仅 cells[0]-[4] 有数据 → 视为纯补课时间
            originalWeek = 0; originalDay = 0; originalStart = 0; originalEnd = 0; originalRoom = ""
            makeupWeek = firstDateWeek; makeupDay = firstDay; makeupStart = firstStartSection; makeupEnd = firstEndSection; makeupRoom = firstRoom
        } else {
            originalWeek = firstDateWeek; originalDay = firstDay; originalStart = firstStartSection; originalEnd = firstEndSection; originalRoom = firstRoom
            makeupWeek = secondWeek; makeupDay = secondDay; makeupStart = secondStartSection; makeupEnd = secondEndSection; makeupRoom = secondRoom
        }

        val hasValidTime = (originalWeek > 0 && originalDay > 0) || (makeupWeek > 0 && makeupDay > 0)
        if (!hasValidTime && type != "停课") return null
        return ScheduleAdjustment(type, title, teacher,
            originalWeek, originalDay, originalStart, originalEnd, originalRoom,
            makeupWeek, makeupDay, makeupStart, makeupEnd, makeupRoom)
    }

    /**
     * 移除调课对应的原周次。
     *
     * [requireOriginalRoom] 控制教室匹配口径：
     *  - false（默认）：保留历史行为，即「原教室为空时通配任意教室」。南宁与模式1 依赖它。
     *  - true：模式2 桂林专用。要求调课表的原教室**非空**，且与课次教室规范化后相等；
     *    原教室未知时宁可少删——空教室通配会顺带命中「同天同节次同周、只是教室不同」的补课时段。
     */
    private fun applyAdjustmentRemovals(
        courses: List<ScheduleCourse>,
        adjustments: List<ScheduleAdjustment>,
        requireOriginalRoom: Boolean = false,
        tolerantTeacher: Boolean = false
    ): List<ScheduleCourse> {
        if (adjustments.isEmpty()) return courses
        // 停课与代课都不取消原课：代课只是换个授课人；停课那一周教务网格里本来就在
        // （实测 `1-9周` 这类范围把停课周也算在内），卡片保留、由左下角「停」角标标出。
        // 只有调课需要把被调走的原周次摘掉。
        val removalAdjustments = adjustments.filter { it.type !in setOf("代课", "停课") }
        if (removalAdjustments.isEmpty()) return courses
        return courses.mapNotNull { course ->
            val updatedOccurrences = course.occurrences.flatMap { occurrence ->
                // 一个课次可能被调走**多周**（实测 `8-16周` 同时挂着第14周与第16周两条调课），
                // 必须把所有命中的周次一起摘掉。只应用第一条会在后面那几周留下一张「幽灵卡」：
                // 那节课早已调到别的周，用户按课表去教室会扑空，课时统计也会偏大。
                val weeksToRemove = removalAdjustments
                    .filter { it.matches(course, occurrence, requireOriginalRoom, tolerantTeacher) }
                    .map { it.originalWeek }
                    .toSet()
                if (weeksToRemove.isEmpty()) {
                    listOf(occurrence)
                } else {
                    // withoutWeek 会把周次拆成多段（`8-16周` 摘掉第14周得 `8-13周` + `15-16周`），
                    // 所以每摘一周都要在**拆出来的每一条**上继续摘，不能只处理原对象。
                    weeksToRemove.fold(listOf(occurrence)) { remaining, week ->
                        remaining.flatMap { it.withoutWeek(week) }
                    }
                }
            }
            if (updatedOccurrences.isEmpty()) null else course.copy(occurrences = updatedOccurrences)
        }
    }

    private fun ScheduleAdjustment.toMakeupCourse(): ScheduleCourse {
        val id = "import-${stableId("supplemental-$title-$teacher-$makeupRoom")}"
        return ScheduleCourse(
            id = id,
            title = title,
            room = makeupRoom,
            teacher = teacher,
            colorHex = CourseColorMapper.colorForCourse(id, title),
            occurrences = listOf(
                CourseOccurrence(
                    id = "$id-makeup-$makeupWeek-$makeupDay-$makeupStartSection-$makeupEndSection",
                    courseId = id,
                    dayOfWeek = makeupDay,
                    startSection = makeupStartSection,
                    endSection = makeupEndSection,
                    weekText = "第${makeupWeek}周",
                    note = makeupRoom
                )
            )
        )
    }

    private fun mergeCourseOccurrences(courses: List<ScheduleCourse>): List<ScheduleCourse> {
        return courses.groupBy { it.id }.map { (_, group) ->
            val first = group.first()
            first.copy(occurrences = mergeAdjacentOccurrences(group.flatMap { it.occurrences }))
        }
    }

    /**
     * 检查调整产生的 makeup 是否已被网格中已有的课程覆盖。
     * 教务网格通常会直接包含调课/补课后的时间，避免重复添加导致冲突角标。
     *
     * [ignoreRoom] 为 true 时不比较教室，只看「课程名 + 星期 + 节次 + 周次」。模式2 专用：
     * 个人课表可能已用**另一个教室文本**列出同一补课时段（例如原教室写「线上教学」、
     * 补课写「05308D」），此时若仍要求教室精确相等，去重失效就会追加出第二张卡片。
     */
    private fun isMakeupCoveredByGrid(
        gridCourses: List<ScheduleCourse>,
        title: String, dayOfWeek: Int, startSection: Int, endSection: Int,
        note: String, week: Int,
        ignoreRoom: Boolean = false
    ): Boolean {
        if (week <= 0) return false
        return gridCourses.any { gc ->
            gc.title.trim() == title.trim() &&
            gc.occurrences.any { occ ->
                occ.dayOfWeek == dayOfWeek &&
                occ.startSection == startSection &&
                occ.endSection == endSection &&
                (ignoreRoom || occ.note.trim() == note.trim()) &&
                com.glut.schedule.data.model.isWeekTextActive(occ.weekText, week)
            }
        }
    }

    private fun mergeCompatibleCourses(courses: List<ScheduleCourse>): List<ScheduleCourse> {
        return courses.flatMap { splitCourseByOccurrenceRoom(it) }
            .groupBy { "${it.title.trim()}|${it.teacher.trim()}|${it.room.trim()}" }
            .map { (_, group) ->
                val first = group.first()
                val room = first.room
                // 教务课表网格已包含调课/补课后的课程，调整表的 toMakeupCourse 会生成
                // 与网格重复的 occurrence，通过 (day, section, weekText, note) 去重
                val occurrences = mergeAdjacentOccurrences(
                    group.flatMap { it.occurrences }
                        .distinctBy { "${it.dayOfWeek}|${it.startSection}|${it.endSection}|${it.note}|${it.weekText}" }
                )
                    .mapIndexed { index, occurrence ->
                        occurrence.copy(
                            id = "${first.id}-occurrence-$index",
                            courseId = first.id
                        )
                    }
                first.copy(
                    room = room,
                    occurrences = occurrences
                )
            }
    }

    private fun splitCourseByOccurrenceRoom(course: ScheduleCourse): List<ScheduleCourse> {
        return course.occurrences
            .groupBy { occurrence -> occurrence.note.trim().ifBlank { course.room.trim() } }
            .map { (room, occurrences) ->
                val id = "import-${stableId("room-bound-${course.title}-${course.teacher}-$room")}"
                course.copy(
                    id = id,
                    room = room,
                    occurrences = occurrences.mapIndexed { index, occurrence ->
                        occurrence.copy(
                            id = "$id-occurrence-$index",
                            courseId = id,
                            note = room
                        )
                    }
                )
            }
    }

    private fun mergeAdjacentOccurrences(occurrences: List<CourseOccurrence>): List<CourseOccurrence> {
        val merged = mutableListOf<CourseOccurrence>()
        occurrences
            .sortedWith(compareBy<CourseOccurrence> { it.dayOfWeek }.thenBy { it.startSection })
            .forEach { occurrence ->
                val previous = merged.lastOrNull()
                if (previous != null &&
                    previous.dayOfWeek == occurrence.dayOfWeek &&
                    previous.weekText == occurrence.weekText &&
                    previous.note == occurrence.note &&
                    previous.endSection + 1 == occurrence.startSection
                ) {
                    merged[merged.lastIndex] = previous.copy(endSection = occurrence.endSection)
                } else if (previous == null ||
                    previous.dayOfWeek != occurrence.dayOfWeek ||
                    previous.startSection != occurrence.startSection ||
                    previous.endSection != occurrence.endSection ||
                    previous.weekText != occurrence.weekText ||
                    previous.note != occurrence.note
                ) {
                    merged.add(occurrence)
                }
            }
        return merged
    }

    private fun parseSimpleTable(html: String): List<ScheduleCourse> {
        val courses = mutableListOf<ScheduleCourse>()
        var currentDay = 0

        for (rowMatch in rowRegex.findAll(html)) {
            val cells = tableCellRegex.findAll(rowMatch.value)
                .map { htmlToLines(it.groupValues[1]) }
                .toList()

            cells.forEach { lines ->
                dayNames.forEachIndexed { index, name ->
                    val joined = lines.joinToString(" ")
                    if (joined.contains("星期$name") || joined.contains("周$name")) {
                        currentDay = index + 1
                    }
                }

                val day = readDayAttribute(rowMatch.value)
                if (day > 0) currentDay = day
                if (currentDay == 0) return@forEach

                val title = lines.firstOrNull { line ->
                    line.length in 2..30 &&
                        !line.startsWith("@") &&
                        !looksLikeRoom(line) &&
                        !looksLikeWeekText(line) &&
                        !dayNames.any { line.contains("星期$it") || line.contains("周$it") }
                } ?: return@forEach

                val room = lines.firstOrNull { looksLikeRoom(it) }
                    ?.removePrefix("@").orEmpty()
                val weekText = lines.firstOrNull { looksLikeWeekText(it) }.orEmpty()
                val id = "import-${stableId("simple-$title-$room-$currentDay")}"

                courses.add(
                    buildCourse(
                        id = id,
                        title = title,
                        room = room,
                        teacher = "待确认",
                        day = currentDay,
                        startSection = 0,
                        endSection = 0,
                        weekText = weekText
                    )
                )
            }
        }

        return courses.filter { it.occurrences.isNotEmpty() }
    }

    private fun parseTextBased(html: String): List<ScheduleCourse> {
        val courses = mutableListOf<ScheduleCourse>()
        val text = htmlToLines(html).joinToString(" ")
        val hasNoonInTimetable = html.contains("中午")

        for (match in textBasedRegex.findAll(text)) {
            val title = match.groupValues[1].trim()
            val teacher = match.groupValues[2].trim().ifBlank { "待确认" }
            val timeText = match.groupValues[3]
            val id = "import-${stableId("text-$title-$teacher")}"
            val occurrences = parseArrangementOccurrences(id, timeText, hasNoonInTimetable)

            if (occurrences.isNotEmpty()) {
                courses.add(
                    ScheduleCourse(
                        id = id,
                        title = title,
                        room = occurrences.firstOrNull()?.note.orEmpty(),
                        teacher = teacher,
                        colorHex = CourseColorMapper.colorForCourse(id, title),
                        occurrences = occurrences
                    )
                )
            }
        }

        return courses
    }

    private fun parseArrangementOccurrences(
        courseId: String,
        text: String,
        hasNoon: Boolean = true
    ): List<CourseOccurrence> {
        val prefixes = arrangementPrefixRegex.findAll(text).toList()
        return prefixes.mapIndexedNotNull { index, match ->
                val tailEnd = prefixes.getOrNull(index + 1)?.range?.first ?: text.length
                val tail = text.substring(match.range.last + 1, tailEnd).trim()
                val tailParts = tail.split(Regex("""\s+"""), limit = 2)
                val sectionText = tailParts.getOrNull(0).orEmpty()
                val weekText = match.groupValues[1].trim().ifBlank { "全周" }
                val day = dayOfWeek(match.groupValues[2]) ?: return@mapIndexedNotNull null
                val (start, end) = parseDisplaySectionRange(sectionText, hasNoon)
                    ?: return@mapIndexedNotNull null
                val room = tailParts.getOrNull(1).orEmpty().trim()

                CourseOccurrence(
                    id = "$courseId-occurrence-$index",
                    courseId = courseId,
                    dayOfWeek = day,
                    startSection = start.coerceIn(1, 14),
                    endSection = end.coerceIn(start, 14),
                    weekText = weekText,
                    note = room
                )
            }
            .toList()
    }

    private fun buildCourse(
        id: String,
        title: String,
        room: String,
        teacher: String,
        day: Int,
        startSection: Int,
        endSection: Int,
        weekText: String
    ): ScheduleCourse {
        val effectiveEnd = if (endSection == 0) startSection else endSection
        val effectiveStart = if (startSection == 0) effectiveEnd else startSection
        return ScheduleCourse(
            id = id,
            title = title,
            room = room,
            teacher = teacher.ifBlank { "待确认" },
            colorHex = CourseColorMapper.colorForCourse(id, title),
            occurrences = listOf(
                CourseOccurrence(
                    id = "$id-occurrence",
                    courseId = id,
                    dayOfWeek = day.coerceIn(1, 7),
                    startSection = effectiveStart.coerceIn(1, 14),
                    endSection = effectiveEnd.coerceIn(effectiveStart, 14),
                    weekText = weekText.ifBlank { "全周" },
                    note = room
                )
            )
        )
    }

    private fun readIntAttribute(tag: String, name: String): Int? {
        val regex = Regex("""$name\s*=\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE)
        return regex.find(tag)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun readDayAttribute(tag: String): Int {
        for ((index, name) in dayNames.withIndex()) {
            if (tag.contains("data-day=\"${index + 1}\"") ||
                tag.contains("day=\"${index + 1}\"") ||
                tag.contains("星期$name") ||
                tag.contains("周$name")
            ) {
                return index + 1
            }
        }
        return 0
    }

    private fun htmlToLines(html: String): List<String> {
        return html
            .replace(Regex("""(?is)<(script|style|noscript).*?</\1>"""), "")
            .replace(Regex("""(?i)<br\s*/?>|</div>|</p>|</li>|</td>|</th>|</tr>"""), "\n")
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#13;", "\n")
            .replace("&#10;", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "-" }
    }

    private fun looksLikeRoom(value: String): Boolean {
        // 只用 NFKC 做识别与比较，课程展示仍保留页面原文。
        val clean = Normalizer.normalize(value.removePrefix("@").trim(), Normalizer.Form.NFKC)
        return Regex("""^\d{4,8}[A-Za-z]?$""").matches(clean) ||
            clean.contains("线上") ||
            clean.contains("馆") ||
            clean.contains("教室") ||
            clean.contains("楼") ||
            (clean.length in 4..10 && clean.any { it.isDigit() } && clean.any { it.isLetter() })
    }

    private fun looksLikeWeekText(value: String): Boolean {
        return looksLikeExplicitWeekText(value) ||
            looksLikeCompactWeekText(value) ||
            looksLikeFragmentedOddEvenWeekText(value)
    }

    private fun CourseOccurrence.withoutWeek(week: Int): List<CourseOccurrence> {
        val remainingWeekTexts = weekTextWithoutWeek(weekText, week)
        return remainingWeekTexts.mapIndexed { index, remainingWeekText ->
            copy(
                id = "$id-adjusted-$index",
                weekText = remainingWeekText
            )
        }
    }

    private fun looksLikeExplicitWeekText(value: String): Boolean {
        val clean = value.trim()
        if (clean == "单周" || clean == "双周") return true
        return (clean.contains("周") || clean.contains("单周") || clean.contains("双周")) &&
            weekSpanRegex.containsMatchIn(clean)
    }

    private fun looksLikeCompactWeekText(value: String): Boolean {
        val clean = value.trim()
        if (!compactWeekTextRegex.matches(clean)) return false

        return clean.split(',', '，').all { token ->
            val parts = token.trim().split("-", "－", "—")
            val start = parts.getOrNull(0)?.toIntOrNull()
            val end = parts.getOrNull(1)?.toIntOrNull() ?: start
            start != null &&
                end != null &&
                start in 1..22 &&
                end in 1..22 &&
                start <= end
        }
    }

    private fun looksLikeFragmentedOddEvenWeekText(value: String): Boolean {
        val clean = value.trim()
        if (!clean.contains('单') && !clean.contains('双')) return false
        return clean.split(',', '，').all { fragment ->
            Regex("""^\d{1,2}(?:[-－—]\d{1,2})?(?:单|双)?(?:周)?$""")
                .matches(fragment.trim())
        }
    }

    private fun looksLikeClassHourType(value: String): Boolean {
        // 南宁的块用「课程学时」标注，桂林用讲课/实验/上机学时。少了南宁这一种，
        // 它就会在「不是教室、不是周次」的兜底规则里被当成教师名。
        return value.contains("讲课学时") ||
            value.contains("实验学时") ||
            value.contains("上机学时") ||
            value.contains("实践学时") ||
            value.contains("课程学时")
    }

    private fun dayOfWeek(value: String): Int? {
        return when (value.trim()) {
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "日", "天" -> 7
            else -> null
        }
    }

    private fun parseWeekdayText(value: String): Int? {
        return dayNames.firstOrNull { value.contains(it) }?.let { dayOfWeek(it) }
    }

    private fun parseSectionRange(value: String): Pair<Int, Int>? {
        val match = sectionRangeRegex.find(value) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].toIntOrNull() ?: start
        return start to end
    }

    private fun looksLikeNonTimetablePage(html: String): Boolean {
        val text = htmlToLines(html).joinToString(" ")
        return text.contains("综合审查结果") ||
            text.contains("累计学分审查") ||
            text.contains("学籍处理") ||
            text.contains("成绩查询") ||
            (text.contains("登录") && text.contains("密码") && !text.contains("课表") && !text.contains("课程"))
    }

    private fun stableId(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private data class ScheduleAdjustment(
        val type: String,
        val title: String,
        val teacher: String,
        val originalWeek: Int,
        val originalDay: Int,
        val originalStartSection: Int,
        val originalEndSection: Int,
        val originalRoom: String,
        val makeupWeek: Int,
        val makeupDay: Int,
        val makeupStartSection: Int,
        val makeupEndSection: Int,
        val makeupRoom: String
    ) {
        fun matches(
            course: ScheduleCourse,
            occurrence: CourseOccurrence,
            requireOriginalRoom: Boolean = false,
            tolerantTeacher: Boolean = false
        ): Boolean {
            val occurrenceRoom = occurrence.note.ifBlank { course.room }
            // 严格口径：原教室必须非空且实打实相等；原教室未知时拒绝匹配（宁可少删，不可错删）
            val roomMatched = if (requireOriginalRoom) {
                originalRoom.isNotBlank() && normalizeRoomKey(originalRoom) == normalizeRoomKey(occurrenceRoom)
            } else {
                occurrenceRoom.trim() == originalRoom.trim()
            }
            return course.title.trim() == title.trim() &&
                teacherMatches(teacher, course.teacher, tolerantTeacher) &&
                occurrence.dayOfWeek == originalDay &&
                occurrence.startSection == originalStartSection &&
                occurrence.endSection == originalEndSection &&
                roomMatched &&
                com.glut.schedule.data.model.isWeekTextActive(occurrence.weekText, originalWeek)
        }

    }

    private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    /**
     * 教务"中午1/2"行夹在第4节和第5节之间，后续节次号偏移+2。
     * 仅限桂林本部（HTML含"中午"），南宁无中午时段，节次直排1-11。
     * 桂林: 第1-4节→1-4, 中午1/2→5/6, 第5-12节→7-14
     * 南宁: 第1-11节→1-11
     */
    private fun mapDisplaySection(periodText: String, hasNoon: Boolean = true): Int? = when {
        periodText.contains("中午1") -> 5
        periodText.contains("中午2") -> 6
        else -> {
            val n = periodNumberRegex.find(periodText)?.groupValues?.get(1)?.toIntOrNull()
            n?.let { offsetSectionForNoon(it, hasNoon) }
        }
    }

    /** 节次偏移规则见 [offsetSectionForNoon]（与南宁路径共用同一实现）。 */

    private companion object {
        val KNOWN_TYPES = setOf("调课", "补课", "停课", "代课")
        val courseTitleHeaders = setOf("课程", "课程名", "课程名称")
        val cellRegex = Regex("""(?is)<td\b([^>]*)>(.*?)</td>""")
        val timetableTableRegex = Regex("""(?is)<table\b(?=[^>]*\bid\s*=\s*["']timetable["'])[^>]*>.*?</table>""")
        val rowRegex = Regex("""(?is)<tr\b[^>]*>.*?</tr>""")
        val tableCellRegex = Regex("""(?is)<t[dh]\b[^>]*>(.*?)</t[dh]>""")
        val tableCellWithAttrsRegex = Regex("""(?is)<t[dh]\b([^>]*)>(.*?)</t[dh]>""")
        val cellIdRegex = Regex("""\bid\s*=\s*["']([1-7])-\d+["']""")
        val glutCourseTitleRegex = Regex("""<<\s*(.+?)\s*>>""")
        val arrangementPrefixRegex = Regex(
            // 先定位每个“周次 + 星期”前缀，再用下一个前缀切分尾部；节次合法性统一交给
            // parseDisplaySectionRange，避免整行正则再次遗漏“中午”等已支持格式。
            //
            // 周次字符类里**绝不能有“节”**：周次文本从不含“节”字，而“节”一旦在类内，
            // 扫描到上一条的节次（如“第5、6节”）时会一路吞到**下一条的周次**才碰到“星期”，
            // 于是上一条尾巴取不到节次被整条丢弃、下一条的周次被读成“第5、6节 第13周”。
            // 实测（currcourse.jsdo《工程伦理》那格：第 3 条教室为空）正是这个形状。
            // 小程序 utils/parser.js 的 parseArrangementTime 有同款规则，两端必须保持一致。
            """((?:单周|双周|全周|[第\d][第\d,，、\-~－—至单双周\s]*?)?)\s*星期([一二三四五六日天])"""
        )
        val periodNumberRegex = Regex("""第?\s*(\d{1,2})\s*[节大]""")
        val textBasedRegex = Regex(
            """([一-龥a-zA-Z()+]+(?:[A-DB]|[Ⅰ-Ⅻ]|[1-9]|[一二三四五六七八九十]))\s*[：:]?\s*([一-龥]{2,4}(?:老师)?)?[，,\s]*([^，,\n]*(?:星期[一二三四五六日天]\s*第\s*\d{1,2}[、,，至~\-－—]\s*\d{1,2}\s*节[^，,\n]*)+)"""
        )
        val compactWeekTextRegex = Regex("""^\d{1,2}(?:[-－—]\d{1,2})?(?:\s*[,，]\s*\d{1,2}(?:[-－—]\d{1,2})?)*$""")
        val sectionRangeRegex = Regex("""第\s*(\d{1,2})\s*(?:[、,，]|至|~|-|－|—)\s*(\d{1,2})\s*节""")
        val weekNumberRegex = Regex("""(\d{1,2})""")
        val weekSpanRegex = Regex("""\d{1,2}(?:[-－—]\d{1,2})?""")
    }
}

package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment
import java.security.MessageDigest

interface AcademicScheduleParser {
    fun parsePersonalSchedule(html: String): List<ScheduleCourse>
    fun parseAdjustments(html: String): List<SemesterAdjustment> = emptyList()
    /** Apply adjustments from HTML to an existing course list (used when courses come from
     *  a different source than adjustments, e.g. Nanning currcourse.jsdo + showTimetable.do). */
    fun applyAdjustmentsToCourses(courses: List<ScheduleCourse>, adjustmentHtml: String): List<ScheduleCourse> = courses
}

class GlutAcademicScheduleParser : AcademicScheduleParser {
    /** Parse just the course adjustment (调课/补课) rows from the timetable HTML. */
    override fun parseAdjustments(html: String): List<SemesterAdjustment> {
        if (html.isBlank()) return emptyList()
        val hasNoonInTimetable = html.contains("中午")
        return parseSupplementalAdjustmentRows(html, hasNoonInTimetable).map { adj ->
            val adjId = stableId("adj-${adj.title}-${adj.teacher}-${adj.originalWeek}-${adj.originalDay}-${adj.makeupWeek}-${adj.makeupDay}")
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

    /** Apply adjustments from HTML to an existing course list (used for Nanning where courses
     *  come from currcourse.jsdo but adjustments come from showTimetable.do). */
    override fun applyAdjustmentsToCourses(courses: List<ScheduleCourse>, adjustmentHtml: String): List<ScheduleCourse> {
        if (adjustmentHtml.isBlank()) return courses
        val hasNoonInTimetable = adjustmentHtml.contains("中午")
        val adjustments = parseSupplementalAdjustmentRows(adjustmentHtml, hasNoonInTimetable)
        if (adjustments.isEmpty()) return courses
        val afterRemoval = applyAdjustmentRemovals(courses, adjustments)
        val dedupedMakeups = adjustments.mapNotNull { adj ->
            val mk = adj.toMakeupCourse()
            val mkOcc = mk.occurrences.single()
            if (isMakeupCoveredByGrid(afterRemoval, mk.title, mkOcc.dayOfWeek,
                    mkOcc.startSection, mkOcc.endSection, mkOcc.note, adj.makeupWeek)) null else mk
        }
        return mergeCompatibleCourses(afterRemoval + dedupedMakeups)
    }

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
        val afterRemoval = applyAdjustmentRemovals(gridCourses, adjustments)
        // 教务课表网格已包含调课/补课后的结果，跳过与网格重复的 makeup
        val dedupedMakeups = adjustments.mapNotNull { adj ->
            val mk = adj.toMakeupCourse()
            val mkOcc = mk.occurrences.single()
            if (isMakeupCoveredByGrid(afterRemoval, mk.title, mkOcc.dayOfWeek,
                    mkOcc.startSection, mkOcc.endSection, mkOcc.note, adj.makeupWeek)) null else mk
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
                ?.takeUnless { it.isBlank() || it.contains("课程名称") || it.contains("课程") }
                ?: return@flatMap emptyList()
            val timeText = cells.getOrNull(timeIndex).orEmpty()
            val teacher = cells.getOrNull(teacherIndex).orEmpty().ifBlank { "待确认" }

            val baseId = "import-${stableId("$title-$teacher-$timeText")}"
            val occurrences = parseArrangementOccurrences(baseId, timeText)
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
            // 实验课块里会出现"2-1"这类课序字段，它不是周次；优先使用带"周"的显式周次。
            val weekText = detailLines.drop(teacherStartIndex + 1).firstOrNull { looksLikeExplicitWeekText(it) }
                ?: detailLines.drop(teacherStartIndex + 1).firstOrNull { looksLikeCompactWeekText(it) }
                ?: rawDetailLines.firstOrNull { looksLikeExplicitWeekText(it) }
                ?: detailLines.firstOrNull { looksLikeCompactWeekText(it) }
                ?: ""
            val teacher = detailLines.drop(teacherStartIndex).firstOrNull { line ->
                line != roomCandidate &&
                    line != weekText &&
                    !looksLikeRoom(line) &&
                    !looksLikeWeekText(line) &&
                    !looksLikeCompactWeekText(line)
            }.orEmpty()

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
                    // 代课/补课主行若无补课时间（仅停/代课侧有数据），则只用于 pending，
                    // 实际调整由续行（rowspan 拆分出的补课行）创建。
                    if (typeCell !in setOf("代课", "补课") || adj.makeupWeek > 0) {
                        results.add(adj)
                    }
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

    /** 将教务显示的节次号映射为内部节次号（桂林中午偏移+2，南宁直排） */
    private fun mapAdjustmentSection(section: Int, hasNoon: Boolean): Int {
        return if (hasNoon && section >= 5) section + 2 else section
    }

    private fun parseAdjustmentRow(cells: List<String>, type: String, title: String, teacher: String, hasNoon: Boolean): ScheduleAdjustment? {
        val makeupBase = cells.size - 5
        val cellsOrigWeek = cells.getOrNull(makeupBase - 4)
            ?.let { weekNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val cellsOrigDay = cells.getOrNull(makeupBase - 3)
            ?.let { parseWeekdayText(it) } ?: 0
        val (origStart, origEnd) = cells.getOrNull(makeupBase - 2)
            ?.let { parseSectionRange(it) } ?: Pair(0, 0)
        val cellsOrigStart = mapAdjustmentSection(origStart, hasNoon)
        val cellsOrigEnd = mapAdjustmentSection(origEnd, hasNoon)
        val cellsOrigRoom = cells.getOrNull(makeupBase - 1).orEmpty()
        val cellsMkWeek = cells.getOrNull(makeupBase + 1)
            ?.let { weekNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val cellsMkDay = cells.getOrNull(makeupBase + 2)
            ?.let { parseWeekdayText(it) } ?: 0
        val (mkStart, mkEnd) = cells.getOrNull(makeupBase + 3)
            ?.let { parseSectionRange(it) } ?: Pair(0, 0)
        val cellsMkStart = mapAdjustmentSection(mkStart, hasNoon)
        val cellsMkEnd = mapAdjustmentSection(mkEnd, hasNoon)
        val cellsMkRoom = cells.getOrNull(makeupBase + 4).orEmpty()

        // 代课：停/代课侧有数据但补课侧为空时，将停/代课时间作为 makeup（代课地点）
        // 补课：同理，补课侧有数据但停/代课侧为空时，直接作为 makeup
        val originalWeek: Int; val originalDay: Int; val originalStart: Int; val originalEnd: Int; val originalRoom: String
        val makeupWeek: Int; val makeupDay: Int; val makeupStart: Int; val makeupEnd: Int; val makeupRoom: String
        if (type in setOf("代课", "补课") && cellsMkWeek == 0 && cellsOrigWeek > 0) {
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
        val (firstStart, firstEnd) = cells.getOrNull(3)?.let { parseSectionRange(it) } ?: Pair(0, 0)
        val firstStartSection = mapAdjustmentSection(firstStart, hasNoon)
        val firstEndSection = mapAdjustmentSection(firstEnd, hasNoon)
        val firstRoom = cells.getOrNull(4).orEmpty()
        val secondWeek = cells.getOrNull(6)
            ?.let { weekNumberRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        val secondDay = cells.getOrNull(7)?.let { parseWeekdayText(it) } ?: 0
        val (secondStart, secondEnd) = cells.getOrNull(8)?.let { parseSectionRange(it) } ?: Pair(0, 0)
        val secondStartSection = mapAdjustmentSection(secondStart, hasNoon)
        val secondEndSection = mapAdjustmentSection(secondEnd, hasNoon)
        val secondRoom = cells.getOrNull(9).orEmpty()

        // 续行可能只包含补课数据（cells[0]-[4] 有效，cells[5]-[9] 全空）
        // 此时 first* 实际是补课时间，应将其移到 makeup 侧，original 侧清空
        val secondHasValidTime = secondWeek > 0 && secondDay > 0
        val firstHasValidTime = firstDateWeek > 0 && firstDay > 0
        val originalWeek: Int; val originalDay: Int; val originalStart: Int; val originalEnd: Int; val originalRoom: String
        val makeupWeek: Int; val makeupDay: Int; val makeupStart: Int; val makeupEnd: Int; val makeupRoom: String
        if (!secondHasValidTime && firstHasValidTime) {
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

    private fun applyAdjustmentRemovals(
        courses: List<ScheduleCourse>,
        adjustments: List<ScheduleAdjustment>
    ): List<ScheduleCourse> {
        if (adjustments.isEmpty()) return courses
        // 代课（替课）不取消原课，只增加补课；调课/停课才需要移除原周
        val removalAdjustments = adjustments.filter { it.type != "代课" }
        if (removalAdjustments.isEmpty()) return courses
        return courses.mapNotNull { course ->
            val updatedOccurrences = course.occurrences.flatMap { occurrence ->
                val adjustment = removalAdjustments.firstOrNull { it.matches(course, occurrence) }
                if (adjustment == null) {
                    listOf(occurrence)
                } else {
                    occurrence.withoutWeek(adjustment.originalWeek)
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
     */
    private fun isMakeupCoveredByGrid(
        gridCourses: List<ScheduleCourse>,
        title: String, dayOfWeek: Int, startSection: Int, endSection: Int,
        note: String, week: Int
    ): Boolean {
        if (week <= 0) return false
        return gridCourses.any { gc ->
            gc.title.trim() == title.trim() &&
            gc.occurrences.any { occ ->
                occ.dayOfWeek == dayOfWeek &&
                occ.startSection == startSection &&
                occ.endSection == endSection &&
                occ.note.trim() == note.trim() &&
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

        for (match in textBasedRegex.findAll(text)) {
            val title = match.groupValues[1].trim()
            val teacher = match.groupValues[2].trim().ifBlank { "待确认" }
            val timeText = match.groupValues[3]
            val id = "import-${stableId("text-$title-$teacher")}"
            val occurrences = parseArrangementOccurrences(id, timeText)

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
        text: String
    ): List<CourseOccurrence> {
        return arrangementTimeRegex.findAll(text)
            .mapIndexedNotNull { index, match ->
                val weekText = match.groupValues[1].trim().ifBlank { "全周" }
                val day = dayOfWeek(match.groupValues[2]) ?: return@mapIndexedNotNull null
                val start = match.groupValues[3].toIntOrNull() ?: return@mapIndexedNotNull null
                val end = match.groupValues[4].toIntOrNull() ?: start
                val room = match.groupValues[5].trim()

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
        val clean = value.removePrefix("@").trim()
        return Regex("""^\d{4,8}[A-Za-z]?$""").matches(clean) ||
            clean.contains("线上") ||
            clean.contains("馆") ||
            clean.contains("教室") ||
            clean.contains("楼") ||
            (clean.length in 4..10 && clean.any { it.isDigit() } && clean.any { it.isLetter() })
    }

    private fun looksLikeWeekText(value: String): Boolean {
        return looksLikeExplicitWeekText(value) ||
            looksLikeCompactWeekText(value)
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

    private fun weekTextWithoutWeek(weekText: String, removedWeek: Int): List<String> {
        val remainingWeeks = expandActiveWeeks(weekText)
            .filter { it != removedWeek }
        return compactWeekNumbers(remainingWeeks)
    }

    private fun expandActiveWeeks(weekText: String): List<Int> {
        val normalized = weekText
            .replace("第", "")
            .replace(" ", "")
            .replace("，", ",")
            .trim()
        val requiresOdd = normalized.contains("单周")
        val requiresEven = normalized.contains("双周")
        val spans = weekSpanRegex.findAll(normalized).mapNotNull { match ->
            val parts = match.value.split("-", "－", "—")
            val start = parts.getOrNull(0)?.toIntOrNull()
            val end = parts.getOrNull(1)?.toIntOrNull() ?: start
            if (start != null && end != null && start <= end) start..end else null
        }.toList()
        val baseWeeks = if (spans.isEmpty()) {
            (1..22).toList()
        } else {
            spans.flatMap { it.toList() }
        }
        return baseWeeks
            .map { it.coerceIn(1, 22) }
            .distinct()
            .sorted()
            .filter { week -> !requiresOdd || week % 2 == 1 }
            .filter { week -> !requiresEven || week % 2 == 0 }
    }

    private fun compactWeekNumbers(weeks: List<Int>): List<String> {
        if (weeks.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = weeks.first()
        var previous = start
        weeks.drop(1).forEach { week ->
            if (week == previous + 1) {
                previous = week
            } else {
                ranges += start..previous
                start = week
                previous = week
            }
        }
        ranges += start..previous
        return ranges.map { range ->
            if (range.first == range.last) {
                "第${range.first}周"
            } else {
                "${range.first}-${range.last}周"
            }
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

    private fun looksLikeClassHourType(value: String): Boolean {
        return value.contains("讲课学时") ||
            value.contains("实验学时") ||
            value.contains("上机学时") ||
            value.contains("实践学时")
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
        fun matches(course: ScheduleCourse, occurrence: CourseOccurrence): Boolean {
            return course.title.trim() == title.trim() &&
                course.teacher.trim() == teacher.trim() &&
                occurrence.dayOfWeek == originalDay &&
                occurrence.startSection == originalStartSection &&
                occurrence.endSection == originalEndSection &&
                occurrence.note.trim().ifBlank { course.room.trim() } == originalRoom.trim() &&
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
            if (n != null && hasNoon && n >= 5) n + 2 else n
        }
    }

    private companion object {
        val KNOWN_TYPES = setOf("调课", "补课", "停课", "代课")
        val cellRegex = Regex("""(?is)<td\b([^>]*)>(.*?)</td>""")
        val timetableTableRegex = Regex("""(?is)<table\b(?=[^>]*\bid\s*=\s*["']timetable["'])[^>]*>.*?</table>""")
        val rowRegex = Regex("""(?is)<tr\b[^>]*>.*?</tr>""")
        val tableCellRegex = Regex("""(?is)<t[dh]\b[^>]*>(.*?)</t[dh]>""")
        val tableCellWithAttrsRegex = Regex("""(?is)<t[dh]\b([^>]*)>(.*?)</t[dh]>""")
        val cellIdRegex = Regex("""\bid\s*=\s*["']([1-7])-\d+["']""")
        val glutCourseTitleRegex = Regex("""<<\s*(.+?)\s*>>""")
        val arrangementTimeRegex = Regex(
            """([第\d,，\-－—至单双周节、\s]*)星期([一二三四五六日天])\s*第\s*(\d{1,2})\s*(?:[、,，]|至|~|-|－|—)\s*(\d{1,2})\s*节\s*([^\s<]*)"""
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

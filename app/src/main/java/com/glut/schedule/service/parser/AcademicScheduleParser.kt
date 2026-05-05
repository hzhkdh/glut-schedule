package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import java.security.MessageDigest

interface AcademicScheduleParser {
    fun parsePersonalSchedule(html: String): List<ScheduleCourse>
}

class GlutAcademicScheduleParser : AcademicScheduleParser {
    override fun parsePersonalSchedule(html: String): List<ScheduleCourse> {
        require(html.isNotBlank()) { "课表 HTML 不能为空" }
        if (looksLikeNonTimetablePage(html)) return emptyList()

        val primary = (parseExplicitCells(html) + parseCourseArrangementRows(html))
            .distinctBy { it.id }
        if (primary.isNotEmpty()) return CourseColorMapper.assignColors(primary)

        val timetableGrid = parseGlutStudentTimetableGrid(html)
        if (timetableGrid.isNotEmpty()) return CourseColorMapper.assignColors(timetableGrid)

        val secondary = (parseGridTable(html) + parseSimpleTable(html))
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
            .ifBlank { "待确认" }

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
                    room = occurrences.firstOrNull()?.note?.ifBlank { "待确认" } ?: "待确认",
                    teacher = teacher,
                    colorHex = CourseColorMapper.colorForCourse(baseId, title),
                    occurrences = occurrences
                )
            )
        }.toList()
    }

    private fun parseGridTable(html: String): List<ScheduleCourse> {
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
            val sectionNumber = periodNumberRegex.find(periodCell)?.groupValues?.get(1)?.toIntOrNull()
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

    private fun parseGlutStudentTimetableGrid(html: String): List<ScheduleCourse> {
        val timetable = timetableTableRegex.find(html)?.value ?: return emptyList()
        val rows = rowRegex.findAll(timetable).drop(1).toList()
        if (rows.isEmpty()) return emptyList()

        val courses = mutableListOf<ScheduleCourse>()
        rows.forEach { rowMatch ->
            val cells = tableCellWithAttrsRegex.findAll(rowMatch.value).toList()
            if (cells.size < 2) return@forEach

            val periodText = htmlToLines(cells.first().groupValues[2]).joinToString(" ")
            val sectionNumber = periodNumberRegex.find(periodText)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
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

            val detailLines = lines.subList(titleIndex + 1, nextTitleIndex)
                .map { it.trim() }
                .filter { it.isNotBlank() && !looksLikeClassHourType(it) }
            val room = detailLines.firstOrNull { looksLikeRoom(it) }.orEmpty()
            val weekText = detailLines.firstOrNull { looksLikeWeekText(it) || looksLikeCompactWeekText(it) }.orEmpty()
            val teacher = detailLines.firstOrNull { line ->
                line != room &&
                    line != weekText &&
                    !looksLikeRoom(line) &&
                    !looksLikeWeekText(line) &&
                    !looksLikeCompactWeekText(line)
            }.orEmpty()

            val id = "import-${stableId("glut-grid-$title-$room-$teacher-$weekText")}"
            buildCourse(
                id = id,
                title = title,
                room = room.ifBlank { "待确认" },
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
                room = room.ifBlank { "待确认" },
                teacher = teacher.ifBlank { "待确认" },
                day = day,
                startSection = sectionNumber,
                endSection = sectionNumber,
                weekText = weekText
            )
        )

        return results
    }

    private fun mergeCourseOccurrences(courses: List<ScheduleCourse>): List<ScheduleCourse> {
        return courses.groupBy { it.id }.map { (_, group) ->
            val first = group.first()
            first.copy(occurrences = mergeAdjacentOccurrences(group.flatMap { it.occurrences }))
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
                        room = room.ifBlank { "待确认" },
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
                        room = occurrences.firstOrNull()?.note?.ifBlank { "待确认" } ?: "待确认",
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
                val room = match.groupValues[5].trim().ifBlank { "待确认" }

                CourseOccurrence(
                    id = "$courseId-occurrence-$index",
                    courseId = courseId,
                    dayOfWeek = day,
                    startSection = start.coerceIn(1, 12),
                    endSection = end.coerceIn(start, 12),
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
            room = room.ifBlank { "待确认" },
            teacher = teacher.ifBlank { "待确认" },
            colorHex = CourseColorMapper.colorForCourse(id, title),
            occurrences = listOf(
                CourseOccurrence(
                    id = "$id-occurrence",
                    courseId = id,
                    dayOfWeek = day.coerceIn(1, 7),
                    startSection = effectiveStart.coerceIn(1, 12),
                    endSection = effectiveEnd.coerceIn(effectiveStart, 12),
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
        return value.contains("周") || value.contains("单周") || value.contains("双周") ||
            Regex("""\d+.*\d*.*周""").containsMatchIn(value) ||
            looksLikeCompactWeekText(value)
    }

    private fun looksLikeCompactWeekText(value: String): Boolean {
        return Regex("""^\d{1,2}(?:-\d{1,2})?(?:\s*[,，]\s*\d{1,2}(?:-\d{1,2})?)*$""").matches(value.trim())
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

    private val dayNames = listOf("一", "二", "三", "四", "五", "六", "日")

    private companion object {
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
    }
}

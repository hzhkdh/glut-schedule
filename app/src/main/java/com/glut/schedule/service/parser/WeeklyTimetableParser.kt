package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class WeeklyTimetableRow(
    val date: String,
    val title: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val building: String,
    val room: String,
    val status: String
)

data class WeeklyTimetablePage(
    val semesterLabel: String,
    val selectedWeek: Int?,
    val availableWeeks: List<Int>,
    val rows: List<WeeklyTimetableRow>
)

fun WeeklyTimetablePage.contentMonday(): LocalDate? {
    val pageMondays = rows.map { row ->
        LocalDate.parse(row.date).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }.distinct()
    require(pageMondays.size <= 1)
    return pageMondays.singleOrNull()
}

fun WeeklyTimetablePage.validateFor(
    expectedWeek: Int,
    expectedSemesterLabel: String,
    expectedSemesterMonday: LocalDate?
): LocalDate? {
    require(semesterLabel == expectedSemesterLabel)
    require(selectedWeek == expectedWeek)
    val datedRows = rows.map { row -> LocalDate.parse(row.date) to row }
    datedRows.forEach { (date, row) -> require(date.dayOfWeek.value == row.dayOfWeek) }
    val pageMondays = datedRows.map { (date, _) ->
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    }.distinct()
    require(pageMondays.size <= 1)
    val inferred = pageMondays.singleOrNull()?.minusWeeks((expectedWeek - 1).toLong())
    require(expectedSemesterMonday == null || inferred == null || inferred == expectedSemesterMonday)
    return expectedSemesterMonday ?: inferred
}

class WeeklyTimetableParser {
    fun parsePage(html: String, hasNoon: Boolean): WeeklyTimetablePage {
        val document = Jsoup.parse(html)
        val semesterLabel = Regex("""(20\d{2})\s*([春秋])""")
            .find(document.body().text())
            ?.let { "${it.groupValues[1]}${it.groupValues[2]}" }
            .orEmpty()
        val weekSelect = document.selectFirst("select[name=whichWeek]")
        val availableWeeks = weekSelect?.select("option")
            ?.mapNotNull { it.attr("value").trim().toIntOrNull() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        val selectedWeek = weekSelect?.selectFirst("option[selected]")
            ?.attr("value")?.trim()?.toIntOrNull()
            ?: weekSelect?.`val`()?.trim()?.toIntOrNull()

        val headerRow = requireNotNull(document.select("tr").firstOrNull(::isCourseHeaderRow)) {
            "Missing weekly timetable course table"
        }
        val courseTable = requireNotNull(headerRow.closest("table")) {
            "Missing weekly timetable course table"
        }
        val tableRows = courseTable.select("tr")
            .filter { it.closest("table") === courseTable }
        val headerPosition = tableRows.indexOf(headerRow)
        val headers = headerRow.directCellTexts()
        val rows = tableRows.drop(headerPosition + 1)
            .filter { row -> row.directCellTexts().any { it.isNotBlank() } }
            .mapNotNull { row ->
                // 跳过无法解析为课程的行（如备注、统计行等），不中断整页解析
                runCatching { parseCourseRow(row, headers, hasNoon) }.getOrNull()
            }
        return WeeklyTimetablePage(semesterLabel, selectedWeek, availableWeeks, rows)
    }

    fun mergeWithMetadata(
        baseCourses: List<ScheduleCourse>,
        pages: List<WeeklyTimetablePage>
    ): List<ScheduleCourse> {
        val finalRows = pages.flatMap { page ->
            page.rows.filterNot { it.status == "停课" }.map { page.selectedWeek to it }
        }.filter { (week, row) -> week != null && row.dayOfWeek > 0 && row.startSection > 0 }
            .distinctBy { (week, row) ->
                "$week|${normalizeTitle(row.title)}|${row.dayOfWeek}|${row.startSection}|${row.endSection}|${row.room}"
            }
        val rowsByCourse = finalRows.groupBy { (_, row) ->
            "${normalizeTitle(row.title)}|${row.room.trim()}"
        }

        val scheduled = rowsByCourse.map { (_, entries) ->
            val sample = entries.first().second
            val titleCandidates = baseCourses.filter {
                normalizeTitle(it.title) == normalizeTitle(sample.title)
            }
            val metadata = titleCandidates.firstOrNull {
                it.room.trim() == sample.room.trim() ||
                    it.occurrences.any { occurrence -> occurrence.note.trim() == sample.room.trim() }
            } ?: titleCandidates.firstOrNull()
            val teacher = metadata?.teacher.orEmpty().ifBlank { "待确认" }
            val courseId = "weekly-${stableId("${sample.title}|$teacher|${sample.room}")}"
            val occurrences = entries.groupBy { (_, row) ->
                Triple(row.dayOfWeek, row.startSection, row.endSection)
            }.map { (slot, slotRows) ->
                val weeks = slotRows.mapNotNull { it.first }.distinct().sorted()
                CourseOccurrence(
                    id = "$courseId-${slot.first}-${slot.second}-${slot.third}",
                    courseId = courseId,
                    dayOfWeek = slot.first,
                    startSection = slot.second,
                    endSection = slot.third,
                    weekText = compactWeeks(weeks),
                    note = sample.room
                )
            }
            ScheduleCourse(
                id = courseId,
                title = sample.title,
                room = sample.room,
                teacher = teacher,
                colorHex = metadata?.colorHex ?: CourseColorMapper.colorForCourse(courseId, sample.title),
                occurrences = occurrences
            )
        }
        val scheduledTitles = scheduled.map { normalizeTitle(it.title) }.toSet()
        val unscheduled = baseCourses.filter { normalizeTitle(it.title) !in scheduledTitles }
            .map { it.copy(occurrences = emptyList()) }
        return scheduled + unscheduled
    }

    private fun isCourseHeaderRow(row: Element): Boolean {
        val headers = row.directCellTexts()
        return "日期" in headers && "课程名" in headers && "星期" in headers &&
            "节次" in headers && "开始时间" in headers && "结束时间" in headers &&
            "教学楼" in headers && "教室" in headers
    }

    private fun Element.directCellTexts(): List<String> = children()
        .filter { it.tagName() == "th" || it.tagName() == "td" }
        .map { it.text().trim() }

    private fun parseCourseRow(
        row: Element,
        headers: List<String>,
        hasNoon: Boolean
    ): WeeklyTimetableRow {
        val cells = row.children()
            .filter { it.tagName() == "td" }
            .map { it.text().replace('\u00A0', ' ').trim() }
        fun column(name: String): Int = headers.indexOfFirst { it == name }
        val dateIndex = column("日期")
        val titleIndex = column("课程名")
        val weekdayIndex = column("星期")
        val sectionIndex = column("节次")
        val buildingIndex = column("教学楼")
        val roomIndex = column("教室")
        require(listOf(dateIndex, titleIndex, weekdayIndex, sectionIndex, buildingIndex, roomIndex)
            .all { it in cells.indices }) { "Malformed weekly timetable course row" }
        val date = cells[dateIndex].trim()
        val title = cells[titleIndex].trim()
        val day = parseWeekday(cells[weekdayIndex])
        val sectionRange = parseSectionRange(cells[sectionIndex])
        require(title.isNotBlank()) { "Missing weekly timetable course title" }
        require(runCatching { LocalDate.parse(date) }.isSuccess) {
            "Invalid weekly timetable course date: $date"
        }
        require(day != 0) { "Invalid weekly timetable weekday: ${cells[weekdayIndex]}" }
        require(sectionRange != null) { "Invalid weekly timetable section: ${cells[sectionIndex]}" }
        val (rawStart, rawEnd) = sectionRange
        val maxRawSection = if (hasNoon) 12 else 11
        require(rawStart in 1..maxRawSection && rawEnd in 1..maxRawSection && rawStart <= rawEnd) {
            "Invalid weekly timetable section range: ${cells[sectionIndex]}"
        }
        val start = mapSection(rawStart, hasNoon)
        val end = mapSection(rawEnd, hasNoon)
        val validMappedRange = if (hasNoon) {
            (start in 1..4 || start in 7..14) && (end in 1..4 || end in 7..14)
        } else {
            start in 1..11 && end in 1..11
        }
        require(validMappedRange && start <= end) {
            "Invalid mapped weekly timetable section range: $start-$end"
        }
        val statusIndex = headers.indexOfFirst { it == "状态" || it == "类型" }
            .takeIf { it >= 0 }
            ?: (roomIndex + 1)
        return WeeklyTimetableRow(
            date = date,
            title = title,
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            building = cells[buildingIndex],
            room = cells[roomIndex].ifBlank { cells[buildingIndex] },
            status = cells.getOrNull(statusIndex).orEmpty()
        )
    }

    private fun parseWeekday(value: String): Int = when (value.replace(Regex("""\s+"""), "")) {
        "一", "周一", "星期一" -> 1
        "二", "周二", "星期二" -> 2
        "三", "周三", "星期三" -> 3
        "四", "周四", "星期四" -> 4
        "五", "周五", "星期五" -> 5
        "六", "周六", "星期六" -> 6
        "日", "天", "周日", "周天", "星期日", "星期天" -> 7
        else -> 0
    }

    private fun parseSectionRange(value: String): Pair<Int, Int>? {
        val match = Regex(
            """^第?\s*(\d{1,2})(?:\s*(?:、|,|，|-|－|~|～|至)\s*(\d{1,2}))?\s*节?$"""
        ).matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].toIntOrNull() ?: start
        return start to end
    }

    private fun mapSection(section: Int, hasNoon: Boolean): Int {
        return if (hasNoon && section >= 5) section + 2 else section
    }

    private fun compactWeeks(weeks: List<Int>): String {
        if (weeks.isEmpty()) return ""
        val ranges = mutableListOf<IntRange>()
        var start = weeks.first()
        var previous = start
        weeks.drop(1).forEach { week ->
            if (week == previous + 1) previous = week
            else {
                ranges += start..previous
                start = week
                previous = week
            }
        }
        ranges += start..previous
        return ranges.joinToString(",") { range ->
            if (range.first == range.last) "第${range.first}周" else "${range.first}-${range.last}周"
        }
    }

    private fun normalizeTitle(value: String): String = value.replace(Regex("""\s+"""), "").trim()

    // 统一使用 MD5 与 AcademicScheduleParser / NanningCurrcourseParser 保持
    // 哈希算法一致，确保跨解析器的课程 ID 可合并去重。
    private fun stableId(value: String): String {
        return MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
    }
}

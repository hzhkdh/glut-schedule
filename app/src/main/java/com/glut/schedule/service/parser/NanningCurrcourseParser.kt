package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import java.security.MessageDigest

class NanningCurrcourseParser : AcademicScheduleParser {

    override fun parsePersonalSchedule(html: String): List<ScheduleCourse> {
        if (html.isBlank()) return emptyList()
        if (!html.contains("infolist_common")) return emptyList()

        // Step 1: Extract all <table class="none">...</table> blocks
        val nestedTables = mutableListOf<String>()
        val cleanedHtml = nestedTableRegex.replace(html) { match ->
            nestedTables.add(match.value)
            "<!--NESTED_${nestedTables.lastIndex}-->"
        }

        // Step 2: Parse infolist_common rows in the cleaned HTML
        val rowMatches = infolistRowRegex.findAll(cleanedHtml).toList()
        if (rowMatches.isEmpty()) return emptyList()

        val courses = mutableListOf<ScheduleCourse>()

        for (rowMatch in rowMatches) {
            val rowHtml = rowMatch.value

            // Extract course name from <a class="infolist">
            val title = courseNameRegex.find(rowHtml)?.groupValues?.get(1)?.trim()
                ?: continue

            // Extract teacher from <a href='...teacherinfo...'>
            val teacher = teacherRegex.find(rowHtml)?.groupValues?.get(1)?.trim()
                ?: "待确认"

            // Find nested table placeholder
            val placeholderMatch = nestedPlaceholderRegex.find(rowHtml)
            if (placeholderMatch == null) continue // MOOC course, no time/location

            val tableIdx = placeholderMatch.groupValues[1].toIntOrNull() ?: continue
            if (tableIdx !in nestedTables.indices) continue

            // Step 3: Parse nested table.none rows
            val nestedHtml = nestedTables[tableIdx]
            val timeRows = tableRowRegex.findAll(nestedHtml).toList()

            val occurrences = mutableListOf<CourseOccurrence>()
            val id = "import-nn-${stableId("$title-$teacher")}"

            for ((occIdx, timeRow) in timeRows.withIndex()) {
                val cells = tableCellRegex.findAll(timeRow.value)
                    .map { it.groupValues[1].trim() }
                    .map { htmlToPlainText(it) }
                    .toList()
                if (cells.size < 4) continue

                val weekText = cells[0]    // "1-18周" or "11-18周单"
                val dayOfWeek = parseWeekday(cells[1]) // "星期一" -> 1
                if (dayOfWeek == 0) continue

                val (startSection, endSection) = parsePeriodRange(cells[2]) // "第5-6节" -> (5,6)
                if (startSection == 0) continue

                val room = cells[3].takeUnless {
                    it == "&nbsp;" || it.isBlank()
                }.orEmpty()

                occurrences.add(
                    CourseOccurrence(
                        id = "$id-occurrence-$occIdx",
                        courseId = id,
                        dayOfWeek = dayOfWeek.coerceIn(1, 7),
                        startSection = startSection.coerceIn(1, 11),
                        endSection = endSection.coerceIn(startSection, 11),
                        weekText = weekText,
                        note = room
                    )
                )
            }

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

        return CourseColorMapper.assignColors(courses)
    }

    // ---- Helpers ----

    private fun parseWeekday(text: String): Int = when {
        text.contains("一") -> 1
        text.contains("二") -> 2
        text.contains("三") -> 3
        text.contains("四") -> 4
        text.contains("五") -> 5
        text.contains("六") -> 6
        text.contains("日") || text.contains("天") -> 7
        else -> 0
    }

    private fun parsePeriodRange(text: String): Pair<Int, Int> {
        val clean = text.replace("第", "").replace("节", "").trim()
        val parts = clean.split("-", "－", "—")
        val start = parts.getOrNull(0)?.toIntOrNull() ?: return 0 to 0
        val end = parts.getOrNull(1)?.toIntOrNull() ?: start
        return start to end
    }

    private fun htmlToPlainText(html: String): String {
        return html
            .replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim()
    }

    private fun stableId(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    // ---- Regex patterns ----

    private companion object {
        val nestedTableRegex = Regex(
            """(?is)<table\b[^>]*class\s*=\s*["']none["'][^>]*>.*?</table>"""
        )
        val infolistRowRegex = Regex(
            """(?is)<tr\b[^>]*class\s*=\s*["']infolist_common["'][^>]*>.*?</tr>"""
        )
        val nestedPlaceholderRegex = Regex("""<!--NESTED_(\d+)-->""")
        val courseNameRegex = Regex(
            """(?is)<a\b[^>]*class\s*=\s*["']infolist["'][^>]*>\s*([^<]+?)\s*</a>"""
        )
        val teacherRegex = Regex(
            """(?is)<a\b[^>]*teacherinfo[^>]*>\s*([^<]+?)\s*</a>"""
        )
        val tableRowRegex = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
        val tableCellRegex = Regex("""(?is)<t[dh]\b[^>]*>(.*?)</t[dh]>""")
    }
}

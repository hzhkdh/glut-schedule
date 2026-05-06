package com.glut.schedule.service.academic

import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import java.security.MessageDigest
import java.time.LocalDate

object AcademicTodayPlanParser {
    fun parse(json: String, currentWeekNumber: Int): List<ScheduleCourse> {
        val data = dataArrayRegex.find(json)?.groupValues?.get(1) ?: return emptyList()
        val courses = mutableListOf<ScheduleCourse>()
        objectRegex.findAll(data).forEach { match ->
            val item = match.groupValues[1]
            val title = readJsonString(item, "name").trim()
            if (title.isBlank()) return@forEach

            val sectionRange = parseSectionRange(readJsonString(item, "time")) ?: return@forEach
            val date = readJsonString(item, "arrangeDate")
                .take(10)
                .let { value -> runCatching { LocalDate.parse(value) }.getOrNull() }
                ?: return@forEach
            val room = readJsonString(item, "roomName")
                .ifBlank { readJsonString(item, "resBuildingName") }
                .ifBlank { "待确认" }
            val id = "today-${stableId("$title-$room-$date-${sectionRange.first}-${sectionRange.second}")}"
            val weekText = "第${currentWeekNumber.coerceIn(1, 22)}周"

            courses += ScheduleCourse(
                id = id,
                title = title,
                room = room,
                teacher = "待确认",
                colorHex = CourseColorMapper.colorForCourse(id, title),
                occurrences = listOf(
                    CourseOccurrence(
                        id = "$id-occurrence",
                        courseId = id,
                        dayOfWeek = date.dayOfWeek.value,
                        startSection = sectionRange.first.coerceIn(1, 12),
                        endSection = sectionRange.second.coerceIn(sectionRange.first, 12),
                        weekText = weekText,
                        note = room
                    )
                )
            )
        }

        return CourseColorMapper.assignColors(courses)
    }

    private fun readJsonString(objectBody: String, key: String): String {
        val pattern = Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""")
        return pattern.find(objectBody)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\/", "/")
            ?.replace("\\n", "\n")
            ?.replace("\\t", "\t")
            .orEmpty()
    }

    private fun parseSectionRange(value: String): Pair<Int, Int>? {
        val match = sectionRangeRegex.find(value) ?: return null
        val start = match.groupValues[1].toIntOrNull() ?: return null
        val end = match.groupValues[2].toIntOrNull() ?: start
        return start to end
    }

    private fun stableId(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private val sectionRangeRegex = Regex("""第\s*(\d{1,2})\s*(?:[、,，]|至|~|-|－|—)\s*(\d{1,2})\s*节""")
    private val dataArrayRegex = Regex(""""data"\s*:\s*\[(.*)]""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val objectRegex = Regex("""\{(.*?)\}""", setOf(RegexOption.DOT_MATCHES_ALL))
}

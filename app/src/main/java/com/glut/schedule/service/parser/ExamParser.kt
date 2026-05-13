package com.glut.schedule.service.parser

import com.glut.schedule.data.model.ExamInfo
import java.time.LocalDate
import java.time.format.DateTimeFormatter

interface ExamParser {
    fun parseExamJson(jsonStr: String): List<ExamInfo>
    fun parseExamHtml(html: String): List<ExamInfo>
}

class GlutExamParser : ExamParser {

    override fun parseExamJson(jsonStr: String): List<ExamInfo> {
        val trimmed = jsonStr.trim()
        if (trimmed.isBlank()) return emptyList()

        val rawItems = extractJsonArray(trimmed)
            ?: extractNestedArray(trimmed)
            ?: return emptyList()

        return rawItems.mapNotNull { item -> parseExamJsonObject(item) }
            .filter { it.courseName.isNotBlank() && it.location.isNotBlank() }
    }

    override fun parseExamHtml(html: String): List<ExamInfo> {
        if (html.isBlank()) return emptyList()

        val tables = tableRegex.findAll(html).map { it.value }.toList()
        for (tableHtml in tables) {
            val rows = tableRowRegex.findAll(tableHtml).toList()
            if (rows.size < 2) continue

            val headerRow = rows.first().value
            val headers = cellRegex.findAll(headerRow).map {
                it.value.replace(Regex("<[^>]+>"), "").trim()
            }.toList()
            if (headers.size < 3) continue
            if (headers.none { header -> isExamHeader(header) }) continue

            val colIndex = mapExamColumns(headers)
            if (colIndex.isEmpty()) continue

            val exams = rows.drop(1).mapNotNull { row ->
                val cells = cellRegex.findAll(row.value).map {
                    it.value.replace(Regex("<[^>]+>"), "").trim()
                }.toList()
                if (cells.size < 3) return@mapNotNull null
                parseExamRow(cells, colIndex)
            }
            if (exams.isNotEmpty()) return exams
        }
        return emptyList()
    }

    private fun parseExamJsonObject(obj: String): ExamInfo? {
        val courseName = extractJsonValue(obj, "courseName", "kcmc", "name", "kc")
        val examDateStr = extractJsonValue(obj, "examDate", "ksrq", "date")
        val examTimeStr = extractJsonValue(obj, "examTime", "kssj", "time")
        val location = extractJsonValue(obj, "examRoom", "ksdd", "jsmc", "room", "location")
        val seatNumber = extractJsonValue(obj, "seatNumber", "zwh", "seat")
        val examType = extractJsonValue(obj, "examType", "kslx", "type")
        val note = extractJsonValue(obj, "note", "remark", "memo", "bz")

        if (courseName.isNullOrBlank() || location.isNullOrBlank()) return null

        val examDate = parseDate(examDateStr.orEmpty())
        val (startTime, endTime) = parseTimeRange(examTimeStr.orEmpty())

        return ExamInfo(
            id = ExamInfo.stableId(courseName, examDate, startTime, endTime, location),
            courseName = courseName.trim(),
            examDate = examDate,
            startTime = startTime,
            endTime = endTime,
            location = location.trim(),
            seatNumber = seatNumber?.trim().orEmpty(),
            examType = examType?.trim().orEmpty(),
            note = note?.trim().orEmpty()
        )
    }

    private fun parseExamRow(cells: List<String>, colIndex: Map<String, Int>): ExamInfo? {
        fun cell(key: String) = colIndex[key]?.let { cells.getOrNull(it) }?.trim().orEmpty()

        val courseName = cell("courseName")
        val location = cell("location")
        if (courseName.isBlank() || location.isBlank()) return null

        val examDate = parseDate(cell("examDate"))
        val (startTime, endTime) = parseTimeRange(cell("examTime"))

        return ExamInfo(
            id = ExamInfo.stableId(courseName, examDate, startTime, endTime, location),
            courseName = courseName,
            examDate = examDate,
            startTime = startTime,
            endTime = endTime,
            location = location,
            seatNumber = cell("seatNumber"),
            examType = cell("examType"),
            note = cell("note")
        )
    }

    private fun parseDate(raw: String): LocalDate {
        // Extract date part: "2026-05-25 16:20--18:00" → "2026-05-25"
        val dateOnly = raw.trim()
            .replace(Regex("""\s.*"""), "")
            .replace(Regex("[年月]"), "-")
            .replace(Regex("[日号]"), "")
        val patterns = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("yyyy-M-d"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("MM-dd"),
            DateTimeFormatter.ofPattern("M月d日")
        )
        for (pattern in patterns) {
            runCatching {
                val parsed = LocalDate.parse(dateOnly, pattern)
                if (parsed.year < 2000) return parsed.withYear(LocalDate.now().year)
                return parsed
            }
        }
        return LocalDate.now()
    }

    private fun parseTimeRange(raw: String): Pair<String, String> {
        val cleaned = raw.trim().replace(Regex("""\s+"""), "")
        // Handle "--", "—", "~", "至", "到" separators (single or double dash)
        val rangeMatch = Regex("""(\d{1,2}:\d{2})\s*[-~—至到]+\s*(\d{1,2}:\d{2})""").find(cleaned)
        if (rangeMatch != null) {
            return rangeMatch.groupValues[1] to rangeMatch.groupValues[2]
        }
        // Also try to find time in datetime strings like "2026-05-25 16:20"
        val datetimeMatch = Regex("""\d{4}[-\/]\d{1,2}[-\/]\d{1,2}\s+(\d{1,2}:\d{2})""").find(raw.trim())
        if (datetimeMatch != null) {
            return datetimeMatch.groupValues[1] to ""
        }
        val single = Regex("""(\d{1,2}:\d{2})""").find(cleaned)
        if (single != null) {
            return single.groupValues[1] to ""
        }
        return "" to ""
    }

    private fun extractJsonValue(json: String, vararg keys: String): String? {
        for (key in keys) {
            val escapedKey = Regex.escape(key)
            val match = Regex(""""$escapedKey"\s*:\s*"([^"]*)"""").find(json)
                ?: Regex(""""$escapedKey"\s*:\s*(\d+)""").find(json)
                ?: Regex(""""$escapedKey"\s*:\s*(null)""").find(json)
            match?.let { m ->
                val value = m.groupValues[1]
                if (value != "null") return value
                return ""
            }
        }
        return null
    }

    private fun extractJsonArray(json: String): List<String>? {
        val arrayMatch = Regex("""\[([\s\S]*)\]""").find(json) ?: return null
        return splitJsonObjects(arrayMatch.groupValues[1]).takeIf { it.isNotEmpty() }
    }

    private fun extractNestedArray(json: String): List<String>? {
        val arrayPatterns = listOf(
            """"data"\s*:\s*\[([\s\S]*?)\]\s*\}""",
            """"result"\s*:\s*\[([\s\S]*?)\]\s*\}""",
            """"list"\s*:\s*\[([\s\S]*?)\]\s*\}""",
            """"rows"\s*:\s*\[([\s\S]*?)\]\s*\}""",
            """"records"\s*:\s*\[([\s\S]*?)\]\s*\}"""
        )
        for (pattern in arrayPatterns) {
            val match = Regex(pattern).find(json) ?: continue
            val objects = splitJsonObjects(match.groupValues[1])
            if (objects.isNotEmpty()) return objects
        }
        return null
    }

    private fun splitJsonObjects(json: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escapeNext = false
        for ((i, ch) in json.withIndex()) {
            if (escapeNext) { escapeNext = false; continue }
            if (ch == '\\') { escapeNext = true; continue }
            if (ch == '"') { inString = !inString; continue }
            if (inString) continue
            when (ch) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> {
                    depth--
                    if (depth == 0 && start >= 0) {
                        result.add(json.substring(start, i + 1))
                        start = -1
                    }
                }
            }
        }
        return result
    }

    private fun isExamHeader(text: String): Boolean {
        val keywords = listOf("课程", "考试", "日期", "时间", "地点", "教室", "座位", "考核")
        return keywords.any { text.contains(it) }
    }

    private fun mapExamColumns(headers: List<String>): Map<String, Int> {
        val mapping = mutableMapOf<String, Int>()
        headers.forEachIndexed { index, header ->
            when {
                header.contains("课程名称") || header.contains("科目") ->
                    mapping["courseName"] = index
                header.contains("课程") || header.contains("名称") ->
                    mapping["courseName"] = index
                header.contains("日期") || header.contains("时间") -> {
                    if (header.contains("考试时间") || header.contains("考试日期")) {
                        mapping["examDate"] = index
                        mapping["examTime"] = index
                    } else if (header.contains("日期")) {
                        mapping["examDate"] = index
                    } else {
                        mapping["examTime"] = index
                    }
                }
                header.contains("地点") || header.contains("教室") || header.contains("考场") ->
                    mapping["location"] = index
                header.contains("座位") -> mapping["seatNumber"] = index
                header.contains("类型") || header.contains("性质") || header.contains("考核") ->
                    mapping["examType"] = index
                header.contains("备注") -> mapping["note"] = index
            }
        }
        if (!mapping.containsKey("examTime")) {
            val timeIdx = headers.indexOfFirst { it.contains("时间") && !it.contains("日期") }
            if (timeIdx >= 0) mapping["examTime"] = timeIdx
        }
        return mapping
    }

    private companion object {
        private val tableRowRegex = Regex("""<tr[^>]*>([\s\S]*?)</tr>""", RegexOption.IGNORE_CASE)
        private val cellRegex = Regex("""<t[dh][^>]*>([\s\S]*?)</t[dh]>""", RegexOption.IGNORE_CASE)
        private val tableRegex = Regex("""<table[^>]*>([\s\S]*?)</table>""", RegexOption.IGNORE_CASE)
    }
}

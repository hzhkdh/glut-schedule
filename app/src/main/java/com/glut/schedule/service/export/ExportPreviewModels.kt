package com.glut.schedule.service.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 展示实际 CSV 的前几行；支持逗号、双引号和引号内换行，不重新拼一份假数据。 */
internal fun csvPreviewRows(content: String, limit: Int = 7): List<List<String>> {
    if (limit <= 0 || content.isBlank()) return emptyList()
    val rows = mutableListOf<List<String>>()
    val row = mutableListOf<String>()
    val value = StringBuilder()
    var quoted = false
    var index = if (content.startsWith('\uFEFF')) 1 else 0
    while (index < content.length && rows.size < limit) {
        val char = content[index]
        when {
            char == '"' -> {
                if (quoted && content.getOrNull(index + 1) == '"') { value.append('"'); index++ }
                else quoted = !quoted
            }
            char == ',' && !quoted -> { row += value.toString(); value.clear() }
            char == '\n' && !quoted -> {
                row += value.toString(); value.clear()
                rows += row.toList(); row.clear()
            }
            char != '\r' || quoted -> value.append(char)
        }
        index++
    }
    if (rows.size < limit && (value.isNotEmpty() || row.isNotEmpty())) {
        row += value.toString(); rows += row.toList()
    }
    return rows
}

internal data class CalendarPreviewEvent(val title: String, val time: String, val location: String)

/** 日历预览解析已生成的事件，避免只展示计数却看不到实际导出内容。 */
internal fun calendarPreviewEvents(content: String, limit: Int = 3, zone: ZoneId = ZoneId.systemDefault()): List<CalendarPreviewEvent> {
    if (limit <= 0) return emptyList()
    val lines = content.replace(Regex("\r?\n[ \t]"), "").lineSequence()
    val events = mutableListOf<CalendarPreviewEvent>()
    var title = ""
    var time = ""
    var location = ""
    val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneId.of("UTC"))
    val display = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(zone)
    for (line in lines) {
        when {
            line == "BEGIN:VEVENT" -> { title = ""; time = ""; location = "" }
            line.startsWith("SUMMARY:") -> title = unescapeCalendarPreview(line.substringAfter(':'))
            line.startsWith("LOCATION:") -> location = unescapeCalendarPreview(line.substringAfter(':'))
            line.startsWith("DTSTART:") -> time = runCatching {
                display.format(Instant.from(utc.parse(line.substringAfter(':'))))
            }.getOrDefault("时间请在日历应用中查看")
            line == "END:VEVENT" -> {
                if (title.isNotBlank()) events += CalendarPreviewEvent(title, time, location)
                if (events.size >= limit) break
            }
        }
    }
    return events
}

private fun unescapeCalendarPreview(value: String): String {
    val result = StringBuilder()
    var index = 0
    while (index < value.length) {
        if (value[index] == '\\' && index + 1 < value.length) {
            index++
            result.append(if (value[index] == 'n' || value[index] == 'N') '\n' else value[index])
        } else result.append(value[index])
        index++
    }
    return result.toString()
}

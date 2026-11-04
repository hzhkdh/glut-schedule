package com.glut.schedule.service.export

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.adjustmentMarkerFor
import com.glut.schedule.data.model.hasConfiguredClassTimes
import com.glut.schedule.data.model.isStoppedWeek
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.model.sectionSpanLabel
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 课表 → iCalendar（RFC 5545）。
 *
 * 只做「把已经算好的每周课次写进事件」这一件事：
 * - 周次展开、调休副本由调用方的 `courseBlocksByWeek` 负责，保证与屏幕同源；
 * - 停课周由 `isStoppedWeek` 排除，日历里不该出现一节已经停掉的课；
 * - 时间一律按 `Asia/Shanghai` 计算后写成 UTC，免去 VTIMEZONE 的兼容问题。
 */
sealed class ScheduleIcsResult {
    data class Success(val content: String, val eventCount: Int) : ScheduleIcsResult()

    /** 作息没有配置钟点（研究生默认如此）。没有时间就无法生成正确的日历事件。 */
    data object MissingClassTimes : ScheduleIcsResult()

    data object NoEvents : ScheduleIcsResult()
}

private val icsUtcFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private val campuZone: ZoneId = ZoneId.of("Asia/Shanghai")
private const val ICS_UID_DOMAIN = "glut-schedule"

fun buildScheduleIcs(
    calendarName: String,
    blocksByWeek: Map<Int, List<CourseBlock>>,
    periods: List<ClassPeriod>,
    semesterStartMonday: java.time.LocalDate,
    maxWeek: Int,
    adjustments: List<SemesterAdjustment> = emptyList(),
    hasNoonSections: Boolean = true,
    includeRoom: Boolean = true,
    includeTeacher: Boolean = true,
    generatedAt: Instant = Instant.now()
): ScheduleIcsResult {
    if (!hasConfiguredClassTimes(periods)) return ScheduleIcsResult.MissingClassTimes

    val periodBySection = periods.associateBy { it.section }
    val stamp = icsUtcFormatter.format(generatedAt)
    val builder = StringBuilder()
    val appendLine = { line: String -> builder.append(foldIcsLine(line)).append("\r\n") }

    appendLine("BEGIN:VCALENDAR")
    appendLine("VERSION:2.0")
    appendLine("PRODID:-//GuilinTechSchedule//Schedule Export//CN")
    appendLine("CALSCALE:GREGORIAN")
    appendLine("METHOD:PUBLISH")
    appendLine("X-WR-CALNAME:${escapeIcsText(calendarName)}")
    appendLine("X-WR-TIMEZONE:Asia/Shanghai")

    var eventCount = 0
    for (week in 1..maxWeek) {
        val weekInfo = scheduleWeekForNumber(week, semesterStartMonday, maxWeek)
        blocksByWeek[week].orEmpty().forEach { block ->
            val occurrence = block.occurrence
            if (occurrence.dayOfWeek !in 1..7) return@forEach
            if (isStoppedWeek(occurrence, block.course.title, week, adjustments)) return@forEach

            val startPeriod = periodBySection[occurrence.startSection] ?: return@forEach
            val endPeriod = periodBySection[occurrence.endSection] ?: return@forEach
            val startTime = parsePeriodTime(startPeriod.startsAt) ?: return@forEach
            val endTime = parsePeriodTime(endPeriod.endsAt) ?: return@forEach

            val date = weekInfo.dateFor(occurrence.dayOfWeek)
            val start = LocalDateTime.of(date, startTime).atZone(campuZone).toInstant()
            val end = LocalDateTime.of(date, endTime).atZone(campuZone).toInstant()
            if (!end.isAfter(start)) return@forEach

            val marker = adjustmentMarkerFor(
                occurrence = occurrence,
                courseTitle = block.course.title,
                weekNumber = week,
                adjustments = adjustments
            )
            val summary = if (marker == null) {
                block.course.title
            } else {
                "${block.course.title} [$marker]"
            }
            val description = buildList {
                if (includeTeacher && block.course.teacher.isNotBlank()) {
                    add("教师：${block.course.teacher}")
                }
                add("节次：${sectionSpanLabel(occurrence.startSection, occurrence.endSection, hasNoonSections)}")
                add("周次：第${week}周")
                if (marker != null) add("教务标记：$marker")
            }.joinToString("\n")

            appendLine("BEGIN:VEVENT")
            appendLine("UID:${eventUid(block, date.toString(), week, start)}")
            appendLine("DTSTAMP:$stamp")
            appendLine("DTSTART:${icsUtcFormatter.format(start)}")
            appendLine("DTEND:${icsUtcFormatter.format(end)}")
            appendLine("SUMMARY:${escapeIcsText(summary)}")
            // 课次上的 note 才是这门课当次的教室（教务允许同一门课分教室），course.room 只是兜底。
            val room = occurrence.note.ifBlank { block.course.room }
            if (includeRoom && room.isNotBlank()) {
                appendLine("LOCATION:${escapeIcsText(room)}")
            }
            if (description.isNotBlank()) {
                appendLine("DESCRIPTION:${escapeIcsText(description)}")
            }
            appendLine("END:VEVENT")
            eventCount++
        }
    }

    appendLine("END:VCALENDAR")
    return if (eventCount == 0) {
        ScheduleIcsResult.NoEvents
    } else {
        ScheduleIcsResult.Success(builder.toString(), eventCount)
    }
}

private fun parsePeriodTime(raw: String): LocalTime? =
    runCatching { LocalTime.parse(raw.trim()) }.getOrNull()

/**
 * RFC 5545 文本转义：反斜杠必须最先处理，否则后面替换出来的反斜杠会被二次转义。
 */
internal fun escapeIcsText(value: String): String = value
    .replace("\\", "\\\\")
    .replace(";", "\\;")
    .replace(",", "\\,")
    .replace("\r\n", "\\n")
    .replace("\n", "\\n")
    .replace("\r", "\\n")

/**
 * 按 75 个八位组折行。折行位置必须落在完整码点上，否则中文会被劈成半个字符。
 * 续行以一个空格开头，这个空格本身也计入 75 字节。
 */
internal fun foldIcsLine(line: String): String {
    if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
    val builder = StringBuilder()
    var bytesOnLine = 0
    var firstLine = true
    var index = 0
    while (index < line.length) {
        val codePoint = line.codePointAt(index)
        val charCount = Character.charCount(codePoint)
        val piece = line.substring(index, index + charCount)
        val pieceBytes = piece.toByteArray(Charsets.UTF_8).size
        val limit = if (firstLine) 75 else 74
        if (bytesOnLine + pieceBytes > limit) {
            builder.append("\r\n ")
            firstLine = false
            bytesOnLine = 0
        }
        builder.append(piece)
        bytesOnLine += pieceBytes
        index += charCount
    }
    return builder.toString()
}

private fun eventUid(
    block: CourseBlock,
    date: String,
    week: Int,
    start: Instant
): String {
    val raw = listOf(
        block.course.title,
        block.course.teacher,
        block.occurrence.note.ifBlank { block.course.room },
        date,
        week.toString(),
        block.occurrence.startSection.toString(),
        block.occurrence.endSection.toString(),
        start.epochSecond.toString()
    ).joinToString("|")
    val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { byte -> "%02x".format(byte) } + "@$ICS_UID_DOMAIN"
}

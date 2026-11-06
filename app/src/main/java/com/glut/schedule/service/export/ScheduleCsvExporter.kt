package com.glut.schedule.service.export

import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.sectionSpanLabel

/**
 * 课表 → CSV 表格（Excel / WPS 可直接打开）。
 *
 * 一条课次一行，周次保留教务原始写法（如 `1-16周`、`3-15单周`）。
 * 调休副本由渲染层生成，所以这里从「每周区块」取数并去重——与屏幕、日历同源。
 */
sealed class ScheduleCsvResult {
    data class Success(val content: String, val rowCount: Int) : ScheduleCsvResult()
    data object NoRows : ScheduleCsvResult()
}

private const val CSV_BOM = '\uFEFF'

fun buildScheduleCsv(
    blocksByWeek: Map<Int, List<CourseBlock>>,
    hasNoonSections: Boolean = true,
    includeRoom: Boolean = true,
    includeTeacher: Boolean = true
): ScheduleCsvResult {
    val rows = blocksByWeek.values
        .asSequence()
        .flatten()
        // 常规课次在每一周都会出现，副本只出现在目标周；按课次 id 去重后各留一条。
        .distinctBy { block -> block.occurrence.id }
        .filter { block -> block.occurrence.dayOfWeek in 1..7 }
        .sortedWith(
            compareBy<CourseBlock> { it.occurrence.dayOfWeek }
                .thenBy { it.occurrence.startSection }
                .thenBy { it.occurrence.endSection }
                .thenBy { it.course.title }
        )
        .toList()
    if (rows.isEmpty()) return ScheduleCsvResult.NoRows

    val header = buildList {
        add("课程")
        add("星期")
        add("节次")
        add("周次")
        if (includeRoom) add("教室")
        if (includeTeacher) add("教师")
    }
    val builder = StringBuilder()
    builder.append(CSV_BOM)
    builder.append(header.joinToString(",") { escapeCsvField(it) }).append("\r\n")
    rows.forEach { block ->
        val occurrence = block.occurrence
        val room = occurrence.note.ifBlank { block.course.room }
        val fields = buildList {
            add(block.course.title)
            add(weekdayLabel(occurrence.dayOfWeek))
            add(csvSectionLabel(sectionSpanLabel(occurrence.startSection, occurrence.endSection, hasNoonSections)))
            add(occurrence.weekText.ifBlank { "全周" })
            if (includeRoom) add(room)
            if (includeTeacher) add(block.course.teacher)
        }
        builder.append(fields.joinToString(",") { escapeCsvField(it) }).append("\r\n")
    }
    return ScheduleCsvResult.Success(builder.toString(), rows.size)
}

/**
 * 「3-4」在 Excel 里会被自动识别成日期（3月4日）。
 * CSV 无法声明单元格类型，只能把纯数字区间包成「第 3-4 节」；含「午1/午2」的写法本身就是文本，保持不变。
 */
internal fun csvSectionLabel(span: String): String {
    val isNumericSpan = span.isNotEmpty() && span.all { it.isDigit() || it == '-' }
    return if (isNumericSpan) "第${span}节" else span
}

private fun weekdayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    7 -> "周日"
    else -> "周$dayOfWeek"
}

/** RFC 4180：含逗号、引号或换行的字段整体加引号，内部引号写两遍。 */
internal fun escapeCsvField(value: String): String {
    if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

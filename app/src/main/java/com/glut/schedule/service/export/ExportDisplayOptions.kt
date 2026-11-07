package com.glut.schedule.service.export

import com.glut.schedule.data.model.CourseBlock

/** 仅供图片、PDF 使用的显示偏好；null 表示尚未初始化，按完整学期自动判断。 */
data class ExportDisplayOptions(val showNoon: Boolean? = null, val showWeekend: Boolean? = null)

/** 使用全部周次（包括调休副本），避免当前周没课就把其他周的课程裁掉。 */
fun resolveExportDisplayOptions(
    saved: ExportDisplayOptions,
    blocksByWeek: Map<Int, List<CourseBlock>>,
    hasNoonSections: Boolean
): ExportDisplayOptions {
    val occurrences = blocksByWeek.values.asSequence().flatten().map { it.occurrence }.toList()
    return ExportDisplayOptions(
        showNoon = hasNoonSections && (saved.showNoon ?: occurrences.any {
            it.startSection <= 6 && it.endSection >= 5
        }),
        showWeekend = saved.showWeekend ?: occurrences.any { it.dayOfWeek in 6..7 }
    )
}

/** 当前学期默认真实本周；历史学期默认第一个有课周，不沿用主页浏览周次。 */
fun initialExportWeek(
    isHistorical: Boolean,
    currentWeekNumber: Int,
    maxWeek: Int,
    blocksByWeek: Map<Int, List<CourseBlock>>
): Int {
    val limit = maxWeek.coerceAtLeast(1)
    return if (isHistorical) {
        blocksByWeek.filter { (week, blocks) -> week in 1..limit && blocks.isNotEmpty() }.keys.minOrNull() ?: 1
    } else currentWeekNumber.coerceIn(1, limit)
}

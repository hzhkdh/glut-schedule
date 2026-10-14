package com.glut.schedule.data.model

import com.glut.schedule.data.settings.CampusType

/** 课表内容推导出的网格显示状态，分别控制周末列和中午两行。 */
data class AutomaticScheduleDisplay(
    val showWeekend: Boolean = false,
    val showNoon: Boolean = false
)

/**
 * 按课程的完整课次判断是否需要展开被默认收起的区域。
 *
 * 桂林的内部第 5、6 节才是中午；南宁和研究生虽然也可能有第 5、6 节，
 * 但它们采用直排节次，不能因此误显示中午两行。
 */
fun automaticScheduleDisplay(
    courses: List<ScheduleCourse>,
    campus: CampusType
): AutomaticScheduleDisplay {
    val occurrences = courses.asSequence().flatMap { it.occurrences.asSequence() }
    var showWeekend = false
    var showNoon = false
    for (occurrence in occurrences) {
        if (occurrence.dayOfWeek in 6..7) showWeekend = true
        if (campus == CampusType.GUILIN && occurrence.startSection <= 6 && occurrence.endSection >= 5) {
            showNoon = true
        }
        if (showWeekend && showNoon) break
    }
    return AutomaticScheduleDisplay(showWeekend = showWeekend, showNoon = showNoon)
}

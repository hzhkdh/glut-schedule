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

/** 需要重新播种自动显示时的结果；内容签名与上次一致时调用方会拿到 null。 */
data class AutomaticScheduleSeed(
    val signature: String,
    val display: AutomaticScheduleDisplay
)

/**
 * 自动显示决策的输入签名：只由「校区 + 星期/节次集合」构成。
 *
 * 这三样正是 [automaticScheduleDisplay] 读的**全部**输入，所以「签名变了」与
 * 「推导结果可能变了」是同一件事——这是能给出的最紧判据：既不会漏掉该展开的课表，
 * 也不会把「同一份课表又刷新了一次」误判成内容变化。
 *
 * 刻意不含的字段：课程/课次 id、标题、教师、教室、颜色、备注——重新解析后必变
 * （颜色还按输入顺序占位）；也不含 weekText，推导根本不读它，而「个人课表」与
 * 「逐周合并」两条导入路径对同一门课会写出 `1-16` / `1-16周` 两种写法。
 * 把任何一项纳进来，等于让用户刚关掉的周末列在下一次刷新里自己打开。
 *
 * 先 distinct 再 sorted：解析顺序变化不构成内容变化。
 */
fun automaticScheduleDisplaySignature(
    courses: List<ScheduleCourse>,
    campus: CampusType
): String = courses.asSequence()
    .flatMap { course -> course.occurrences.asSequence() }
    .map { occurrence -> "${occurrence.dayOfWeek}:${occurrence.startSection}:${occurrence.endSection}" }
    .distinct()
    .sorted()
    .joinToString(separator = "|", prefix = "${campus.name}|")

/**
 * 决定这次是否要用课表内容重新播种自动显示。
 *
 * 返回 null 表示内容签名与上次播种时一致——此时**必须原样保留用户当前的选择**：
 * 哪怕只是把相同的值再写一遍，也已经在语义上夺走了「用户关掉它」这个事实。
 */
fun planAutomaticScheduleSeed(
    storedSignature: String?,
    courses: List<ScheduleCourse>,
    campus: CampusType
): AutomaticScheduleSeed? {
    val signature = automaticScheduleDisplaySignature(courses, campus)
    if (storedSignature == signature) return null
    return AutomaticScheduleSeed(
        signature = signature,
        display = automaticScheduleDisplay(courses, campus)
    )
}

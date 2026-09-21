package com.glut.schedule.data.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 用户手动添加的「整天复制课程」规则（调休调课）。
 *
 * 语义与小程序 `utils/manualDayCopies.js` 保持一致：规则只描述「把 [sourceDate] 当天的
 * 全部课程复制到 [targetDate]」，**不写回教务导入快照**。副本在渲染时按日期重新计算，
 * 因此重新导入课表后规则依旧生效，源日期的课程也始终保留。
 *
 * `id` 由两个日期推导，天然保证同一学期内不重复。
 */
data class ManualDayCopyRule(
    val sourceDate: LocalDate,
    val targetDate: LocalDate
) {
    val id: String get() = "$sourceDate->$targetDate"
}

/** 校验结果：null 表示通过，否则为可直接展示给用户的原因。 */
fun validateManualDayCopy(sourceDate: LocalDate?, targetDate: LocalDate?): String? = when {
    sourceDate == null || targetDate == null -> "请选择有效日期"
    sourceDate == targetDate -> "原日期和目标日期不能相同"
    else -> null
}

/**
 * 只在目标日期所在的那一周生成副本课程块。
 *
 * 逐条规则独立计算，不修改 `courses` 里的任何对象——副本课次的 id 加
 * `-manual-copy-<ruleId>` 后缀，既不会和原课次撞 id，也能在冲突分组里被当作独立卡片。
 */
fun manualCopyBlocksForWeek(
    courses: List<ScheduleCourse>,
    rules: List<ManualDayCopyRule>,
    weekNumber: Int,
    weekMonday: LocalDate
): List<CourseBlock> {
    if (rules.isEmpty() || weekNumber < MIN_ACADEMIC_WEEK) return emptyList()
    val normalizedMonday = mondayOf(weekMonday)
    val blocks = mutableListOf<CourseBlock>()
    val seenOccurrenceIds = mutableSetOf<String>()

    rules.forEach { rule ->
        val targetDayOffset = ChronoUnit.DAYS.between(normalizedMonday, rule.targetDate).toInt()
        // 目标日不在本周：本周没有任何要追加的副本。
        if (targetDayOffset !in 0..6) return@forEach

        val sourceMonday = mondayOf(rule.sourceDate)
        val sourceWeek =
            weekNumber + (ChronoUnit.DAYS.between(normalizedMonday, sourceMonday) / 7).toInt()
        // 源日期早于学期第一周时不做任何事；上界只是防御，日期被限制在学期内时不可能越过。
        if (sourceWeek < MIN_ACADEMIC_WEEK || sourceWeek > MAX_ACADEMIC_WEEK) return@forEach

        val sourceDayOfWeek = rule.sourceDate.dayOfWeek.value
        val targetDayOfWeek = targetDayOffset + 1
        courses.forEach { course ->
            course.occurrences
                .filter { occurrence ->
                    occurrence.dayOfWeek == sourceDayOfWeek && occurrence.isActiveInWeek(sourceWeek)
                }
                .forEach { occurrence ->
                    val copyId = "${occurrence.id}-manual-copy-${rule.id}"
                    if (!seenOccurrenceIds.add(copyId)) return@forEach
                    blocks += CourseBlock(
                        course = course,
                        occurrence = occurrence.copy(
                            id = copyId,
                            dayOfWeek = targetDayOfWeek,
                            weekText = "第${weekNumber}周"
                        )
                    )
                }
        }
    }

    return blocks
}

/**
 * 统计某个自然日当天会被复制走的课程块数量，供编辑页在保存前预览。
 *
 * 日期在学期开始之前（或算出的周次早于第一周）时返回 0，调用方据此拒绝保存。
 */
fun countManualCopySourceBlocks(
    courses: List<ScheduleCourse>,
    sourceDate: LocalDate?,
    semesterStartMonday: LocalDate?
): Int {
    if (sourceDate == null || semesterStartMonday == null) return 0
    val weekNumber = weekNumberForDate(sourceDate, semesterStartMonday)
    if (sourceDate.isBefore(mondayOf(semesterStartMonday))) return 0
    return courses.sumOf { course ->
        course.occurrences.count { occurrence ->
            occurrence.dayOfWeek == sourceDate.dayOfWeek.value &&
                occurrence.isActiveInWeek(weekNumber)
        }
    }
}

/**
 * 规则集合的存储编码。
 *
 * 沿用课程配色覆盖的存法（分隔符拼接的 StringSet），不引入 JSON 解析：
 * 一条规则一个条目，首段是学期 id，因此读取时可以按学期分组。
 * 学期 id 由校区与年月日段拼成，不可能出现分隔符，用它做分隔不存在歧义。
 */
private const val RULE_FIELD_SEPARATOR = '\u0000'

fun encodeManualDayCopyRules(rulesBySemester: Map<String, List<ManualDayCopyRule>>): Set<String> =
    rulesBySemester.entries.flatMap { (semesterId, rules) ->
        if (semesterId.isBlank()) return@flatMap emptyList()
        rules.map { rule ->
            "$semesterId$RULE_FIELD_SEPARATOR${rule.sourceDate}$RULE_FIELD_SEPARATOR${rule.targetDate}"
        }
    }.toSet()

/**
 * 解码规则集合。无法解析的条目一律丢弃而不是整体失败——存储里残留一条脏数据
 * 不应该让整个学期的调休规则消失。
 */
fun decodeManualDayCopyRules(entries: Set<String>): Map<String, List<ManualDayCopyRule>> {
    val result = linkedMapOf<String, MutableList<ManualDayCopyRule>>()
    entries.forEach { entry ->
        val parts = entry.split(RULE_FIELD_SEPARATOR)
        if (parts.size != 3) return@forEach
        val semesterId = parts[0]
        if (semesterId.isBlank()) return@forEach
        val sourceDate = parseIsoDateOrNull(parts[1]) ?: return@forEach
        val targetDate = parseIsoDateOrNull(parts[2]) ?: return@forEach
        val rule = ManualDayCopyRule(sourceDate = sourceDate, targetDate = targetDate)
        if (validateManualDayCopy(sourceDate, targetDate) != null) return@forEach
        val rules = result.getOrPut(semesterId) { mutableListOf() }
        // 同一学期内以 id 去重，避免旧数据里的重复条目在编辑页显示两遍。
        if (rules.none { it.id == rule.id }) rules += rule
    }
    return result.mapValues { (_, rules) -> rules.sortedBy { it.targetDate } }
}

private fun parseIsoDateOrNull(value: String): LocalDate? =
    runCatching { LocalDate.parse(value) }.getOrNull()

/** 日期落在学期第几周（从 1 开始，按起始周一计算）；早于第一周时返回 0 或负数。 */
fun weekNumberForDate(date: LocalDate, semesterStartMonday: LocalDate): Int {
    val normalizedStart = mondayOf(semesterStartMonday)
    return Math.floorDiv(ChronoUnit.DAYS.between(normalizedStart, date), 7L).toInt() + 1
}

/** 把日期对齐到所在周的周一。 */
fun mondayOf(date: LocalDate): LocalDate =
    date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

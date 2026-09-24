package com.glut.schedule.data.model

/**
 * 用户手动「删除」的课程卡片。
 *
 * 这是继配色覆盖、手动调休规则之后的第三个**用户覆盖层**：规则独立于课程数据存在，
 * 只在读取/渲染时叠加，绝不写回快照。原因很实在——刷新课表走的是
 * `ScheduleDao.replaceSemesterSchedule` 的「事务内先删后插」，整门课、整个课次都会被重建；
 * 隐藏记录若跟着课程数据走，刷新一次就全被复活了。
 *
 * 键一律使用**语义键**而不是课次 id：两端都在用 `${courseId}-occurrence-${index}` 这类
 * 索引式 id，刷新后教务返回顺序一变就漂移，记录会静默失配。语义键与配色覆盖同源
 * （[CourseColorMapper.colorKey]），因此重导入后依然能命中。
 */

/** 删除的三档范围。`storageValue` 与小程序 `utils/hiddenCourseCards.js` 的 SCOPES 逐一对应。 */
enum class HiddenCardScope(val storageValue: String) {
    /** 整门课：这门课在本学期的全部卡片。 */
    COURSE("course"),

    /** 这个课次：命中 (星期, 起止节) 的那个课次的所有周。 */
    OCCURRENCE("occurrence"),

    /** 本周这次：只去掉某一周，其他周照常显示。 */
    WEEK("week")
}

/**
 * 一条隐藏记录。
 *
 * [dayOfWeek] / [startSection] / [endSection] 只在 [HiddenCardScope.OCCURRENCE] 与
 * [HiddenCardScope.WEEK] 下有意义；[week] 只在 [HiddenCardScope.WEEK] 下有值。
 */
data class HiddenCourseRule(
    val scope: HiddenCardScope,
    val courseKey: String,
    val dayOfWeek: Int = 0,
    val startSection: Int = 0,
    val endSection: Int = 0,
    val week: Int = 0
) {
    /** 稳定标识，用于去重与「恢复某一条」。 */
    val id: String
        get() = when (scope) {
            HiddenCardScope.COURSE -> courseKey
            HiddenCardScope.OCCURRENCE -> "$courseKey@$dayOfWeek:$startSection-$endSection"
            HiddenCardScope.WEEK -> "$courseKey@$dayOfWeek:$startSection-$endSection#$week"
        }

    /** 该规则是否管得住这个课次（不含「那一周是否真的在课次周次范围内」这一步）。 */
    fun matchesOccurrence(courseKey: String, occurrence: CourseOccurrence): Boolean {
        if (scope == HiddenCardScope.COURSE) return false
        if (this.courseKey != courseKey) return false
        return occurrence.dayOfWeek == dayOfWeek &&
            occurrence.startSection == startSection &&
            occurrence.endSection == endSection
    }
}

/** 与配色覆盖同一个键：课程名去空格、剥 `@班级` 后缀、转小写，课程名为空时回退 id。 */
fun hiddenCourseKey(courseId: String, title: String): String =
    CourseColorMapper.colorKey(courseId, title)

/**
 * 把隐藏规则叠加到课程列表上（纯函数，不改原对象）。
 *
 * **调用方必须是已经配好色的列表**：`CourseColorMapper.assignColors` 会按输入顺序占位、
 * 并做相邻颜色避让，少一门课会释放调色板索引、让其余可见课程跟着换色。
 * 所以顺序永远是「先配色、后过滤」。
 *
 * 三档语义：
 * - `course` 整门课丢弃；
 * - `occurrence` 命中课次整条丢弃；
 * - `week` 命中课次按周剥掉该周，剥空则丢弃该课次。
 *
 * 注意这里丢掉的课次是**渲染口径**的丢弃，不影响调用方判空态用的完整列表。
 */
fun applyHiddenCourseRules(
    courses: List<ScheduleCourse>,
    rules: List<HiddenCourseRule>
): List<ScheduleCourse> {
    if (rules.isEmpty() || courses.isEmpty()) return courses
    val hiddenCourseKeys = rules
        .filter { it.scope == HiddenCardScope.COURSE }
        .mapTo(mutableSetOf()) { it.courseKey }
    val occurrenceRules = rules.filter { it.scope == HiddenCardScope.OCCURRENCE }
    val weekRules = rules.filter { it.scope == HiddenCardScope.WEEK }
    if (hiddenCourseKeys.isEmpty() && occurrenceRules.isEmpty() && weekRules.isEmpty()) return courses

    return courses.mapNotNull { course ->
        val courseKey = hiddenCourseKey(course.id, course.title)
        if (courseKey in hiddenCourseKeys) return@mapNotNull null

        val occurrences = course.occurrences.flatMap { occurrence ->
            if (occurrenceRules.any { it.matchesOccurrence(courseKey, occurrence) }) {
                return@flatMap emptyList()
            }
            val weekRule = weekRules.firstOrNull {
                it.matchesOccurrence(courseKey, occurrence) &&
                    it.week in academicWeeksForText(occurrence.weekText)
            } ?: return@flatMap listOf(occurrence)

            // 「1-16周 去掉第 5 周」没法用一段文本表示，会拆成多段，每段各生成一个课次。
            weekTextWithoutWeek(occurrence.weekText, weekRule.week).mapIndexed { index, remaining ->
                occurrence.copy(
                    id = "${occurrence.id}-hidden-w${weekRule.week}-$index",
                    weekText = remaining
                )
            }
        }
        if (occurrences.isEmpty()) null else course.copy(occurrences = occurrences)
    }
}

/**
 * 当前课表里**真正被隐藏掉**的卡片数。
 *
 * 只统计确实命中现有课次的规则：教务排课会来回变，失效的规则我们有意保留（这样课再排回来
 * 依然是隐藏的），但刷新弹窗上写「保留 N 张」时不能把失效的也算进去——否则会出现
 * 「说保留了 8 张却一张都看不见」。
 */
fun hiddenCardCount(courses: List<ScheduleCourse>, rules: List<HiddenCourseRule>): Int =
    rules.count { rule -> hiddenCardRuleHits(courses, rule) }

/** 这条规则是否管得住当前课表里的某个课次（`week` 档还要求那一周真的在课次的周次范围内）。 */
fun hiddenCardRuleHits(courses: List<ScheduleCourse>, rule: HiddenCourseRule): Boolean =
    courses.any { course ->
        val courseKey = hiddenCourseKey(course.id, course.title)
        if (rule.scope == HiddenCardScope.COURSE) {
            courseKey == rule.courseKey
        } else {
            courseKey == rule.courseKey && course.occurrences.any { occurrence ->
                rule.matchesOccurrence(courseKey, occurrence) &&
                    (rule.scope != HiddenCardScope.WEEK || rule.week in academicWeeksForText(occurrence.weekText))
            }
        }
    }

/** 设置页「已隐藏的卡片」列表里的一行，例如「数据库系统 · 周二 3-4 节 · 第 5 周」。 */
fun hiddenRuleLabel(rule: HiddenCourseRule, courses: List<ScheduleCourse>): String =
    if (rule.scope == HiddenCardScope.COURSE) {
        "${hiddenRuleCourseTitle(rule, courses)}（整门课，同名课程一并隐藏）"
    } else {
        "${hiddenRuleCourseTitle(rule, courses)} · ${hiddenRuleScopeText(rule, courses)}"
    }

/**
 * 范围说明，用在卡片管理弹层的按钮括号里。
 *
 * 「这个课次」展示的是**该课次当前实际生效的周次**，而不是 `weekText` 原文——
 * 教务原文可能带「第」「单周」等噪声，直接抄给用户反而看不懂。
 */
fun hiddenRuleScopeText(rule: HiddenCourseRule, courses: List<ScheduleCourse>): String =
    when (rule.scope) {
        HiddenCardScope.COURSE ->
            // 键只看课程名（剥 @班级 后缀、去空格、小写），同名不同教师/教室的课会一起隐藏，
            // 必须写清楚，否则用户会以为只删了这一门。
            "${hiddenRuleCourseTitle(rule, courses)}，同名课程一并隐藏"

        HiddenCardScope.WEEK -> listOfNotNull(
            hiddenRuleSectionText(rule),
            "第 ${rule.week} 周"
        ).joinToString(" · ")

        HiddenCardScope.OCCURRENCE -> listOfNotNull(
            hiddenRuleSectionText(rule),
            hiddenRuleMatchedWeekText(rule, courses)
        ).joinToString(" · ")
    }

private fun hiddenRuleCourseTitle(rule: HiddenCourseRule, courses: List<ScheduleCourse>): String =
    courses.firstOrNull { hiddenCourseKey(it.id, it.title) == rule.courseKey }?.title ?: rule.courseKey

private fun hiddenRuleSectionText(rule: HiddenCourseRule): String =
    "${weekdayLabel(rule.dayOfWeek)} ${rule.startSection}-${rule.endSection} 节"

/** 命中的那个课次在课表里实际生效的周次，压成 `1-16周` 这样的文本。 */
private fun hiddenRuleMatchedWeekText(rule: HiddenCourseRule, courses: List<ScheduleCourse>): String =
    courses.firstOrNull { hiddenCourseKey(it.id, it.title) == rule.courseKey }
        ?.occurrences
        ?.firstOrNull {
            it.dayOfWeek == rule.dayOfWeek &&
                it.startSection == rule.startSection &&
                it.endSection == rule.endSection
        }
        ?.let { compactWeekNumbers(academicWeeksForText(it.weekText)).joinToString("、") }
        .orEmpty()

private fun weekdayLabel(dayOfWeek: Int): String =
    if (dayOfWeek in 1..7) WEEKDAY_LABELS[dayOfWeek - 1] else "周?"

private val WEEKDAY_LABELS = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 编码为 DataStore 的 StringSet。沿用 `ManualDayCopy` 的 NUL 分隔做法，不引入 JSON——
 * 这些值每次都要整体读取，用不着结构化格式。
 *
 * 字段顺序固定：`学期id NUL scope NUL 课程键 [NUL 星期 NUL 起始节 NUL 结束节 [NUL 周次]]`。
 * 学期 id（形如 `guilin-2025-秋`）不含分隔符，不会有歧义。
 */
internal fun encodeHiddenCourseRules(
    rulesBySemester: Map<String, List<HiddenCourseRule>>
): Set<String> {
    val encoded = linkedSetOf<String>()
    rulesBySemester.forEach { (semesterId, rules) ->
        if (semesterId.isBlank()) return@forEach
        rules.take(MAX_RULES_PER_SEMESTER).forEach inner@{ rule ->
            if (rule.courseKey.isBlank()) return@inner
            val fields = mutableListOf(semesterId, rule.scope.storageValue, rule.courseKey)
            if (rule.scope != HiddenCardScope.COURSE) {
                fields += rule.dayOfWeek.toString()
                fields += rule.startSection.toString()
                fields += rule.endSection.toString()
            }
            if (rule.scope == HiddenCardScope.WEEK) fields += rule.week.toString()
            encoded += fields.joinToString(RULE_FIELD_SEPARATOR.toString())
        }
    }
    return encoded
}

/**
 * 从 StringSet 解回来。**脏条目逐条丢弃**而不是整体失败——一条坏记录不该让用户的
 * 全部隐藏设置作废（与 `decodeManualDayCopyRules` 同策略）。
 */
internal fun decodeHiddenCourseRules(entries: Set<String>): Map<String, List<HiddenCourseRule>> {
    val result = linkedMapOf<String, MutableList<HiddenCourseRule>>()
    entries.forEach entry@{ entry ->
        val fields = entry.split(RULE_FIELD_SEPARATOR)
        val semesterId = fields.getOrNull(0)?.trim().orEmpty()
        if (semesterId.isBlank()) return@entry
        val scope = HiddenCardScope.entries.firstOrNull { it.storageValue == fields.getOrNull(1) }
            ?: return@entry
        val courseKey = fields.getOrNull(2)?.trim().orEmpty()
        if (courseKey.isBlank()) return@entry

        val rule = if (scope == HiddenCardScope.COURSE) {
            HiddenCourseRule(scope = scope, courseKey = courseKey)
        } else {
            if (fields.size < if (scope == HiddenCardScope.WEEK) 7 else 6) return@entry
            val day = fields[3].toIntOrNull() ?: return@entry
            val start = fields[4].toIntOrNull() ?: return@entry
            val end = fields[5].toIntOrNull() ?: return@entry
            if (day !in 1..7 || start !in 1..MAX_SECTION || end !in 1..MAX_SECTION || end < start) {
                return@entry
            }
            if (scope == HiddenCardScope.WEEK) {
                val week = fields[6].toIntOrNull() ?: return@entry
                if (week !in 1..MAX_WEEK) return@entry
                HiddenCourseRule(scope, courseKey, day, start, end, week)
            } else {
                HiddenCourseRule(scope, courseKey, day, start, end)
            }
        }

        val rules = result.getOrPut(semesterId) { mutableListOf() }
        if (rules.none { it.id == rule.id } && rules.size < MAX_RULES_PER_SEMESTER) rules += rule
    }
    return result
}

/** 与 `ManualDayCopyRule` 一致：用 NUL 作字段分隔符，课程名里的普通字符撞不上。 */
private val RULE_FIELD_SEPARATOR: Char = Char(0)

/**
 * 每学期规则条数上界。教务排课会反复变动、失效规则我们有意保留，所以需要兜底上限防止
 * 存储无限增长；超出后丢弃新规则（老规则优先，避免反复删同一张卡时把已有记录挤掉）。
 */
private const val MAX_RULES_PER_SEMESTER = 500
private const val MAX_SECTION = 14
private const val MAX_WEEK = 22

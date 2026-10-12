package com.glut.schedule.data.model

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 自动调课（「调休调课」页的开关打开时生效）的规则与推导。
 *
 * 与 [ManualDayCopyRule] 的关系：**语义完全一致**（把 [sourceDate] 当天的课复制到
 * [targetDate]，源日期课程保留），所以渲染层只要把它转成 [ManualDayCopyRule] 就能复用
 * `manualCopyBlocksForWeek`，`ScheduleScreen` / 小组件 / 长按管理弹层一行都不用改。
 * 多出来的 [origin] 只服务于编辑页的「自动/手动」标注与删除抑制。
 *
 * 两条来源：
 * - [AutoCopyOrigin.REMOTE]：远端对照表（维护者按学校放假通知录入，权威）。
 * - [AutoCopyOrigin.DERIVED]：从节假日缓存的补班日推导（兜底，只在能唯一确定时生成）。
 */
enum class AutoCopyOrigin(val storageValue: String) {
    REMOTE("remote"),
    DERIVED("derived");

    companion object {
        fun fromStorageValue(value: String): AutoCopyOrigin? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * 一条自动调课规则。
 *
 * [id] 与 [ManualDayCopyRule.id] 同构（`源日期->目标日期`），因此两边合并后不会撞 id，
 * 同一条规则也不会既算手动又算自动地显示两遍。
 */
data class AutoDayCopyRule(
    val sourceDate: LocalDate,
    val targetDate: LocalDate,
    val origin: AutoCopyOrigin
) {
    val id: String get() = "$sourceDate->$targetDate"

    fun asManualRule(): ManualDayCopyRule = ManualDayCopyRule(sourceDate, targetDate)
}

/** 远端对照表里的一条。解析落在 service 层，条目类型放这里以保持 `service → data.model` 的依赖方向。 */
data class MakeupEntry(
    val source: LocalDate,
    val target: LocalDate
)

/**
 * 节假日接口给出的一个补班日。
 *
 * [name] 是 timor 原文（如「国庆节前补班」），**推导不看它**：上游把补班日挂到哪个假期并不可靠
 * （2026 年 9/20 被标成「中秋节前补班」，学校通知里它补的却是国庆节内的 10/6），所以推导只认
 * [date]，归属靠「离哪段连休最近」自己算。字段保留是因为缓存与页面还要显示原名。
 */
data class AdjustedWorkday(
    val date: LocalDate,
    val name: String
)

/** 推导的一键停机开关：出问题时不改逻辑就能退回「只信对照表」。 */
internal const val AUTO_DERIVATION_ENABLED = true

/** 推导结果。notices 是给页面直接显示的一行说明，**不含任何需要用户点击的动作**。 */
internal data class DerivationOutcome(
    val rules: List<AutoDayCopyRule>,
    val notices: List<String>
)

/** 界面上显示的日期文案（`10月7日`）。 */
internal fun makeupDateText(date: LocalDate): String = "${date.monthValue}月${date.dayOfMonth}日"

/**
 * 教务**已经下发过补课安排**的目标日期集合。
 *
 * 这是整套自动调课的自我纠错机制：教务一旦下发，「原课次被摘掉 + 补课卡生成」就已经把
 * 课表改对了（见 `AcademicScheduleParser.parsePersonalSchedule`），自动调课必须对该日让位，
 * 否则同一节课会在同一周显示两张卡。
 *
 * 只认有补课侧的记录：停课与代课的 `makeupWeek/makeupDay` 恒为 0（它们的锚点在原时段），
 * 天然不会进这个集合。
 *
 * 周次换算必须与课表渲染同一口径（`scheduleWeekForNumber` 的周一 + 星期偏移），
 * 否则周次一漂就会「让位到别的日子」——**这条换算有专门的测试钉着**。
 */
internal fun adjustmentTargetDates(
    adjustments: List<SemesterAdjustment>,
    semesterStartMonday: LocalDate
): Set<LocalDate> {
    if (adjustments.isEmpty()) return emptySet()
    val normalizedStart = mondayOf(semesterStartMonday)
    return adjustments.asSequence()
        .filter { it.makeupWeek in MIN_ACADEMIC_WEEK..MAX_ACADEMIC_WEEK && it.makeupDay in 1..7 }
        .mapTo(mutableSetOf()) { adjustment ->
            normalizedStart
                .plusWeeks((adjustment.makeupWeek - 1).toLong())
                .plusDays((adjustment.makeupDay - 1).toLong())
        }
}

/** 假期区间内的工作日（周一~周五），按日期升序。 */
private fun workdaysWithin(start: LocalDate, end: LocalDate): List<LocalDate> {
    if (end.isBefore(start)) return emptyList()
    val days = mutableListOf<LocalDate>()
    var cursor = start
    while (!cursor.isAfter(end)) {
        if (cursor.dayOfWeek.value <= 5) days += cursor
        cursor = cursor.plusDays(1)
    }
    return days
}

/**
 * 法定节假日天数。**按年份分档**：2024 年 11 月修订的《全国年节及纪念日放假办法》从 2025 年起
 * 把春节从 3 天加到 4 天（含除夕）、劳动节从 1 天加到 2 天。
 *
 * 这张表是推导的前提之一，破了就会整体错位：2025 国庆+中秋那 8 天连休漏算中秋那 1 天法定，
 * 被借工作日就从 2 个变成 3 个，与 2 个补班日配不上——或者更糟，配上但整体平移一天。
 */
private data class StatutoryDays(val fromYear: Int, val days: Int)

private val STATUTORY_DAYS: Map<String, List<StatutoryDays>> = mapOf(
    "元旦" to listOf(StatutoryDays(0, 1)),
    "春节" to listOf(StatutoryDays(0, 3), StatutoryDays(2025, 4)),
    "清明节" to listOf(StatutoryDays(0, 1)),
    "劳动节" to listOf(StatutoryDays(0, 1), StatutoryDays(2025, 2)),
    "端午节" to listOf(StatutoryDays(0, 1)),
    "中秋节" to listOf(StatutoryDays(0, 1)),
    "国庆节" to listOf(StatutoryDays(0, 3))
)

/** 取某年该假期的法定天数；不认识的假期名返回 null——**不猜**，该簇整簇放弃。 */
private fun statutoryDaysOf(name: String, year: Int): Int? =
    STATUTORY_DAYS[name]?.lastOrNull { it.fromYear <= year }?.days

/** 日历上的周六周日：课表网格在「显示周末」关掉时只画到这一天为止。 */
private const val LAST_WEEKDAY = 5

private fun isWeekend(date: LocalDate): Boolean = date.dayOfWeek.value > LAST_WEEKDAY

/**
 * 假期分簇/补班日归属的最大日距：中秋（9/25–9/27）与国庆（10/1–10/7）相隔 3 天，算同一段连休；
 * 而调休的补班日离自己那段连休最多 5 天（2026 的 2/28 离春节区间末 2/23 是 5 天），7 天留足余量。
 */
private const val CLUSTER_LINK_MAX_DAYS = 7

/** 解析出区间的假期条目。区间解析不出来的直接丢掉——没有区间就算不了任何东西。 */
private data class DatedHoliday(val holiday: HolidayInfo, val start: LocalDate, val end: LocalDate)

/**
 * 一段连休：若干日期相邻或嵌套的假期合在一起算账。
 *
 * 必须是「簇」而不是「单个假期」，因为两件事实都会跨假期：上游把补班日挂到相邻假期（2026 把
 * 9/20 挂成「中秋节前补班」，而它补的是国庆节内的 10/6）；被借走的工作日也可能落在另一个假期
 * 的区间里（2025 的中秋 10/6 就在国庆的 10/1–10/8 中间）。
 */
private class MakeupCluster(
    val holidays: List<HolidayInfo>,
    val start: LocalDate,
    val end: LocalDate,
    /** 被借走的工作日（升序）；[unresolvedReason] 非空时为 null，表示算不出来。 */
    val borrowedWorkdays: List<LocalDate>?,
    /** 算不出来的原因，直接拼进提示文案；算得出来时为 null。 */
    val unresolvedReason: String?
) {
    val label: String get() = holidays.joinToString("、") { it.name }

    /** 只有与学期有关的簇才写提示：寒假里的春节不该占着本学期页面的提示位。 */
    fun touchesSemester(
        semesterStartDate: LocalDate,
        semesterEndDate: LocalDate,
        makeupDays: List<LocalDate>
    ): Boolean =
        (!end.isBefore(semesterStartDate) && !start.isAfter(semesterEndDate)) ||
            makeupDays.any { it in semesterStartDate..semesterEndDate }
}

/**
 * 法定起算日**不固定**的假期：春节（除夕、初一是农历）、清明节（节气在 4/4 或 4/5）、
 * 端午节（五月初五）、中秋节（八月十五）。这几天的位置会在区间里漂，只能靠区间形状定位。
 *
 * 其余的起算日是死日期——元旦 1/1、劳动节 5/1、国庆 10/1——法定块永远从这些日子起算，
 * 直接取区间首日即可。2020 劳动节的区间是 5/1~5/5、首末都是工作日，「形状」说不清，但法定日
 * 就是 5/1，按形状规则反而会误放弃。
 */
private val MOVABLE_STATUTORY_HOLIDAYS = setOf("春节", "清明节", "端午节", "中秋节")

/**
 * 法定块的起点。
 *
 * - **起算日固定的假期**（元旦、劳动节、国庆）与**多日法定假日**（春节 4 天、国庆 3 天、
 *   2025 起劳动节 2 天）：取区间首日。2026 春节的区间首日 2/15 是周日，会被 [statutoryDatesFrom]
 *   的顺延吃掉，结果正好是从除夕 2/16 起的 4 天。
 * - **起算日会漂的单日法定假日**（清明、端午、中秋）：法定日由农历/节气决定，可以落在区间里的
 *   **任何位置**，只能靠区间形状定位——连休块 = 法定日 + 被吸进来的相邻周末，所以法定日一定在
 *   「远离周末的那一端」。2024 中秋的区间是 9/15（周日）~9/17（周二），法定日（八月十五）是末端
 *   的 9/17；按「区间首日」算会把它错当成 9/16，整条映射平移一天。2021 中秋同理。
 *   首末同性质（都是工作日 / 都是周末，含 2022、2023 那种「元旦」同名日期被跨年拉成整年区间的
 *   畸形形状）说不清，返回 null，由调用方放弃。
 */
private fun statutoryStart(item: DatedHoliday, days: Int): LocalDate? {
    if (days > 1 || item.holiday.name !in MOVABLE_STATUTORY_HOLIDAYS) return item.start
    val first = item.start
    val last = item.end
    return when {
        first == last -> first
        !isWeekend(first) && isWeekend(last) -> first
        isWeekend(first) && !isWeekend(last) -> last
        else -> null
    }
}

/**
 * 法定节假日日期：从 [rangeStart] 起取 [days] 天，落在周末的顺延到后面的工作日（补假规则）。
 *
 * 起算点由 [statutoryStart] 按区间形状给出，**不能再假想成区间首日**——那正是 2024/2021 中秋
 * 错一天的原因。
 */
private fun statutoryDatesFrom(rangeStart: LocalDate, days: Int): List<LocalDate> {
    val result = mutableListOf<LocalDate>()
    var postponed = 0L
    repeat(days) { offset ->
        val candidate = rangeStart.plusDays(offset.toLong())
        if (isWeekend(candidate)) {
            var delta = days.toLong() + postponed
            while (isWeekend(rangeStart.plusDays(delta))) delta++
            result += rangeStart.plusDays(delta)
            postponed++
        } else {
            result += candidate
        }
    }
    return result
}

/** 假期按日期邻近分簇；嵌套的假期（中秋落在国庆区间里）天然落进同一簇。 */
private fun clusterHolidays(holidays: List<HolidayInfo>): List<MakeupCluster> {
    val dated = holidays.mapNotNull { holiday ->
        val start = parseIsoDateOrNull(holiday.startDate) ?: return@mapNotNull null
        val end = parseIsoDateOrNull(holiday.endDate) ?: return@mapNotNull null
        DatedHoliday(holiday, start, end)
    }.sortedWith(compareBy({ it.start }, { it.end }))

    val clusters = mutableListOf<MakeupCluster>()
    val pending = mutableListOf<DatedHoliday>()
    var pendingEnd: LocalDate? = null
    dated.forEach { item ->
        val currentEnd = pendingEnd
        if (currentEnd != null && item.start.isAfter(currentEnd.plusDays(CLUSTER_LINK_MAX_DAYS.toLong()))) {
            clusters += buildCluster(pending)
            pending.clear()
            pendingEnd = null
        }
        pending += item
        val previousEnd = pendingEnd
        pendingEnd = if (previousEnd == null || item.end.isAfter(previousEnd)) item.end else previousEnd
    }
    if (pending.isNotEmpty()) clusters += buildCluster(pending)
    return clusters
}

private fun buildCluster(items: List<DatedHoliday>): MakeupCluster {
    val statutory = mutableListOf<LocalDate>()
    var unresolvedReason: String? = null
    // 同一个假期被别的假期从中间切开时会分成多段（2025 的国庆被中秋切成 10/1–10/5 与 10/7–10/8），
    // 而**法定块只属于第一段**——它从假期起始日起算，被切出来的后一段装的正是被借走的工作日。
    // 每段各算一次的话，10/7、10/8 会被当成法定日，被借日就空了。
    val countedNames = mutableSetOf<String>()
    items.forEach { item ->
        if (!countedNames.add(item.holiday.name)) return@forEach
        val days = statutoryDaysOf(item.holiday.name, item.start.year)
        val start = if (days == null) null else statutoryStart(item, days)
        when {
            days == null ->
                if (unresolvedReason == null) unresolvedReason = "有认不出的假期，算不出被借走的工作日"
            start == null ->
                if (unresolvedReason == null) unresolvedReason = "假期的区间形状说不清，定位不出法定日"
            else -> statutory += statutoryDatesFrom(start, days)
        }
    }
    // 被借走的工作日 = 簇内假期区间里的工作日 − 法定节假日。周末不用减：workdaysWithin 已经排除。
    val workdays = items.flatMap { workdaysWithin(it.start, it.end) }.distinct().sorted()
    return MakeupCluster(
        holidays = items.map { it.holiday },
        start = items.minOf { it.start },
        end = items.maxOf { it.end },
        borrowedWorkdays = if (unresolvedReason == null) workdays.filterNot { it in statutory } else null,
        unresolvedReason = unresolvedReason
    )
}

/** 补班日归属最近的连休簇；超过 [CLUSTER_LINK_MAX_DAYS] 天返回 null（宁可放弃也不硬塞）。 */
private fun nearestCluster(clusters: List<MakeupCluster>, date: LocalDate): Int? {
    var bestIndex: Int? = null
    var bestDistance = Long.MAX_VALUE
    clusters.forEachIndexed { index, cluster ->
        val distance = when {
            date.isBefore(cluster.start) -> ChronoUnit.DAYS.between(date, cluster.start)
            date.isAfter(cluster.end) -> ChronoUnit.DAYS.between(cluster.end, date)
            else -> 0L
        }
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = index
        }
    }
    return if (bestDistance <= CLUSTER_LINK_MAX_DAYS) bestIndex else null
}

/**
 * 从补班日推导「补哪一天的课」：**把被借走的工作日还给补班日**。
 *
 * 记账恒等式——假期 = 法定节假日 + 落在假期里的周末 + **被借走的工作日**；而调休是「借几天
 * 还几天」，所以 **被借走的工作日数必须等于补班日数**。这不是启发式，是调休的定义，三年的
 * 真实教务处通知都能靠它复现：
 * - 2024 国庆：借 10/4、10/7 → 9/29 补 10/4、10/12 补 10/7（五所高校通知一致）
 * - 2025 国庆+中秋：借 10/7、10/8 → 9/28 补 10/7、10/11 补 10/8（四所高校通知一致）
 * - 2026 国庆+中秋：借 10/6、10/7 → 9/20 补 10/6、10/10 补 10/7（本校通知，见对照表）
 *
 * 它与「数假期内的工作日」那种老口径的差别就在**法定节假日和被借走的日子必须分开**：老口径
 * 把法定日也算成待补的，2026 国庆就成了「5 个工作日 vs 1 个补班日」，只能放弃；而中秋那组
 * 凑出的「1 vs 1」又是个巧合，会补出错误的课。
 *
 * 三个必须显式维护的前提，破了任何一条都会整体错位而且错得看不出来：见 [STATUTORY_DAYS]（按
 * 年份分档）、[statutoryDatesFrom]（法定日撞周末要顺延）、以及下面「按日期顺序一一对应」。
 * **所以远端对照表永远优先于推导**，推导只是新学期通知还没录进来时的兜底。
 *
 * 补班日的归属靠「离哪段连休最近」，**完全不看它的名字**：上游的挂靠不可靠（2026 把 9/20 标成
 * 「中秋节前补班」），而在簇这一级挂错会自动失效——中秋那簇借 0 天却挂着 1 个补班日，账对不上。
 */
internal fun deriveMakeupCopies(
    semesterStartDate: LocalDate,
    semesterEndDate: LocalDate,
    adjustedWorkdays: List<AdjustedWorkday>,
    holidays: List<HolidayInfo>,
    holidayCacheAvailable: Boolean,
    yieldedDates: Set<LocalDate>,
    excludedIds: Set<String>,
    derivationEnabled: Boolean = AUTO_DERIVATION_ENABLED
): DerivationOutcome {
    if (!derivationEnabled) return DerivationOutcome(emptyList(), emptyList())
    if (!holidayCacheAvailable) {
        return DerivationOutcome(
            emptyList(),
            listOf("节假日数据尚未缓存，补班日规律推导暂不可用。请到首页下拉刷新后再试。")
        )
    }
    if (adjustedWorkdays.isEmpty()) return DerivationOutcome(emptyList(), emptyList())

    val notices = mutableListOf<String>()
    val rules = mutableListOf<AutoDayCopyRule>()
    val usedTargets = mutableSetOf<LocalDate>()

    // 与学期搭不上边的假期**直接丢掉**：寒假里的春节、暑假后的国庆，算不出任何本学期要用的东西。
    // 留 [CLUSTER_LINK_MAX_DAYS] 的余量是为了让学期边界上的补班日仍能找到自己那段连休
    // （补班日离连休最多 5 天），否则它会变成「找不到假期」的孤儿并多出一条没意义的提示。
    val scopedHolidays = holidays.filter { holiday ->
        val start = parseIsoDateOrNull(holiday.startDate) ?: return@filter false
        val end = parseIsoDateOrNull(holiday.endDate) ?: return@filter false
        !end.isBefore(semesterStartDate.minusDays(CLUSTER_LINK_MAX_DAYS.toLong())) &&
            !start.isAfter(semesterEndDate.plusDays(CLUSTER_LINK_MAX_DAYS.toLong()))
    }
    val clusters = clusterHolidays(scopedHolidays)

    // 补班日归属最近的连休簇。**不看名字**：上游把 9/20 挂成「中秋节前补班」也不影响结果，
    // 因为中秋和国庆本来就在同一簇里，配对是在簇这一级做的。
    val makeupByCluster = MutableList(clusters.size) { mutableListOf<LocalDate>() }
    adjustedWorkdays.map { it.date }.distinct().sorted().forEach { date ->
        val index = nearestCluster(clusters, date)
        if (index == null) {
            // 学期外的孤儿补班日不提示：它补不到本学期的课上，写出来只是噪音。
            if (date in semesterStartDate..semesterEndDate) {
                notices += "${makeupDateText(date)} 是补班日，但前后 $CLUSTER_LINK_MAX_DAYS " +
                    "天内没有对应的假期，已跳过。"
            }
        } else {
            makeupByCluster[index] += date
        }
    }

    clusters.forEachIndexed { index, cluster ->
        val makeupDays = makeupByCluster[index].sorted()
        if (!cluster.touchesSemester(semesterStartDate, semesterEndDate, makeupDays)) return@forEachIndexed
        val borrowed = cluster.borrowedWorkdays
        if (borrowed == null) {
            notices += "${cluster.label}：${cluster.unresolvedReason.orEmpty()}，已跳过。"
            return@forEachIndexed
        }
        // 清明、端午这类不用还的假期：借 0 天、也没有补班日，什么都不用说。
        if (borrowed.isEmpty() && makeupDays.isEmpty()) return@forEachIndexed
        if (borrowed.size != makeupDays.size) {
            notices += "${cluster.label}：这段连休里有 ${borrowed.size} 个被借走的工作日、" +
                "${makeupDays.size} 个补班日，对不上，无法一一确定补哪一天，已跳过。"
            return@forEachIndexed
        }

        // 被借走的那天是 source（课从哪来），补班日是 target（课上在哪天）。
        // 按日期顺序一一对应：节前补班配靠前的被借日——2024/2025/2026 三年通知都符合，
        // 但这是惯例不是规定（佛山大学 2024 年就在自己的通知里改过），所以远端永远优先。
        borrowed.zip(makeupDays).forEach { (sourceDate, targetDate) ->
            writeDerivedRule(
                sourceDate = sourceDate,
                targetDate = targetDate,
                semesterStartDate = semesterStartDate,
                semesterEndDate = semesterEndDate,
                yieldedDates = yieldedDates,
                excludedIds = excludedIds,
                usedTargets = usedTargets,
                rules = rules,
                notices = notices
            )
        }
    }

    return DerivationOutcome(rules.sortedBy { it.targetDate }, notices)
}

private fun writeDerivedRule(
    sourceDate: LocalDate,
    targetDate: LocalDate,
    semesterStartDate: LocalDate,
    semesterEndDate: LocalDate,
    yieldedDates: Set<LocalDate>,
    excludedIds: Set<String>,
    usedTargets: MutableSet<LocalDate>,
    rules: MutableList<AutoDayCopyRule>,
    notices: MutableList<String>
) {
    val rule = AutoDayCopyRule(sourceDate, targetDate, AutoCopyOrigin.DERIVED)
    if (sourceDate.isBefore(semesterStartDate) || sourceDate.isAfter(semesterEndDate) ||
        targetDate.isBefore(semesterStartDate) || targetDate.isAfter(semesterEndDate)
    ) {
        notices += "${makeupDateText(targetDate)} 的补班安排在学期范围外，已忽略。"
        return
    }
    if (targetDate in yieldedDates) {
        notices += "${makeupDateText(targetDate)} 教务已下发补课安排，自动调课已让位。"
        return
    }
    if (rule.id in excludedIds) return
    if (!usedTargets.add(targetDate)) {
        notices += "${makeupDateText(targetDate)} 有多条补班安排，只保留了一条。"
        return
    }
    rules += rule
}

/** 远端条目 + 推导兜底的组合结果。 */
internal data class AutoDayCopyComposition(
    val rules: List<AutoDayCopyRule>,
    val notices: List<String>
)

/**
 * 组合自动调课规则：**远端对照表优先，推导兜底**。
 *
 * 同一 [AutoDayCopyRule.targetDate] 上远端压过推导（远端是维护者按学校通知录入的权威数据）。
 * 两者都要过的闸门：学期范围、让位教务（[yieldedDates]）、用户删除抑制（[suppressedIds]）、
 * 与手动规则同 id 时不重复（[manualRuleIds]）。
 *
 * 纯函数：同样的入参必得同样的输出，因此 `recompose` 可以随便重跑。
 */
internal fun composeAutoDayCopies(
    semesterStartDate: LocalDate,
    semesterEndDate: LocalDate,
    remoteEntries: List<MakeupEntry>,
    adjustedWorkdays: List<AdjustedWorkday>,
    holidays: List<HolidayInfo>,
    holidayCacheAvailable: Boolean,
    yieldedDates: Set<LocalDate>,
    suppressedIds: Set<String>,
    manualRuleIds: Set<String>,
    derivationEnabled: Boolean = AUTO_DERIVATION_ENABLED
): AutoDayCopyComposition {
    val notices = mutableListOf<String>()
    val remoteRules = mutableListOf<AutoDayCopyRule>()
    val usedTargets = mutableSetOf<LocalDate>()

    remoteEntries.forEach { entry ->
        val rule = AutoDayCopyRule(entry.source, entry.target, AutoCopyOrigin.REMOTE)
        if (entry.source.isBefore(semesterStartDate) || entry.source.isAfter(semesterEndDate) ||
            entry.target.isBefore(semesterStartDate) || entry.target.isAfter(semesterEndDate)
        ) {
            notices += "${makeupDateText(entry.target)} 的补班安排在学期范围外，已忽略。"
            return@forEach
        }
        if (entry.target in yieldedDates) {
            notices += "${makeupDateText(entry.target)} 教务已下发补课安排，自动调课已让位。"
            return@forEach
        }
        if (rule.id in suppressedIds || rule.id in manualRuleIds) return@forEach
        if (!usedTargets.add(entry.target)) {
            notices += "${makeupDateText(entry.target)} 在对照表里有重复条目，只保留了一条。"
            return@forEach
        }
        remoteRules += rule
    }

    val derived = deriveMakeupCopies(
        semesterStartDate = semesterStartDate,
        semesterEndDate = semesterEndDate,
        adjustedWorkdays = adjustedWorkdays,
        holidays = holidays,
        holidayCacheAvailable = holidayCacheAvailable,
        yieldedDates = yieldedDates,
        excludedIds = suppressedIds + manualRuleIds,
        derivationEnabled = derivationEnabled
    )
    // 远端已经覆盖的补班日不再由推导补一份；这是静默让位，不写 notice（用户没必要知道）。
    val derivedRules = derived.rules.filter { it.targetDate !in usedTargets }

    return AutoDayCopyComposition(
        rules = (remoteRules + derivedRules).sortedBy { it.targetDate },
        notices = notices + derived.notices
    )
}

// ---- 存储编码 ----
// 沿用 ManualDayCopy / HiddenCourseCards 的 \u0000 分隔做法：不引 JSON，一条规则一个条目，
// 首段是学期 id，因此读取时可以按学期分组。

private const val AUTO_COPY_FIELD_SEPARATOR = '\u0000'

fun encodeAutoDayCopyRules(bySemester: Map<String, List<AutoDayCopyRule>>): Set<String> =
    bySemester.entries.flatMap { (semesterId, rules) ->
        if (semesterId.isBlank()) return@flatMap emptyList()
        rules.map { rule ->
            listOf(semesterId, rule.sourceDate.toString(), rule.targetDate.toString(), rule.origin.storageValue)
                .joinToString(AUTO_COPY_FIELD_SEPARATOR.toString())
        }
    }.toSet()

/** 解码规则。脏条目**逐条丢弃**而不是整体失败——存储里一条坏数据不该让整学期的自动调课消失。 */
fun decodeAutoDayCopyRules(entries: Set<String>): Map<String, List<AutoDayCopyRule>> {
    val result = linkedMapOf<String, MutableList<AutoDayCopyRule>>()
    entries.forEach { entry ->
        val parts = entry.split(AUTO_COPY_FIELD_SEPARATOR)
        if (parts.size != 4) return@forEach
        val semesterId = parts[0]
        if (semesterId.isBlank()) return@forEach
        val sourceDate = parseIsoDateOrNull(parts[1]) ?: return@forEach
        val targetDate = parseIsoDateOrNull(parts[2]) ?: return@forEach
        val origin = AutoCopyOrigin.fromStorageValue(parts[3]) ?: return@forEach
        if (sourceDate == targetDate) return@forEach
        val rules = result.getOrPut(semesterId) { mutableListOf() }
        // 同一学期内按 id 去重，避免旧数据里的重复条目在编辑页显示两遍。
        if (rules.none { it.id == "$sourceDate->$targetDate" }) {
            rules += AutoDayCopyRule(sourceDate, targetDate, origin)
        }
    }
    return result.mapValues { (_, rules) -> rules.sortedBy { it.targetDate } }
}

/** 被用户删掉的自动规则（按学期）。`学期id \0 规则id`。 */
fun encodeAutoDayCopySuppressions(bySemester: Map<String, Set<String>>): Set<String> =
    bySemester.entries.flatMap { (semesterId, ids) ->
        if (semesterId.isBlank()) return@flatMap emptyList()
        ids.map { id -> "$semesterId$AUTO_COPY_FIELD_SEPARATOR$id" }
    }.toSet()

fun decodeAutoDayCopySuppressions(entries: Set<String>): Map<String, Set<String>> {
    val result = linkedMapOf<String, MutableSet<String>>()
    entries.forEach { entry ->
        val parts = entry.split(AUTO_COPY_FIELD_SEPARATOR)
        if (parts.size != 2) return@forEach
        val semesterId = parts[0]
        val ruleId = parts[1]
        if (semesterId.isBlank() || ruleId.isBlank()) return@forEach
        result.getOrPut(semesterId) { mutableSetOf() } += ruleId
    }
    return result
}

/**
 * 页面提示的编码：一条一行 `学期id \0 文案`。
 *
 * 文案里的换行与分隔符先换成空格——用 `\n` 做行分隔，正文里再出现 `\n` 就会串行。
 */
fun encodeAutoCopyNotices(bySemester: Map<String, List<String>>): String =
    bySemester.entries.flatMap { (semesterId, notices) ->
        if (semesterId.isBlank()) return@flatMap emptyList()
        notices.map { notice -> "$semesterId$AUTO_COPY_FIELD_SEPARATOR${sanitizeNotice(notice)}" }
    }.joinToString("\n")

fun decodeAutoCopyNotices(raw: String): Map<String, List<String>> {
    if (raw.isBlank()) return emptyMap()
    val result = linkedMapOf<String, MutableList<String>>()
    raw.split('\n').forEach { line ->
        val parts = line.split(AUTO_COPY_FIELD_SEPARATOR)
        if (parts.size != 2) return@forEach
        val semesterId = parts[0]
        val notice = parts[1].trim()
        if (semesterId.isBlank() || notice.isEmpty()) return@forEach
        val notices = result.getOrPut(semesterId) { mutableListOf() }
        if (notice !in notices) notices += notice
    }
    return result
}

private fun sanitizeNotice(value: String): String =
    value.replace('\n', ' ').replace('\r', ' ').replace(AUTO_COPY_FIELD_SEPARATOR, ' ').trim()

private fun parseIsoDateOrNull(value: String): LocalDate? =
    runCatching { LocalDate.parse(value) }.getOrNull()

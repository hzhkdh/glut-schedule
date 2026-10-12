package com.glut.schedule

import com.glut.schedule.data.model.AdjustedWorkday
import com.glut.schedule.data.model.AutoCopyOrigin
import com.glut.schedule.data.model.AutoDayCopyRule
import com.glut.schedule.data.model.HolidayInfo
import com.glut.schedule.data.model.MakeupEntry
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.adjustmentTargetDates
import com.glut.schedule.data.model.composeAutoDayCopies
import com.glut.schedule.data.model.decodeAutoCopyNotices
import com.glut.schedule.data.model.decodeAutoDayCopyRules
import com.glut.schedule.data.model.decodeAutoDayCopySuppressions
import com.glut.schedule.data.model.deriveMakeupCopies
import com.glut.schedule.data.model.encodeAutoCopyNotices
import com.glut.schedule.data.model.encodeAutoDayCopyRules
import com.glut.schedule.data.model.encodeAutoDayCopySuppressions
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.settings.autoDayCopyEnabledFromStored
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动调课（远端对照表 + 被借工作日推导 + 让位教务 + 删除抑制）的纯逻辑测试。
 *
 * 推导的钉子**全部取自真实通知**，而且是三种不同形状：
 * - **2024 国庆**（湖南大学、广东工业大学、郑州大学、重庆医科大学、国科大杭高院通知一致）：借走
 *   10/4、10/7 —— 被借日**不在假期末尾**，所以「取假期最后 N 天」那类口径会算错。
 * - **2025 国庆+中秋**（厦门理工、湖南大学、东北石油、重庆医科大学通知一致）：假期是 10/1–10/8
 *   一整段，中秋 10/6 嵌在国庆区间里，法定要按国庆 3 天 + 中秋 1 天一起算。
 * - **2026 国庆+中秋**（本校通知，见对照表）：借走 10/6、10/7 —— 而上游 timor 把 9/20 标成
 *   「中秋节前补班」，按名字挂靠会把它算进中秋那组并凑出错误的 1:1。
 *
 * 另外两条前提各有钉子：**法定天数按年份分档**（2025 年起春节 3→4 天、劳动节 1→2 天）与
 * **法定日撞周末要顺延**（2026 国庆的 10/3 是周六，顺延到 10/5）。它们任何一条破了都会让映射
 * 整体错位，而错位的结果看上去完全正常——所以这两年各有一条例专门钉住。
 *
 * 春节、元旦那几条的**方向**按日期顺序惯例（2024/2025/2026 三年国庆通知都符合），没有逐校核对
 * 通知；也就是说它们钉的是「账算对了」，不是「方向一定对」。
 */
class AutoDayCopyTest {

    private val springStart = LocalDate.of(2026, 3, 9)
    private val springEnd = LocalDate.of(2026, 7, 3)
    private val autumnStart = LocalDate.of(2026, 8, 31)
    private val autumnEnd = LocalDate.of(2027, 1, 17)

    // ---- 推导：真实通知的三种形状 ----

    /**
     * 2026 国庆+中秋（本校通知）：借走 10/6、10/7 → 9/20 补 10/6、10/10 补 10/7。
     *
     * 上游把 9/20 标成「中秋节前补班」，按名字挂靠会把它算进中秋那组（那组借 0 天），凑出一个
     * 「1 个被借日 vs 1 个补班日」的巧合。推导不看名字、且配对在连休簇这一级做，所以照样对。
     */
    @Test
    fun derivesTheRealAutumnMappingOf2026() {
        val outcome = derive(
            semesterStart = autumnStart,
            semesterEnd = autumnEnd,
            holidays = listOf(
                holiday("中秋节", "2026-09-25", "2026-09-27"),
                holiday("国庆节", "2026-10-01", "2026-10-07")
            ),
            workdays = listOf(
                AdjustedWorkday(LocalDate.of(2026, 9, 20), "中秋节前补班"),
                AdjustedWorkday(LocalDate.of(2026, 10, 10), "国庆节后补班")
            )
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 6) to LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 10, 7) to LocalDate.of(2026, 10, 10)
            ),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
        assertTrue(outcome.notices.isEmpty())
    }

    /** 2024 国庆：被借日落在假期中间（10/4）和末尾（10/7），不是「最后两天」。 */
    @Test
    fun derivesTheRealMappingOf2024WhereBorrowedDaysAreNotAtTheEnd() {
        val outcome = derive(
            semesterStart = LocalDate.of(2024, 9, 2),
            semesterEnd = LocalDate.of(2025, 1, 12),
            holidays = listOf(holiday("国庆节", "2024-10-01", "2024-10-07")),
            workdays = listOf(
                AdjustedWorkday(LocalDate.of(2024, 9, 29), "国庆节前补班"),
                AdjustedWorkday(LocalDate.of(2024, 10, 12), "国庆节后补班")
            )
        )

        assertEquals(
            listOf(
                LocalDate.of(2024, 10, 4) to LocalDate.of(2024, 9, 29),
                LocalDate.of(2024, 10, 7) to LocalDate.of(2024, 10, 12)
            ),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /**
     * 2024 中秋（湖南大学、北京交通大学、重庆医科大学、福州大学等九校通知一致）：
     * 9/14 补 **9/16** 的课。法定日（农历八月十五）是区间**末日** 9/17，区间首日 9/15 只是周日——
     * 按「区间首日起算」会把 9/16 当成法定日、9/17 当成被借日，整条映射平移一天。
     */
    @Test
    fun derivesTheRealMidAutumnMappingOf2024() {
        val outcome = derive(
            semesterStart = LocalDate.of(2024, 9, 2),
            semesterEnd = LocalDate.of(2025, 1, 12),
            holidays = listOf(holiday("中秋节", "2024-09-15", "2024-09-17")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2024, 9, 14), "中秋节前补班"))
        )

        assertEquals(
            listOf(LocalDate.of(2024, 9, 16) to LocalDate.of(2024, 9, 14)),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /** 2021 中秋（重庆医科、厦门理工、南京邮电等通知一致）：9/18 补 **9/20** 的课，法定日在区间末端 9/21。 */
    @Test
    fun derivesTheRealMidAutumnMappingOf2021() {
        val outcome = derive(
            semesterStart = LocalDate.of(2021, 9, 1),
            semesterEnd = LocalDate.of(2022, 1, 16),
            holidays = listOf(holiday("中秋节", "2021-09-19", "2021-09-21")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2021, 9, 18), "中秋节前补班"))
        )

        assertEquals(
            listOf(LocalDate.of(2021, 9, 20) to LocalDate.of(2021, 9, 18)),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /**
     * 区间首末同性质（都工作日 / 都周末）时形状说不清，宁可不生成也不猜——
     * 「认不出法定日就当区间首日」正是 2024 中秋错一天的成因。
     */
    @Test
    fun givesUpWhenTheHolidayShapeCannotLocateTheLegalDay() {
        val outcome = derive(
            // 区间必须落在学期内，否则会先被「学期外静默」挡掉，看不到形状的提示。
            semesterStart = autumnStart,
            semesterEnd = autumnEnd,
            holidays = listOf(holiday("中秋节", "2026-09-19", "2026-09-27")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 9, 20), "中秋节前补班"))
        )

        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.any { it.contains("形状说不清") })
    }

    /**
     * 2025 国庆+中秋：假期 10/1–10/8 一整段，中秋 10/6 嵌在国庆区间里。
     * 借走 10/7、10/8 → 9/28 补 10/7、10/11 补 10/8。
     *
     * 这条例还钉住**跨节日合并**：少算中秋那 1 天法定，被借日就变成 3 个，与 2 个补班日对不上。
     */
    @Test
    fun derivesTheRealMappingOf2025WithNestedMidAutumn() {
        val outcome = derive(
            semesterStart = LocalDate.of(2025, 9, 1),
            semesterEnd = LocalDate.of(2026, 1, 18),
            holidays = listOf(
                holiday("国庆节", "2025-10-01", "2025-10-08"),
                holiday("中秋节", "2025-10-06", "2025-10-06")
            ),
            workdays = listOf(
                AdjustedWorkday(LocalDate.of(2025, 9, 28), "国庆节前补班"),
                AdjustedWorkday(LocalDate.of(2025, 10, 11), "国庆节后补班")
            )
        )

        assertEquals(
            listOf(
                LocalDate.of(2025, 10, 7) to LocalDate.of(2025, 9, 28),
                LocalDate.of(2025, 10, 8) to LocalDate.of(2025, 10, 11)
            ),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /** 2026 劳动节：钉住「2025 年起法定 2 天」——按旧的 1 天算，被借日会变成 5/4、5/5 两个。 */
    @Test
    fun usesTwoStatutoryDaysForLabourDaySince2025() {
        val outcome = derive(
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"))
        )

        assertEquals(
            listOf(LocalDate.of(2026, 5, 5) to LocalDate.of(2026, 5, 9)),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /** 2026 春节：区间 2/15–2/23，法定 4 天从除夕 2/16 起算；借走 2/20、2/23。 */
    @Test
    fun usesFourStatutoryDaysForSpringFestivalSince2025() {
        val outcome = derive(
            semesterStart = LocalDate.of(2026, 2, 1),
            semesterEnd = LocalDate.of(2026, 3, 31),
            holidays = listOf(holiday("春节", "2026-02-15", "2026-02-23")),
            workdays = listOf(
                AdjustedWorkday(LocalDate.of(2026, 2, 14), "春节前补班"),
                AdjustedWorkday(LocalDate.of(2026, 2, 28), "春节后补班")
            )
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 20) to LocalDate.of(2026, 2, 14),
                LocalDate.of(2026, 2, 23) to LocalDate.of(2026, 2, 28)
            ),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /** 2026 元旦：假期 1/1–1/3、法定 1 天，借走 1/2，1/4 补它的课。 */
    @Test
    fun derivesTheBorrowedWorkdayOfNewYear2026() {
        val outcome = derive(
            semesterStart = LocalDate.of(2025, 9, 1),
            semesterEnd = LocalDate.of(2026, 1, 18),
            holidays = listOf(holiday("元旦", "2026-01-01", "2026-01-03")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 1, 4), "元旦后补班"))
        )

        assertEquals(
            listOf(LocalDate.of(2026, 1, 2) to LocalDate.of(2026, 1, 4)),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /**
     * 补班日的名字**完全不参与推导**：上游的挂靠不可靠（2026 把 9/20 标成中秋节），
     * 所以名字是空的、错的都无所谓，只要日期对就算得出来。
     */
    @Test
    fun makeupDayNamesDoNotAffectDerivation() {
        val outcome = derive(
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), ""))
        )

        assertEquals(
            listOf(LocalDate.of(2026, 5, 5) to LocalDate.of(2026, 5, 9)),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    /** 借 0 天的假期（清明、端午）什么都不说，也不会去认领别的补班日。 */
    @Test
    fun zeroBorrowedHolidaysStaySilentAndClaimNothing() {
        val outcome = derive(
            holidays = listOf(
                holiday("清明节", "2026-04-04", "2026-04-06"),
                holiday("劳动节", "2026-05-01", "2026-05-05")
            ),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"))
        )

        assertEquals(
            listOf(LocalDate.of(2026, 5, 5) to LocalDate.of(2026, 5, 9)),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
        assertTrue(outcome.notices.none { it.contains("清明节") })
    }

    /**
     * 认不出的假期名：法定天数查不到就**不猜**，整簇放弃并说明。
     * 新增一个非法定节日时，宁可不动课表，也不要按错误的法定天数配出一堆错课。
     */
    @Test
    fun givesUpForHolidayWhoseStatutoryDaysAreUnknown() {
        val outcome = derive(
            holidays = listOf(holiday("校庆日", "2026-03-16", "2026-03-17")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 3, 14), "校庆日前补班"))
        )

        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.any { it.contains("认不出的假期") })
    }

    /** 补班日离任何一段连休都太远：报一句，不硬塞给某个假期。 */
    @Test
    fun reportsMakeupDaysThatBelongToNoHoliday() {
        val outcome = derive(
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 6, 20), "补班"))
        )

        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.any { it.contains("没有对应的假期") })
    }

    /** 一份假期都没缓存（解析全失败）时同样只提示、不生成。 */
    @Test
    fun reportsMakeupDaysWhenNoHolidayIsCachedAtAll() {
        val outcome = derive(
            holidays = emptyList(),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"))
        )

        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.any { it.contains("没有对应的假期") })
    }

    @Test
    fun yieldsToAcademicAdjustmentsOnTheSameTargetDate() {
        val outcome = derive(
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班")),
            // 学期第 9 周周六 = 2026-05-09，正是那次补课。
            adjustments = listOf(adjustment(makeupWeek = 9, makeupDay = 6))
        )

        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.any { it.contains("已让位") })
    }

    /** 配对里有一头落在学期外的那条忽略掉。 */
    @Test
    fun dropsPairingsThatFallOutsideTheSemester() {
        val outcome = derive(
            semesterStart = LocalDate.of(2026, 3, 9),
            semesterEnd = LocalDate.of(2026, 5, 6),
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"))
        )

        // 借走的是 5/5，而补班日 5/9 在学期末之后：这条配对整体忽略。
        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.any { it.contains("学期范围外") })
    }

    /**
     * 寒假里的元旦、春节、五一跟秋季学期无关，不占本学期页面的提示位。
     *
     * 用例里的日期就是真机上 2026 年那一整份 timor 缓存的复刻：2026 秋季学期里，调休调课页曾
     * 列出「春节：假期内 6 个工作日、2 个补班日……已跳过」——一条与用户本学期毫无关系的提示。
     */
    @Test
    fun staysQuietAboutHolidaysThatDoNotTouchTheSemester() {
        val outcome = derive(
            semesterStart = autumnStart,
            semesterEnd = autumnEnd,
            holidays = listOf(
                holiday("元旦", "2026-01-01", "2026-01-03"),
                holiday("春节", "2026-02-15", "2026-02-23"),
                holiday("劳动节", "2026-05-01", "2026-05-05"),
                holiday("中秋节", "2026-09-25", "2026-09-27"),
                holiday("国庆节", "2026-10-01", "2026-10-07")
            ),
            workdays = listOf(
                AdjustedWorkday(LocalDate.of(2026, 1, 4), "元旦后补班"),
                AdjustedWorkday(LocalDate.of(2026, 2, 14), "春节前补班"),
                AdjustedWorkday(LocalDate.of(2026, 2, 28), "春节后补班"),
                AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"),
                AdjustedWorkday(LocalDate.of(2026, 9, 20), "中秋节前补班"),
                AdjustedWorkday(LocalDate.of(2026, 10, 10), "国庆节后补班")
            )
        )

        assertTrue(
            "学期外的假期不该出现在本学期的提示里",
            outcome.notices.none { notice ->
                listOf("元旦", "春节", "劳动节").any { notice.contains(it) }
            }
        )
        // 学期内那一段连休照旧算出正确结果。
        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 6) to LocalDate.of(2026, 9, 20),
                LocalDate.of(2026, 10, 7) to LocalDate.of(2026, 10, 10)
            ),
            outcome.rules.map { it.sourceDate to it.targetDate }
        )
    }

    @Test
    fun missingHolidayCacheExplainsItselfAndGeneratesNothing() {
        val outcome = derive(
            holidays = emptyList(),
            workdays = emptyList(),
            holidayCacheAvailable = false
        )

        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.any { it.contains("节假日数据尚未缓存") })
    }

    @Test
    fun derivationCanBeSwitchedOffWithoutTouchingTheLogic() {
        val outcome = derive(
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班")),
            derivationEnabled = false
        )

        assertTrue(outcome.rules.isEmpty())
        assertTrue(outcome.notices.isEmpty())
    }

    // ---- 让位教务 ----

    @Test
    fun adjustmentTargetDatesIgnoresRecordsWithoutAMakeupSide() {
        val dates = adjustmentTargetDates(
            listOf(
                // 停课/代课只有原时段，makeupWeek 为 0。
                adjustment(makeupWeek = 0, makeupDay = 0),
                adjustment(makeupWeek = 2, makeupDay = 3)
            ),
            springStart
        )

        // 第 2 周周一 = 2026-03-16，星期 3 = 周三 = 2026-03-18。
        assertEquals(setOf(LocalDate.of(2026, 3, 18)), dates)
    }

    /** 周次换算必须与课表渲染同一口径，否则让位会让到别的日子上。 */
    @Test
    fun adjustmentTargetDatesMatchesTheScheduleGridWeekMapping() {
        val dates = adjustmentTargetDates(listOf(adjustment(makeupWeek = 6, makeupDay = 6)), springStart)

        val expected = scheduleWeekForNumber(6, springStart).monday.plusDays(5)
        assertEquals(setOf(expected), dates)
        assertEquals(LocalDate.of(2026, 4, 18), expected)
    }

    // ---- 组合：远端优先 / 抑制 / 与手动规则去重 ----

    @Test
    fun remoteEntryWinsOverDerivedForTheSameTargetDate() {
        val composed = compose(
            // 与推导会得出的 5/5 → 5/9 撞在同一个目标日上。
            remoteEntries = listOf(MakeupEntry(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 9))),
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"))
        )

        assertEquals(1, composed.rules.size)
        assertEquals(LocalDate.of(2026, 5, 4), composed.rules.first().sourceDate)
        assertEquals(AutoCopyOrigin.REMOTE, composed.rules.first().origin)
    }

    @Test
    fun suppressedRuleIsNotRegeneratedEvenWhenItIsStillInTheTable() {
        val entry = MakeupEntry(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 14))
        val ruleId = AutoDayCopyRule(entry.source, entry.target, AutoCopyOrigin.REMOTE).id

        val composed = compose(
            remoteEntries = listOf(entry),
            holidays = emptyList(),
            workdays = emptyList(),
            suppressedIds = setOf(ruleId)
        )

        assertTrue("用户删过的规则不得复活", composed.rules.isEmpty())
    }

    @Test
    fun manualRuleWithTheSameIdIsNotDuplicatedAsAuto() {
        val entry = MakeupEntry(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 14))
        val ruleId = AutoDayCopyRule(entry.source, entry.target, AutoCopyOrigin.REMOTE).id

        val composed = compose(
            remoteEntries = listOf(entry),
            holidays = emptyList(),
            workdays = emptyList(),
            manualRuleIds = setOf(ruleId)
        )

        assertTrue(composed.rules.isEmpty())
    }

    @Test
    fun autoCopyYieldsToAcademicMakesWhenTheCardIsAlreadyThere() {
        val composed = compose(
            remoteEntries = listOf(MakeupEntry(LocalDate.of(2026, 3, 16), LocalDate.of(2026, 3, 14))),
            holidays = emptyList(),
            workdays = emptyList(),
            yieldedDates = setOf(LocalDate.of(2026, 3, 14))
        )

        assertTrue(composed.rules.isEmpty())
        assertTrue(composed.notices.any { it.contains("已让位") })
    }

    /**
     * 对照表收录了本学期时，协调器会把推导整学期关掉（`derivationEnabled = false`）——
     * 那时只剩远端条目，推导不会再补出学校通知里没有的日子。
     */
    @Test
    fun derivationOffLeavesOnlyRemoteEntries() {
        val composed = compose(
            remoteEntries = listOf(MakeupEntry(LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 9))),
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(
                AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"),
                AdjustedWorkday(LocalDate.of(2026, 6, 20), "补班")
            ),
            derivationEnabled = false
        )

        assertEquals(
            listOf(LocalDate.of(2026, 5, 5) to LocalDate.of(2026, 5, 9)),
            composed.rules.map { it.sourceDate to it.targetDate }
        )
        assertTrue(composed.notices.isEmpty())
    }

    @Test
    fun composeIsDeterministicSoRecomputeIsIdempotent() {
        fun run() = compose(
            remoteEntries = listOf(MakeupEntry(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 9))),
            holidays = listOf(holiday("劳动节", "2026-05-01", "2026-05-05")),
            workdays = listOf(AdjustedWorkday(LocalDate.of(2026, 5, 9), "劳动节后补班"))
        )

        val first = run()
        val second = run()

        assertEquals(first.rules, second.rules)
        assertEquals(first.notices, second.notices)
    }

    // ---- 编解码 ----

    @Test
    fun ruleEncodingRoundTripsAndKeepsOrigin() {
        val rules = mapOf(
            "guilin:2026:autumn" to listOf(
                AutoDayCopyRule(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 9, 20), AutoCopyOrigin.REMOTE),
                AutoDayCopyRule(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 10), AutoCopyOrigin.DERIVED)
            )
        )

        val decoded = decodeAutoDayCopyRules(encodeAutoDayCopyRules(rules))

        assertEquals(rules["guilin:2026:autumn"], decoded["guilin:2026:autumn"])
    }

    @Test
    fun ruleDecodingDropsMalformedEntriesInsteadOfLosingTheWholeSemester() {
        val valid = encodeAutoDayCopyRules(
            mapOf(
                "guilin:2026:autumn" to listOf(
                    AutoDayCopyRule(LocalDate.of(2026, 10, 6), LocalDate.of(2026, 9, 20), AutoCopyOrigin.REMOTE)
                )
            )
        )
        val stored = valid + setOf(
            "guilin:2026:autumn\u00002026-10-06\u00002026-09-20",                    // 少一段
            "guilin:2026:autumn\u0000bad-date\u00002026-09-20\u0000remote",         // 日期非法
            "guilin:2026:autumn\u00002026-10-06\u00002026-09-20\u0000whatever",     // 来源未知
            "guilin:2026:autumn\u00002026-10-06\u00002026-10-06\u0000remote",       // 源=目标
            "\u00002026-10-06\u00002026-09-20\u0000remote",                          // 学期 id 为空
            "guilin:2026:autumn\u00002026-10-06\u00002026-09-20\u0000remote\u0000x" // 多一段
        )

        val decoded = decodeAutoDayCopyRules(stored)

        assertEquals(1, decoded.getValue("guilin:2026:autumn").size)
    }

    @Test
    fun suppressionEncodingRoundTripsAndDropsBlankEntries() {
        val encoded = encodeAutoDayCopySuppressions(
            mapOf("guilin:2026:autumn" to setOf("2026-10-06->2026-09-20"))
        ) + setOf("\u0000only-id", "semester\u0000")

        val decoded = decodeAutoDayCopySuppressions(encoded)

        assertEquals(setOf("2026-10-06->2026-09-20"), decoded["guilin:2026:autumn"])
    }

    @Test
    fun noticeEncodingStripsNewlinesSoOneNoticeStaysOnOneLine() {
        val encoded = encodeAutoCopyNotices(
            mapOf("guilin:2026:autumn" to listOf("第一行\n第二行", "另一条\u0000提示"))
        )

        val decoded = decodeAutoCopyNotices(encoded)

        assertEquals(listOf("第一行 第二行", "另一条 提示"), decoded["guilin:2026:autumn"])
    }

    @Test
    fun autoDayCopySwitchDefaultsToOff() {
        assertFalse(autoDayCopyEnabledFromStored(null))
        assertFalse(autoDayCopyEnabledFromStored(false))
        assertTrue(autoDayCopyEnabledFromStored(true))
        assertNull(decodeAutoDayCopyRules(emptySet())["guilin:2026:autumn"])
    }

    // ---- helpers ----

    private fun derive(
        holidays: List<HolidayInfo>,
        workdays: List<AdjustedWorkday>,
        semesterStart: LocalDate = springStart,
        semesterEnd: LocalDate = springEnd,
        holidayCacheAvailable: Boolean = true,
        adjustments: List<SemesterAdjustment> = emptyList(),
        derivationEnabled: Boolean = true
    ) = deriveMakeupCopies(
        semesterStartDate = semesterStart,
        semesterEndDate = semesterEnd,
        adjustedWorkdays = workdays,
        holidays = holidays,
        holidayCacheAvailable = holidayCacheAvailable,
        yieldedDates = adjustmentTargetDates(adjustments, semesterStart),
        excludedIds = emptySet(),
        derivationEnabled = derivationEnabled
    )

    private fun compose(
        remoteEntries: List<MakeupEntry>,
        holidays: List<HolidayInfo>,
        workdays: List<AdjustedWorkday>,
        yieldedDates: Set<LocalDate> = emptySet(),
        suppressedIds: Set<String> = emptySet(),
        manualRuleIds: Set<String> = emptySet(),
        derivationEnabled: Boolean = true
    ) = composeAutoDayCopies(
        semesterStartDate = springStart,
        semesterEndDate = springEnd,
        remoteEntries = remoteEntries,
        adjustedWorkdays = workdays,
        holidays = holidays,
        holidayCacheAvailable = true,
        yieldedDates = yieldedDates,
        suppressedIds = suppressedIds,
        manualRuleIds = manualRuleIds,
        derivationEnabled = derivationEnabled
    )

    private fun holiday(name: String, start: String, end: String) = HolidayInfo(
        name = name,
        startDate = start,
        endDate = end,
        daysOff = java.time.temporal.ChronoUnit.DAYS
            .between(LocalDate.parse(start), LocalDate.parse(end)).toInt() + 1
    )

    private fun adjustment(makeupWeek: Int, makeupDay: Int) = SemesterAdjustment(
        id = "adj-$makeupWeek-$makeupDay",
        type = "补课",
        title = "测试课程",
        teacher = "测试教师",
        originalWeek = 1,
        originalDay = 1,
        originalStartSection = 1,
        originalEndSection = 2,
        originalRoom = "00001",
        makeupWeek = makeupWeek,
        makeupDay = makeupDay,
        makeupStartSection = 1,
        makeupEndSection = 2,
        makeupRoom = "00002"
    )
}

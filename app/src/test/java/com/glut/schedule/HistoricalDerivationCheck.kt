package com.glut.schedule

import com.glut.schedule.data.model.composeAutoDayCopies
import com.glut.schedule.service.holiday.TimorHolidayCalendarParser
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 历年回归：用 App 自己的解析器与推导跑 `src/test/resources/holidays/<年>.json`（timor 原文，
 * 与运行时缓存同格式），窗口取整年、前后各一年合并（与 `AutoDayCopyCoordinator` 同口径）。
 *
 * 两个用途：
 * - [assertVerifiedMappingsFromRealNotices] 把**已经用真实教务处通知核对过**的映射钉成断言；
 * - [printDerivedMappingsForHistoricalYears] 把没核对的年份打印出来，供人工比对（含每条放弃的原因）。
 *
 * 推导的三条前提（法定天数表、法定日定位与顺延、按日期顺序配对）在这里被历年数据反复摩擦——
 * 2024/2021 中秋就是被这套数据抓出来的「整体平移一天」。
 */
class HistoricalDerivationCheck {

    private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /**
     * 已用真实通知核对过的映射，写成 `补班日<-被借日`。
     *
     * - 2022 国庆：10/8 补 10/6、10/9 补 10/7（佛山科学技术学院、广东药科大学、西北工业大学、
     *   郑州大学、电子科技大学、广东技术师范大学等九校一致）
     * - 2023 国庆+中秋：10/7 补 10/5、10/8 补 10/6（安徽理工、福州大学、西华师范、衡阳师范、
     *   天津职业技术师范大学等多校一致）
     * - 2024 中秋：9/14 补 9/16（湖南大学、北京交通大学、重庆医科大学、福州大学等九校一致）
     * - 2024 国庆：9/29 补 10/4、10/12 补 10/7（湖南大学、广东工业、郑州大学、重庆医科、国科大杭高院一致）
     * - 2025 国庆+中秋：9/28 补 10/7、10/11 补 10/8（厦门理工、湖南大学、东北石油、重庆医科一致）
     * - 2026 国庆+中秋：9/20 补 10/6、10/10 补 10/7（本校对照表）
     *
     * 2022/2023 那四条同时是「跨年同名日期不合并」与「法定块只算第一段」两条修复的回归钉子：
     * 修之前这两年整年一条都推不出来。
     */
    private val verifiedMappings = mapOf(
        2022 to setOf("2022-10-08<-2022-10-06", "2022-10-09<-2022-10-07"),
        2023 to setOf("2023-10-07<-2023-10-05", "2023-10-08<-2023-10-06"),
        2024 to setOf("2024-09-14<-2024-09-16", "2024-09-29<-2024-10-04", "2024-10-12<-2024-10-07"),
        2025 to setOf("2025-09-28<-2025-10-07", "2025-10-11<-2025-10-08"),
        2026 to setOf("2026-09-20<-2026-10-06", "2026-10-10<-2026-10-07")
    )

    @Test
    fun assertVerifiedMappingsFromRealNotices() {
        verifiedMappings.forEach { (year, expected) ->
            val actual = derivedMappings(year)
            expected.forEach { mapping ->
                assertTrue("$year 年缺少已核对的映射 $mapping（实际：$actual）", mapping in actual)
            }
        }

        // 2024 中秋曾经算错成「9/14 补 9/17」——那条必须不再出现，且同年不许出现「补 9/17」。
        assertFalse("2024 中秋不得再映射到 9/17", "2024-09-14<-2024-09-17" in derivedMappings(2024))
    }

    @Test
    fun printDerivedMappingsForHistoricalYears() {
        (2020..2026).forEach { year ->
            val calendars = calendarsAround(year)
            val holidays = calendars.flatMap { it.holidays }
            val workdays = calendars.flatMap { it.adjustedWorkdays }.sortedBy { it.date }
            val composed = composeAutoDayCopies(
                semesterStartDate = LocalDate.of(year, 1, 1),
                semesterEndDate = LocalDate.of(year, 12, 31),
                remoteEntries = emptyList(),
                adjustedWorkdays = workdays,
                holidays = holidays,
                holidayCacheAvailable = true,
                yieldedDates = emptySet(),
                suppressedIds = emptySet(),
                manualRuleIds = emptySet()
            )

            println("===== $year 年 =====")
            println(
                "  假期: " + holidays.filter { it.startDate.startsWith("$year-") }
                    .joinToString("，") { "${it.name} ${it.startDate}~${it.endDate}" }
            )
            println(
                "  补班日（timor 原名）: " + workdays.filter { it.date.year == year }
                    .joinToString("，") { "${it.date}(${it.name})" }
            )
            composed.rules.forEach { rule ->
                val weekday = weekdays[rule.targetDate.dayOfWeek.value - 1]
                println("  推导: ${rule.targetDate}($weekday) 上 ${rule.sourceDate} 的课")
            }
            if (composed.rules.isEmpty()) println("  推导: （无）")
            composed.notices.forEach { println("  提示: $it") }
            println()
        }
    }

    /** 某年推导出的全部映射，`补班日<-被借日`。 */
    private fun derivedMappings(year: Int): Set<String> {
        val calendars = calendarsAround(year)
        val composed = composeAutoDayCopies(
            semesterStartDate = LocalDate.of(year, 1, 1),
            semesterEndDate = LocalDate.of(year, 12, 31),
            remoteEntries = emptyList(),
            adjustedWorkdays = calendars.flatMap { it.adjustedWorkdays }.sortedBy { it.date },
            holidays = calendars.flatMap { it.holidays },
            holidayCacheAvailable = true,
            yieldedDates = emptySet(),
            suppressedIds = emptySet(),
            manualRuleIds = emptySet()
        )
        return composed.rules.mapTo(mutableSetOf()) { "${it.targetDate}<-${it.sourceDate}" }
    }

    private fun calendarsAround(year: Int) = (year - 1..year + 1).mapNotNull { source ->
        readResource("/holidays/$source.json")?.let { TimorHolidayCalendarParser.parse(it, source) }
    }

    private fun readResource(path: String): String? =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.use { it.readText() }
}

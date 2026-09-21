package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.countManualCopySourceBlocks
import com.glut.schedule.data.model.decodeManualDayCopyRules
import com.glut.schedule.data.model.encodeManualDayCopyRules
import com.glut.schedule.data.model.manualCopyBlocksForWeek
import com.glut.schedule.data.model.scheduleWeekForNumber
import com.glut.schedule.data.model.validateManualDayCopy
import com.glut.schedule.data.model.weekNumberForDate
import com.glut.schedule.service.holiday.TimorHolidayCalendarParser
import com.glut.schedule.ui.components.ScheduleDayMarker
import com.glut.schedule.ui.components.scheduleDayMarker
import com.glut.schedule.ui.pages.courseBlocksByWeek
import com.glut.schedule.ui.pages.localDateToUtcMillis
import com.glut.schedule.ui.pages.nextDateWithinSemester
import com.glut.schedule.ui.pages.utcMillisToLocalDate
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调休调课（整天复制）与日期角标的纯逻辑测试。
 *
 * 行为口径与小程序 `utils/manualDayCopies.js`、`utils/holidayCalendar.js` 一一对应，
 * 两端任何一侧改动后都应能在这些用例里立刻看出差异。
 */
class ManualDayCopyTest {

    // 2026-03-09 是第 1 周周一，因此第 2 周周一为 2026-03-16，第 2 周周三为 2026-03-18。
    private val semesterStartMonday = LocalDate.of(2026, 3, 9)

    private val courses = listOf(
        course(
            id = "math",
            title = "高等数学",
            occurrences = listOf(
                occurrence(
                    id = "math-w2-wed",
                    courseId = "math",
                    dayOfWeek = 3,
                    weekText = "第2周"
                )
            )
        )
    )

    @Test
    fun manualCopyOnlyAppendsToTargetDayAndKeepsSourceDay() {
        val rule = ManualDayCopyRule(
            sourceDate = LocalDate.of(2026, 3, 18),
            targetDate = LocalDate.of(2026, 3, 21)
        )

        val copied = manualCopyBlocksForWeek(
            courses = courses,
            rules = listOf(rule),
            weekNumber = 2,
            weekMonday = semesterStartMonday.plusWeeks(1)
        )

        assertEquals(1, copied.size)
        assertEquals("math", copied.single().course.id)
        assertEquals(6, copied.single().occurrence.dayOfWeek)
        assertEquals("第2周", copied.single().occurrence.weekText)
        assertTrue(copied.single().occurrence.id.startsWith("math-w2-wed-manual-copy-"))
        // 规则不修改原课程：源日期课程必须原样保留。
        assertEquals(3, courses.single().occurrences.single().dayOfWeek)
    }

    @Test
    fun manualCopyOnlyFiresOnTheTargetWeek() {
        val rule = ManualDayCopyRule(
            sourceDate = LocalDate.of(2026, 3, 18),
            targetDate = LocalDate.of(2026, 3, 21)
        )

        val otherWeek = manualCopyBlocksForWeek(
            courses = courses,
            rules = listOf(rule),
            weekNumber = 3,
            weekMonday = semesterStartMonday.plusWeeks(1)
        )

        assertTrue(otherWeek.isEmpty())
    }

    @Test
    fun manualCopyReadsSourceCoursesFromTheTargetWeekOffset() {
        // 跨周复制：第 3 周的周一课程复制到第 4 周的周一。
        val weekly = listOf(
            course(
                id = "physics",
                title = "大学物理",
                occurrences = listOf(
                    occurrence(
                        id = "physics-w3-mon",
                        courseId = "physics",
                        dayOfWeek = 1,
                        weekText = "第3周"
                    )
                )
            )
        )
        val rule = ManualDayCopyRule(
            sourceDate = semesterStartMonday.plusWeeks(2),
            targetDate = semesterStartMonday.plusWeeks(3)
        )

        val copied = manualCopyBlocksForWeek(
            courses = weekly,
            rules = listOf(rule),
            weekNumber = 4,
            weekMonday = semesterStartMonday.plusWeeks(3)
        )

        assertEquals(1, copied.size)
        assertEquals(1, copied.single().occurrence.dayOfWeek)
        assertEquals("第4周", copied.single().occurrence.weekText)
    }

    @Test
    fun validateRejectsSameOrMissingDates() {
        assertEquals(
            "原日期和目标日期不能相同",
            validateManualDayCopy(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 7))
        )
        assertEquals("请选择有效日期", validateManualDayCopy(null, LocalDate.of(2026, 10, 10)))
        assertNull(
            validateManualDayCopy(LocalDate.of(2026, 10, 7), LocalDate.of(2026, 10, 10))
        )
    }

    @Test
    fun sourceBlockCountCountsOnlyThatDaysActiveCourses() {
        assertEquals(
            1,
            countManualCopySourceBlocks(courses, LocalDate.of(2026, 3, 18), semesterStartMonday)
        )
        // 同周周四没有课。
        assertEquals(
            0,
            countManualCopySourceBlocks(courses, LocalDate.of(2026, 3, 19), semesterStartMonday)
        )
        // 学期开始之前不算任何一周。
        assertEquals(
            0,
            countManualCopySourceBlocks(courses, LocalDate.of(2026, 3, 10), semesterStartMonday)
        )
        assertEquals(0, countManualCopySourceBlocks(courses, null, semesterStartMonday))
    }

    @Test
    fun weekNumberForDateCountsFromSemesterMonday() {
        assertEquals(1, weekNumberForDate(semesterStartMonday, semesterStartMonday))
        assertEquals(1, weekNumberForDate(semesterStartMonday.plusDays(6), semesterStartMonday))
        assertEquals(2, weekNumberForDate(semesterStartMonday.plusDays(7), semesterStartMonday))
        assertEquals(0, weekNumberForDate(semesterStartMonday.minusDays(1), semesterStartMonday))
    }

    @Test
    fun courseBlocksByWeekAppliesRulesOnlyOnTargetWeek() {
        val rule = ManualDayCopyRule(
            sourceDate = LocalDate.of(2026, 3, 18),
            targetDate = LocalDate.of(2026, 3, 21)
        )

        val blocksByWeek = courseBlocksByWeek(
            courses = courses,
            maxWeek = 19,
            semesterStartMonday = semesterStartMonday,
            rules = listOf(rule)
        )

        // 第 2 周：原有课 + 追加副本。
        assertEquals(2, blocksByWeek.getValue(2).size)
        assertEquals(1, blocksByWeek.getValue(2).count { it.occurrence.dayOfWeek == 6 })
        // 第 3 周：规则不生效。
        assertEquals(0, blocksByWeek.getValue(3).size)
        assertEquals(19, blocksByWeek.size)
    }

    @Test
    fun weekMondayForCopiesMatchesTheScheduleWeekMapping() {
        // 追加副本用的周一必须与页面渲染用的周一一致，否则副本会落到错误的周。
        val week = scheduleWeekForNumber(7, semesterStartMonday, 19)

        assertEquals(semesterStartMonday.plusWeeks(6), week.monday)
        assertEquals(week.monday, LocalDate.of(2026, 4, 20))
    }

    @Test
    fun encodingKeepsRulesIsolatedPerSemester() {
        val early = ManualDayCopyRule(
            sourceDate = LocalDate.of(2026, 10, 7),
            targetDate = LocalDate.of(2026, 10, 10)
        )
        val late = ManualDayCopyRule(
            sourceDate = LocalDate.of(2026, 11, 2),
            targetDate = LocalDate.of(2026, 11, 3)
        )
        val encoded = encodeManualDayCopyRules(mapOf("guilin:2026:autumn" to listOf(early, late)))

        val decoded = decodeManualDayCopyRules(encoded)

        assertEquals(listOf(early, late), decoded.getValue("guilin:2026:autumn"))
        assertNull(decoded["guilin:2026:spring"])
    }

    @Test
    fun decodingDropsUnparsableEntriesInsteadOfLosingTheWholeSemester() {
        val valid = ManualDayCopyRule(
            sourceDate = LocalDate.of(2026, 10, 7),
            targetDate = LocalDate.of(2026, 10, 10)
        )
        val validEntry = encodeManualDayCopyRules(mapOf("guilin:2026:autumn" to listOf(valid)))
        val stored = validEntry + setOf(
            "guilin:2026:autumn\u0000not-a-date\u00002026-10-10",
            "guilin:2026:autumn\u00002026-10-07",
            "\u00002026-10-07\u00002026-10-10",
            // 同一条规则重复写入时按 id 去重。
            "guilin:2026:autumn\u00002026-10-07\u00002026-10-10"
        )

        val decoded = decodeManualDayCopyRules(stored)

        assertEquals(listOf(valid), decoded.getValue("guilin:2026:autumn"))
    }

    @Test
    fun decodingDropsRulesWhoseTwoDatesAreIdentical() {
        val decoded = decodeManualDayCopyRules(
            setOf("guilin:2026:autumn\u00002026-10-07\u00002026-10-07")
        )

        assertTrue(decoded.isEmpty())
    }

    @Test
    fun holidayMarkerOnlyMarksLegalHolidaysAndLetsAdjustmentWin() {
        val calendar = TimorHolidayCalendarParser.parse(
            """
            {"code":0,"holiday":{
              "10-01":{"holiday":true,"name":"国庆节"},
              "10-02":{"holiday":true,"name":"国庆节"},
              "10-10":{"holiday":false,"name":"国庆节后补班"}
            }}
            """.trimIndent(),
            year = 2026
        )!!

        val holidayDates = calendar.holidayDates
        // 调课目标日既有法定放假日（10-01），也有补班日（10-10）。
        val adjustmentDates = setOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 10))

        assertEquals(
            setOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2)),
            holidayDates
        )
        // 补班日不是放假日，不能标「休」。
        assertTrue(LocalDate.of(2026, 10, 10) !in holidayDates)

        assertEquals(
            ScheduleDayMarker.HOLIDAY,
            scheduleDayMarker(LocalDate.of(2026, 10, 2), holidayDates, adjustmentDates)
        )
        // 同一日期既是假期又有手动调课时，「调」优先。
        assertEquals(
            ScheduleDayMarker.ADJUSTMENT,
            scheduleDayMarker(LocalDate.of(2026, 10, 1), holidayDates, adjustmentDates)
        )
        // 补班日上的手动调课照常标「调」，不会被「不是假期」吞掉。
        assertEquals(
            ScheduleDayMarker.ADJUSTMENT,
            scheduleDayMarker(LocalDate.of(2026, 10, 10), holidayDates, adjustmentDates)
        )
        // 既非假期也无调课的补班日没有角标。
        assertNull(scheduleDayMarker(LocalDate.of(2026, 10, 10), holidayDates, emptySet()))
        // 不显示日期（历史学期缺少权威校历）时没有角标落点。
        assertNull(scheduleDayMarker(null, holidayDates, adjustmentDates))
        assertNull(scheduleDayMarker(LocalDate.of(2026, 10, 3), holidayDates, adjustmentDates))
    }

    @Test
    fun datePickerMillisRoundTripWithoutTimezoneDrift() {
        val date = LocalDate.of(2026, 10, 1)

        assertEquals(date, utcMillisToLocalDate(localDateToUtcMillis(date)))
    }

    @Test
    fun nextDateKeepsTargetInsideSemester() {
        val end = LocalDate.of(2027, 1, 17)

        assertEquals(
            LocalDate.of(2026, 10, 8),
            nextDateWithinSemester(LocalDate.of(2026, 10, 7), end)
        )
        // 学期最后一天没有次日，保持原日期而不是越界。
        assertEquals(end, nextDateWithinSemester(end, end))
    }

    private fun course(
        id: String,
        title: String,
        occurrences: List<CourseOccurrence>
    ): ScheduleCourse = ScheduleCourse(
        id = id,
        title = title,
        room = "06408D",
        teacher = "待确认",
        colorHex = "#3B82F6",
        occurrences = occurrences
    )

    private fun occurrence(
        id: String,
        courseId: String,
        dayOfWeek: Int,
        weekText: String
    ): CourseOccurrence = CourseOccurrence(
        id = id,
        courseId = courseId,
        dayOfWeek = dayOfWeek,
        startSection = 1,
        endSection = 2,
        weekText = weekText,
        note = ""
    )
}

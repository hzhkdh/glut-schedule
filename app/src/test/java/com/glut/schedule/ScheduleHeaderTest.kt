package com.glut.schedule

import com.glut.schedule.ui.components.scheduleHeaderPrimaryText
import com.glut.schedule.ui.components.isWeekTitleClickable
import com.glut.schedule.ui.components.scheduleGridMonthHeaderStartPaddingDp
import com.glut.schedule.ui.components.scheduleGridMonthHeaderTopPaddingDp
import com.glut.schedule.ui.components.scheduleGridMonthText
import com.glut.schedule.ui.components.scheduleCalendarDays
import com.glut.schedule.ui.components.semesterMenuStatusText
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ScheduleWeek
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.CampusType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ScheduleHeaderTest {
    @Test
    fun semesterMenuMarksTheViewedHistoricalSemester() {
        val current = semester("guilin:2026:autumn", isCurrent = true)
        val cached = semester("guilin:2025:spring", isCurrent = false)

        // 当前学期永远显示「当前」——即使你正在看它，也不该因此丢掉
        // 「哪一个是当前学期」这个信息（这是用户明确选定过的取舍）。
        assertEquals("当前", semesterMenuStatusText(current, viewedSemesterId = "guilin:2026:autumn"))
        assertEquals("当前", semesterMenuStatusText(current, viewedSemesterId = null))

        // 正在查看的**历史**学期才显示「正在查看」。
        assertEquals("正在查看", semesterMenuStatusText(cached, viewedSemesterId = "guilin:2025:spring"))
        assertEquals("已缓存", semesterMenuStatusText(cached, viewedSemesterId = null))
        assertEquals("已缓存", semesterMenuStatusText(cached, viewedSemesterId = "guilin:2024:autumn"))
    }

    @Test
    fun headerPrimaryTextShowsDayOnlyForCurrentWeek() {
        assertEquals("第9周 周日", scheduleHeaderPrimaryText(9, 9, "周日"))
    }

    @Test
    fun currentSemesterHeaderMarksNonCurrentWeekWithEnglishParentheses() {
        assertEquals("第6周(非本周)", scheduleHeaderPrimaryText(6, 9, "周日"))
        assertEquals("第14周(非本周)", scheduleHeaderPrimaryText(14, 9, "周日"))
    }

    @Test
    fun historicalHeaderUsesPlainWeekTitleAndDisablesClick() {
        assertEquals("第3周", scheduleHeaderPrimaryText(3, 9, "周日", isHistorical = true))
        assertEquals(false, isWeekTitleClickable(isHistorical = true))
        assertEquals(true, isWeekTitleClickable(isHistorical = false))
    }

    @Test
    fun gridMonthTextUsesWeekMondayMonth() {
        assertEquals("5月", scheduleGridMonthText(LocalDate.of(2026, 5, 11)))
        assertEquals("7月", scheduleGridMonthText(LocalDate.of(2026, 7, 13)))
    }

    @Test
    fun gridMonthHeaderKeepsMonthAlignedWithWeekdayRow() {
        assertEquals(15, scheduleGridMonthHeaderStartPaddingDp())
        assertEquals(6, scheduleGridMonthHeaderTopPaddingDp())
    }

    @Test
    fun calendarDaysExposeDatesAndHighlightOnlyTheActualToday() {
        val currentWeek = ScheduleWeek(1, LocalDate.of(2026, 7, 27))
        val currentDays = scheduleCalendarDays(
            week = currentWeek,
            today = LocalDate.of(2026, 7, 30),
            dayCount = 5,
            showCalendarDates = true
        )

        assertEquals(listOf("一", "二", "三", "四", "五"), currentDays.map { it.name })
        assertEquals(listOf(27, 28, 29, 30, 31), currentDays.map { it.date?.dayOfMonth })
        assertEquals(listOf(false, false, false, true, false), currentDays.map { it.isToday })

        val otherWeek = scheduleCalendarDays(
            week = ScheduleWeek(2, LocalDate.of(2026, 8, 3)),
            today = LocalDate.of(2026, 7, 30),
            dayCount = 7,
            showCalendarDates = true
        )
        assertEquals(7, otherWeek.size)
        assertEquals(false, otherWeek.any { it.isToday })
    }

    private fun semester(
        id: String,
        isCurrent: Boolean
    ) = AcademicSemester(
        id = id,
        campus = CampusType.GUILIN,
        portalYear = 2026,
        portalYearId = "46",
        season = SemesterSeason.SPRING,
        portalTermId = "1",
        displayName = id,
        isCurrent = isCurrent,
        cacheStatus = SemesterCacheStatus.CACHED
    )
}

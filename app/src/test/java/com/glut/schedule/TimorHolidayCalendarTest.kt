package com.glut.schedule

import com.glut.schedule.service.holiday.CalendarDayKind
import com.glut.schedule.service.holiday.CalendarDayInfo
import com.glut.schedule.service.holiday.TimorHolidayCalendarParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TimorHolidayCalendarTest {

    @Test
    fun parsesHolidayNamesAdjustedWorkdaysAndOrdinaryDates() {
        val calendar = TimorHolidayCalendarParser.parse(
            json = """
                {
                  "code": 0,
                  "holiday": {
                    "02-16": {"holiday": true, "name": "除夕"},
                    "02-17": {"holiday": true, "name": "初一"},
                    "02-21": {"holiday": false, "name": "春节后补班"},
                    "05-01": {"holiday": true, "name": "劳动节"}
                  }
                }
            """.trimIndent(),
            year = 2026
        )!!

        assertEquals(
            CalendarDayKind.HOLIDAY,
            calendar.dayInfo(LocalDate.of(2026, 2, 16)).kind
        )
        assertEquals(
            "春节",
            calendar.dayInfo(LocalDate.of(2026, 2, 16)).holidayName
        )
        assertEquals(
            CalendarDayKind.ADJUSTED_WORKDAY,
            calendar.dayInfo(LocalDate.of(2026, 2, 21)).kind
        )
        // 补班名必须**原样保留**（不能被 normalizeHolidayName 塌缩成「春节」）：
        // 自动调课的推导靠它判断「属于哪个节、是节前还是节后」。
        assertEquals(
            "春节后补班",
            calendar.dayInfo(LocalDate.of(2026, 2, 21)).holidayName
        )
        assertEquals(
            CalendarDayKind.ORDINARY,
            calendar.dayInfo(LocalDate.of(2026, 3, 8)).kind
        )
        assertEquals(
            listOf("春节", "劳动节"),
            calendar.holidays.map { it.name }
        )
        assertEquals("2026-02-16", calendar.holidays.first().startDate)
        assertEquals("2026-02-17", calendar.holidays.first().endDate)
        assertEquals(2, calendar.holidays.first().daysOff)
    }

    @Test
    fun adjustedWorkdaysExposeTheRawNameAndAreSorted() {
        val calendar = TimorHolidayCalendarParser.parse(
            json = """
                {
                  "code": 0,
                  "holiday": {
                    "02-21": {"holiday": false, "name": "春节后补班"},
                    "02-14": {"holiday": false, "name": "春节前补班"},
                    "02-17": {"holiday": true, "name": "初一"}
                  }
                }
            """.trimIndent(),
            year = 2026
        )!!

        assertEquals(
            listOf(
                LocalDate.of(2026, 2, 14) to "春节前补班",
                LocalDate.of(2026, 2, 21) to "春节后补班"
            ),
            calendar.adjustedWorkdays.map { it.date to it.name }
        )
        // 放假日不进补班日列表，否则推导会把假期本身当成补班日去补课。
        assertTrue(calendar.adjustedWorkdays.none { it.date == LocalDate.of(2026, 2, 17) })
    }

    @Test
    fun holidayWithoutANameIsIgnoredInsteadOfRenderingAnEmptyPlaceholder() {
        val calendar = TimorHolidayCalendarParser.parse(
            json = """
                {
                  "code": 0,
                  "holiday": {
                    "10-01": {"holiday": true, "name": "   "}
                  }
                }
            """.trimIndent(),
            year = 2026
        )!!

        assertEquals(
            CalendarDayKind.ORDINARY,
            calendar.dayInfo(LocalDate.of(2026, 10, 1)).kind
        )
        assertTrue(calendar.holidays.isEmpty())
    }

    @Test
    fun malformedOrUnsuccessfulDocumentsRemainUnknown() {
        assertNull(TimorHolidayCalendarParser.parse("{", 2026))
        assertNull(
            TimorHolidayCalendarParser.parse(
                """{"code":1,"holiday":{}}""",
                2026
            )
        )
    }

    @Test
    fun unpublishedYearYieldsNoHolidaysSoCallersCanSpotItAsMissing() {
        // 2027 年放假安排未公布时 timor 返回的是合法但内容为空的文档。
        // 解析层不自作主张判失败（问候语等调用方依赖非 null），但必须解析不出任何假期，
        // 调用方才能据此判定「这一年还没拿到数据」并重试。
        val calendar = TimorHolidayCalendarParser.parse("""{"code":0,"holiday":{}}""", 2027)!!

        assertTrue(calendar.holidays.isEmpty())
        assertTrue(calendar.holidayDates.isEmpty())
        assertEquals(
            CalendarDayKind.ORDINARY,
            calendar.dayInfo(LocalDate.of(2027, 1, 1)).kind
        )
    }

    @Test
    fun cachedDayRequiresAValidCacheFromTheSameYear() {
        val json = """
            {
              "code": 0,
              "holiday": {
                "10-01": {"holiday": true, "name": "国庆节"}
              }
            }
        """.trimIndent()
        val today = LocalDate.of(2026, 10, 1)

        assertEquals(
            CalendarDayInfo(CalendarDayKind.HOLIDAY, "国庆节"),
            TimorHolidayCalendarParser.resolveCachedDay(
                json = json,
                cacheDate = "2026-01-01",
                date = today
            )
        )
        assertEquals(
            CalendarDayKind.UNKNOWN,
            TimorHolidayCalendarParser.resolveCachedDay(
                json = json,
                cacheDate = "2025-12-31",
                date = today
            ).kind
        )
        assertEquals(
            CalendarDayKind.UNKNOWN,
            TimorHolidayCalendarParser.resolveCachedDay(
                json = "{",
                cacheDate = "2026-01-01",
                date = today
            ).kind
        )
    }
}

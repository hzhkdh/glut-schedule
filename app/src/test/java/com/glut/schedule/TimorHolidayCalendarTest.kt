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

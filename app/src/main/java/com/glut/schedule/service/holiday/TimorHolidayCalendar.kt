package com.glut.schedule.service.holiday

import com.glut.schedule.data.model.HolidayInfo
import org.json.JSONObject
import java.time.LocalDate

enum class CalendarDayKind {
    HOLIDAY,
    ADJUSTED_WORKDAY,
    ORDINARY,
    UNKNOWN
}

data class CalendarDayInfo(
    val kind: CalendarDayKind,
    val holidayName: String = ""
)

data class TimorHolidayCalendar(
    val holidays: List<HolidayInfo>,
    private val days: Map<LocalDate, CalendarDayInfo>
) {
    fun dayInfo(date: LocalDate): CalendarDayInfo =
        days[date] ?: CalendarDayInfo(CalendarDayKind.ORDINARY)
}

object TimorHolidayCalendarParser {
    private val springFestivalSubNames = setOf(
        "除夕", "初一", "初二", "初三", "初四", "初五", "初六", "初七",
        "春节前补班", "春节后补班"
    )

    fun parse(json: String, year: Int): TimorHolidayCalendar? = runCatching {
        val root = JSONObject(json)
        if (root.optInt("code", -1) != 0) return null
        val holidayObject = root.optJSONObject("holiday") ?: return null
        val days = linkedMapOf<LocalDate, CalendarDayInfo>()
        val holidayDatesByName = linkedMapOf<String, MutableList<LocalDate>>()

        val keys = holidayObject.keys()
        while (keys.hasNext()) {
            val dateKey = keys.next()
            val info = holidayObject.optJSONObject(dateKey) ?: continue
            if (!info.has("holiday")) continue
            val date = parseDate(year, dateKey) ?: continue
            if (info.optBoolean("holiday", false)) {
                val name = normalizeHolidayName(info.optString("name").trim())
                if (name.isBlank()) continue
                days[date] = CalendarDayInfo(CalendarDayKind.HOLIDAY, name)
                holidayDatesByName.getOrPut(name) { mutableListOf() }.add(date)
            } else {
                days[date] = CalendarDayInfo(CalendarDayKind.ADJUSTED_WORKDAY)
            }
        }

        val holidays = holidayDatesByName.map { (name, dates) ->
            val sortedDates = dates.sorted()
            HolidayInfo(
                name = name,
                startDate = sortedDates.first().toString(),
                endDate = sortedDates.last().toString(),
                daysOff = sortedDates.size
            )
        }.sortedBy(HolidayInfo::startDate)

        TimorHolidayCalendar(holidays = holidays, days = days)
    }.getOrNull()

    /**
     * 缓存必须属于目标日期所在年份；跨年旧缓存只能视为未知，不能据此判断节假日或补班。
     */
    fun resolveCachedDay(
        json: String,
        cacheDate: String,
        date: LocalDate
    ): CalendarDayInfo {
        val cachedAt = runCatching { LocalDate.parse(cacheDate) }.getOrNull()
        if (cachedAt?.year != date.year) return CalendarDayInfo(CalendarDayKind.UNKNOWN)
        return parse(json, date.year)?.dayInfo(date)
            ?: CalendarDayInfo(CalendarDayKind.UNKNOWN)
    }

    private fun parseDate(year: Int, dateKey: String): LocalDate? {
        val match = Regex("""^(\d{2})-(\d{2})$""").matchEntire(dateKey) ?: return null
        return runCatching {
            LocalDate.of(year, match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }.getOrNull()
    }

    private fun normalizeHolidayName(name: String): String =
        if (name in springFestivalSubNames) "春节" else name
}

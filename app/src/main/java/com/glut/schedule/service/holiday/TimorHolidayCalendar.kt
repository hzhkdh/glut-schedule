package com.glut.schedule.service.holiday

import com.glut.schedule.data.model.AdjustedWorkday
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

    /**
     * 全部法定放假日（补班日不在内）。
     *
     * 不能由 [holidays] 的起止日期区间展开得到：一个假期中间的补班日会让区间
     * 覆盖到并不放假的日子，课表角标会多标。只能逐日回读原始解析结果。
     */
    val holidayDates: Set<LocalDate>
        get() = days.filterValues { it.kind == CalendarDayKind.HOLIDAY }.keys

    /**
     * 全部补班日（含 timor 原文名称），按日期升序。自动调课的推导兜底用它。
     *
     * 不能像 [holidayDates] 那样从 [holidays] 的起止区间推：补班日不在假期区间里，
     * 而且推导需要名称判断属于哪个节、是节前还是节后。
     */
    val adjustedWorkdays: List<AdjustedWorkday>
        get() = days
            .filterValues { it.kind == CalendarDayKind.ADJUSTED_WORKDAY }
            .map { (date, info) -> AdjustedWorkday(date, info.holidayName) }
            .sortedBy { it.date }
}

object TimorHolidayCalendarParser {
    /**
     * 春节假期里 timor 不把每天都叫「春节」，而是逐日叫 `除夕`、`初一`、`初二`…：**实测出现过到
     * 「初八」**（2024 年放假 8 天 = 初一~初八）。漏掉一天，那天就会变成一个只含一天的独立假期，
     * 让整簇算不出被借走的工作日而放弃，所以按模式认而不是写字面清单。
     */
    private val springFestivalSubNamePattern = Regex("^(除夕|初[一二三四五六七八九十]+)$")

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
                // 补班名（如「国庆节后补班」）必须**原样保留**，绝不能走 normalizeHolidayName：
                // 规约成「春节」会把「这是补班」这个性质抹掉，页面与缓存都要显示原文。
                // （自动调课推导**不看**这个名字——上游把补班日挂到哪个假期并不可靠，推导只认日期，
                // 归属自己按「离哪段连休最近」算。）
                days[date] = CalendarDayInfo(CalendarDayKind.ADJUSTED_WORKDAY, info.optString("name").trim())
            }
        }

        // 同名日期**不连续时必须拆成多条**：timor 会把 12/31 也命名成「元旦」（那是**次年**元旦
        // 假期的首日，落在本年的载荷里）。按 min~max 合成一条的话，元旦区间会被拉成 1/1~12/31，
        // 把所有假期连成一簇，整年的推导直接作废（2022、2023 就是这样）。
        val holidays = holidayDatesByName.flatMap { (name, dates) ->
            contiguousRuns(dates.sorted()).map { run ->
                HolidayInfo(
                    name = name,
                    startDate = run.first().toString(),
                    endDate = run.last().toString(),
                    daysOff = run.size
                )
            }
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
        if (springFestivalSubNamePattern.matches(name)) "春节" else name

    /** 把有序日期切成连续段：相邻两天算一段，断档就是一段新的。 */
    private fun contiguousRuns(sortedDates: List<LocalDate>): List<List<LocalDate>> {
        val runs = mutableListOf<MutableList<LocalDate>>()
        sortedDates.forEach { date ->
            val current = runs.lastOrNull()
            if (current != null && current.last().plusDays(1) == date) current += date
            else runs += mutableListOf(date)
        }
        return runs
    }
}

package com.glut.schedule.service.holiday

import org.json.JSONObject

/**
 * 年度节假日缓存（`数据所属年份 -> timor.tech 原始返回`）的编解码、可用性判定与旧数据兼容。
 *
 * 抽成纯函数是为了能直接测「升级后旧缓存会不会被丢掉」「未公布的年份会不会被当成已缓存」
 * 这类只有真实数据踩得到、单元测试却最容易漏掉的路径。
 */
internal fun decodeHolidayYearCache(raw: String?): Map<Int, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val root = JSONObject(raw)
        root.keys().asSequence().mapNotNull { key ->
            val year = key.toIntOrNull() ?: return@mapNotNull null
            val json = root.optString(key)
            if (json.isBlank()) null else year to json
        }
            // 读闸门：已落盘的坏数据在这里被剔除，调用方据此判为「缺失」并重新拉取，
            // 不需要任何一次性迁移脚本。
            .filter { (year, json) -> isUsableHolidayYearPayload(json, year) }
            .toMap()
    }.getOrDefault(emptyMap())
}

internal fun encodeHolidayYearCache(cache: Map<Int, String>): String {
    val root = JSONObject()
    cache.toSortedMap().forEach { (year, json) -> root.put(year.toString(), json) }
    return root.toString()
}

/**
 * 这份年度载荷是否**真的能渲染出假期**。
 *
 * timor 对尚未公布放假安排的年份返回的是合法但内容为空的 `{"code":0,"holiday":{}}`，
 * 而不是报错。只判「字符串非空」会把这份空数据当成有效缓存永久落盘，元旦之类的
 * 固定节日就再也不会有角标——所以判定必须落到内容上。
 *
 * 口径取「解析后至少有一个具名放假日」，而不是「holiday 是非空对象」：两端解析器都会
 * 丢弃名称为空的放假条目，因此「有键但名字全是空白」的载荷同样渲染不出任何角标，
 * 缓存它等于复制同一个 bug（已缓存但渲染为空 → 永不重拉）。
 *
 * 这里直接委托解析器，不重复实现一遍校验规则——解析器已经做了 code / 结构 / 日期合法性 /
 * `holiday:true` / 名字非空的全部检查，`holidays` 只由通过全部检查的条目构建。
 */
internal fun isUsableHolidayYearPayload(json: String?, year: Int): Boolean =
    TimorHolidayCalendarParser.parse(json.orEmpty(), year)?.holidays?.isNotEmpty() == true

/**
 * 读取年度缓存，并兼容升级前「只存一年」的旧键。
 *
 * 新键一旦有内容就以新键为准，旧键不再参与——否则用户清掉某年数据后旧值会「复活」。
 * 写入方必须走同一个函数再合并，否则第一次写入新键会把旧键里的那一年挤掉。
 *
 * [legacyDate] 是旧键记录的「拉取当天」，用它的年份判断那份 JSON 属于哪一年；
 * 解析不出来时退到 [fallbackYear]。
 */
internal fun holidayYearCache(
    storedYears: String?,
    legacyJson: String?,
    legacyDate: String?,
    fallbackYear: Int
): Map<Int, String> {
    val stored = decodeHolidayYearCache(storedYears)
    if (stored.isNotEmpty()) return stored
    if (legacyJson.isNullOrBlank()) return emptyMap()
    val legacyYear = legacyDate?.take(4)?.toIntOrNull() ?: fallbackYear
    // 旧键同样要过读闸门，否则被污染的旧值会从兜底分支里复活。
    if (!isUsableHolidayYearPayload(legacyJson, legacyYear)) return emptyMap()
    return mapOf(legacyYear to legacyJson)
}

/**
 * 挑出本地还没有可用数据的年份。
 *
 * 调用方传入的应是**已过读闸门**的表：被污染的条目在那里已经消失，所以这里
 * 天然会把它们判为缺失。不做任何节流——取数只在用户主动刷新时发生（见
 * `HolidayYearRefresher`），每次刷新都应当真的去问一次。
 */
internal fun holidayYearsMissingFromCache(
    years: IntRange,
    cachedYears: Map<Int, String>
): List<Int> = years.filter { cachedYears[it].isNullOrBlank() }

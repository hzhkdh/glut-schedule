package com.glut.schedule.service.holiday

import org.json.JSONObject

/**
 * 年度节假日缓存（`数据所属年份 -> timor.tech 原始返回`）的编解码与旧数据兼容。
 *
 * 抽成纯函数是为了能直接测「升级后旧缓存会不会被丢掉」「合并写入会不会覆盖别的年份」
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
        }.toMap()
    }.getOrDefault(emptyMap())
}

internal fun encodeHolidayYearCache(cache: Map<Int, String>): String {
    val root = JSONObject()
    cache.toSortedMap().forEach { (year, json) -> root.put(year.toString(), json) }
    return root.toString()
}

/**
 * 读取年度缓存，并兼容升级前「只存一年」的旧键。
 *
 * 新键一旦有内容就以新键为准，旧键不再参与——否则用户清掉某年数据后旧值会"复活"。
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
    return mapOf(legacyYear to legacyJson)
}

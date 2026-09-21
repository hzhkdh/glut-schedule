package com.glut.schedule

import com.glut.schedule.service.holiday.decodeHolidayYearCache
import com.glut.schedule.service.holiday.encodeHolidayYearCache
import com.glut.schedule.service.holiday.holidayYearCache
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 年度节假日缓存的编解码与升级兼容。
 *
 * 重点是**不能丢数据**：旧版本只存一年，升级后第一年往往只补拉缺失的那一年，
 * 合并逻辑一旦写成覆盖，旧年份就会静默消失。
 */
class HolidayYearCacheTest {

    private val json2026 = """{"code":0,"holiday":{"10-01":{"holiday":true,"name":"国庆节"}}}"""
    private val json2027 = """{"code":0,"holiday":{"01-01":{"holiday":true,"name":"元旦"}}}"""

    @Test
    fun encodeDecodeRoundTripKeepsEveryYear() {
        val encoded = encodeHolidayYearCache(mapOf(2026 to json2026, 2027 to json2027))

        assertEquals(mapOf(2026 to json2026, 2027 to json2027), decodeHolidayYearCache(encoded))
    }

    @Test
    fun decodeDropsBrokenEntriesInsteadOfLosingEveryYear() {
        // 用 JSONObject 构造：值必须是**字符串**，与真实存储一致——直接内联 JSON 会被
        // org.json 当成嵌套对象，读回来时重新序列化，键顺序变化会掩盖真实断言。
        val stored = JSONObject()
            .put("2026", json2026)
            .put("not-a-year", json2027)
            .put("2028", "")
            .toString()

        assertEquals(mapOf(2026 to json2026), decodeHolidayYearCache(stored))
    }

    @Test
    fun decodeOfMissingOrBrokenRawValueIsEmptyRatherThanACrash() {
        assertTrue(decodeHolidayYearCache(null).isEmpty())
        assertTrue(decodeHolidayYearCache("").isEmpty())
        assertTrue(decodeHolidayYearCache("not json").isEmpty())
    }

    @Test
    fun legacySingleYearCacheIsUsedOnlyWhileTheNewKeyIsEmpty() {
        assertEquals(
            mapOf(2026 to json2026),
            holidayYearCache(
                storedYears = null,
                legacyJson = json2026,
                legacyDate = "2026-09-21",
                fallbackYear = 2026
            )
        )
        // 新键一旦有内容就以它为准，旧键不再参与，避免已清理的年份"复活"。
        assertEquals(
            mapOf(2027 to json2027),
            holidayYearCache(
                storedYears = encodeHolidayYearCache(mapOf(2027 to json2027)),
                legacyJson = json2026,
                legacyDate = "2026-09-21",
                fallbackYear = 2026
            )
        )
    }

    @Test
    fun legacyYearFallsBackWhenTheStoredDateIsUnreadable() {
        assertEquals(
            mapOf(2025 to json2026),
            holidayYearCache(
                storedYears = null,
                legacyJson = json2026,
                legacyDate = "",
                fallbackYear = 2025
            )
        )
    }

    @Test
    fun writingANewYearKeepsTheYearCarriedOverFromTheLegacyKey() {
        // 模拟 setHolidayYearCache 的合并写入：先读（含旧键兜底）再合并新拉取的那一年。
        val beforeWrite = holidayYearCache(
            storedYears = null,
            legacyJson = json2026,
            legacyDate = "2026-09-21",
            fallbackYear = 2026
        )

        val afterWrite = decodeHolidayYearCache(
            encodeHolidayYearCache(beforeWrite + (2027 to json2027))
        )

        assertEquals(mapOf(2026 to json2026, 2027 to json2027), afterWrite)
    }

    @Test
    fun writingTheSameYearAgainReplacesItInsteadOfDuplicating() {
        val merged = decodeHolidayYearCache(
            encodeHolidayYearCache(mapOf(2026 to json2026) + (2026 to json2027))
        )

        assertEquals(mapOf(2026 to json2027), merged)
    }
}

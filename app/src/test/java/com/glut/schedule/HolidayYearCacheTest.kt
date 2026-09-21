package com.glut.schedule

import com.glut.schedule.service.holiday.decodeHolidayYearCache
import com.glut.schedule.service.holiday.encodeHolidayYearCache
import com.glut.schedule.service.holiday.holidayYearCache
import com.glut.schedule.service.holiday.holidayYearsMissingFromCache
import com.glut.schedule.service.holiday.isUsableHolidayYearPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 年度节假日缓存的编解码、可用性判定、节流与升级兼容。
 *
 * 两类重点：
 * 1. **不能丢数据**：旧版本只存一年，升级后第一年往往只补拉缺失的那一年，
 *    合并逻辑一旦写成覆盖，旧年份就会静默消失。
 * 2. **不能把「未公布」当成「已缓存」**：timor 对未公布年份返回
 *    `{"code":0,"holiday":{}}`（HTTP 200、code 为 0），只判字符串非空会让它永久落盘，
 *    元旦之类的固定节日再也不会有角标。
 */
class HolidayYearCacheTest {

    private val json2026 = """{"code":0,"holiday":{"10-01":{"holiday":true,"name":"国庆节"}}}"""
    private val json2027 = """{"code":0,"holiday":{"01-01":{"holiday":true,"name":"元旦"}}}"""

    /** 真实线上故障形态：2027 年放假安排未公布时 timor 的返回。 */
    private val notPublished = """{"code":0,"holiday":{}}"""

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

    // ---- 可用性判定：真实线上故障形态 ----

    @Test
    fun unpublishedYearPayloadIsNotUsable() {
        // 2027 年放假安排未公布时 timor 返回 {"code":0,"holiday":{}}。
        // 放过它会让「未公布」变成「已缓存」，元旦再也不会有角标。
        assertFalse(isUsableHolidayYearPayload(notPublished, 2027))
    }

    @Test
    fun payloadWithoutAnyNamedHolidayIsNotUsable() {
        // 有键但名字全是空白：两端解析器都会丢弃无名条目，缓存它同样渲染不出角标。
        assertFalse(
            isUsableHolidayYearPayload(
                """{"code":0,"holiday":{"10-01":{"holiday":true,"name":"   "}}}""",
                2026
            )
        )
        // 全是补班日：一份可展示信息都没有。
        assertFalse(
            isUsableHolidayYearPayload(
                """{"code":0,"holiday":{"10-10":{"holiday":false,"name":"国庆节后补班"}}}""",
                2026
            )
        )
    }

    @Test
    fun malformedOrForeignPayloadsAreNotUsable() {
        assertFalse(isUsableHolidayYearPayload(null, 2026))
        assertFalse(isUsableHolidayYearPayload("", 2026))
        assertFalse(isUsableHolidayYearPayload("not json", 2026))
        assertFalse(isUsableHolidayYearPayload("<html>error</html>", 2026))
        assertFalse(isUsableHolidayYearPayload("""{"code":1,"holiday":{}}""", 2026))
        assertFalse(isUsableHolidayYearPayload("""{"code":0,"holiday":[]}""", 2026))
        assertFalse(isUsableHolidayYearPayload("""{"code":0,"holiday":"x"}""", 2026))
    }

    @Test
    fun payloadWithOneNamedHolidayIsUsable() {
        // 正向对照：谓词不能严到把真实数据也拒了，否则每年会无限重拉。
        assertTrue(isUsableHolidayYearPayload(json2026, 2026))
        assertTrue(isUsableHolidayYearPayload(json2027, 2027))
    }

    // ---- 读闸门：自愈已污染的缓存 ----

    @Test
    fun poisonedYearIsDroppedWhileValidYearsSurvive() {
        // 这是自愈的核心：用户机器上已经落盘的坏数据，读出来时必须消失，
        // 调用方才会把它判为「缺失」并重新拉取——不需要任何迁移脚本。
        val stored = JSONObject()
            .put("2026", json2026)
            .put("2027", notPublished)
            .toString()

        assertEquals(mapOf(2026 to json2026), decodeHolidayYearCache(stored))
    }

    @Test
    fun poisonedLegacySingleYearCacheIsIgnored() {
        assertTrue(
            holidayYearCache(
                storedYears = null,
                legacyJson = notPublished,
                legacyDate = "2027-01-01",
                fallbackYear = 2027
            ).isEmpty()
        )
    }

    // ---- 缺失年份判定：取数只在用户主动刷新时发生 ----

    @Test
    fun yearsWithoutUsableDataAreSelectedForFetching() {
        assertEquals(
            listOf(2026, 2027),
            holidayYearsMissingFromCache(years = 2026..2027, cachedYears = emptyMap())
        )
    }

    @Test
    fun yearsWithUsableDataAreNeverFetched() {
        assertEquals(
            emptyList<Int>(),
            holidayYearsMissingFromCache(years = 2026..2026, cachedYears = mapOf(2026 to json2026))
        )
    }

    @Test
    fun poisonedEntriesCountAsMissingSoARefreshRetriesThem() {
        // 读闸门已经把它们剔除了；即便有人绕过闸门直接塞进来，判定也不能把空载荷
        // 当成「已缓存」，否则用户永远刷不出元旦。
        val poisoned = mapOf(2026 to "", 2027 to "   ")

        assertEquals(
            listOf(2026, 2027),
            holidayYearsMissingFromCache(years = 2026..2027, cachedYears = poisoned)
        )
    }

    @Test
    fun writingTheSameYearAgainReplacesItInsteadOfDuplicating() {
        val merged = decodeHolidayYearCache(
            encodeHolidayYearCache(mapOf(2026 to json2026) + (2026 to json2027))
        )

        assertEquals(mapOf(2026 to json2027), merged)
    }
}

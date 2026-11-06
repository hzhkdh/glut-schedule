package com.glut.schedule

import com.glut.schedule.service.refresh.nextRefreshInstant
import com.glut.schedule.service.refresh.validateRefreshTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduledRefreshSchedulerTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `校验接受乱序但拒绝重复与非法数量`() {
        assertTrue(validateRefreshTimes(listOf(1080, 420)))
        assertFalse(validateRefreshTimes(listOf(420, 420)))
        assertFalse(validateRefreshTimes(List(9) { 420 }))
        assertTrue(validateRefreshTimes(listOf(420)))
        assertFalse(validateRefreshTimes(emptyList()))
        assertFalse(validateRefreshTimes((0..8).map { it * 60 }))
        assertFalse(validateRefreshTimes(listOf(-1)))
        assertFalse(validateRefreshTimes(listOf(1440)))
    }

    @Test
    fun `相邻及跨日的最小间隔均为十五分钟`() {
        assertFalse(validateRefreshTimes(listOf(420, 434)))
        assertTrue(validateRefreshTimes(listOf(420, 435)))
        assertFalse(validateRefreshTimes(listOf(1430, 4)))
        assertTrue(validateRefreshTimes(listOf(1430, 5)))
    }

    @Test
    fun `乱序列表选择当天最近未来时刻`() {
        assertEquals(
            Instant.parse("2026-11-06T10:00:00Z"),
            nextRefreshInstant(listOf(1080, 420), Instant.parse("2026-11-06T00:00:00Z"), shanghai)
        )
    }

    @Test
    fun `恰好命中时刻会跳过且最后一个时刻后跨日`() {
        assertEquals(
            Instant.parse("2026-11-06T10:00:00Z"),
            nextRefreshInstant(listOf(420, 1080), Instant.parse("2026-11-05T23:00:00Z"), shanghai)
        )
        assertEquals(
            Instant.parse("2026-11-06T23:00:00Z"),
            nextRefreshInstant(listOf(420, 1080), Instant.parse("2026-11-06T10:00:00Z"), shanghai)
        )
    }

    @Test
    fun `时间按传入时区计算而不是固定二十四小时间隔`() {
        val now = Instant.parse("2026-11-06T00:00:00Z")
        assertEquals(Instant.parse("2026-11-06T07:00:00Z"), nextRefreshInstant(listOf(420), now, ZoneId.of("UTC")))
        assertEquals(Instant.parse("2026-11-06T23:00:00Z"), nextRefreshInstant(listOf(420), now, shanghai))
    }

    @Test
    fun `夏令时缺失时刻按缺口向后平移`() {
        // 纽约春季跳过 02 点，02:30 顺延为当天 03:30。
        assertEquals(
            Instant.parse("2026-03-08T07:30:00Z"),
            nextRefreshInstant(listOf(150), Instant.parse("2026-03-08T05:00:00Z"), ZoneId.of("America/New_York"))
        )
    }

    @Test
    fun `跨夏令时仍保持本地七点而不是顺延二十四小时`() {
        // 进入夏令时后，纽约次日 07:00 与前一天只相差 23 小时。
        assertEquals(
            Instant.parse("2026-03-08T11:00:00Z"),
            nextRefreshInstant(listOf(420), Instant.parse("2026-03-07T12:00:00Z"), ZoneId.of("America/New_York"))
        )
    }

    @Test
    fun `夏令时回拨重叠时刻每天仅选择较早一次`() {
        val zone = ZoneId.of("America/New_York")
        assertEquals(
            Instant.parse("2026-11-01T05:30:00Z"),
            nextRefreshInstant(listOf(90), Instant.parse("2026-11-01T04:00:00Z"), zone)
        )
        assertEquals(
            Instant.parse("2026-11-02T06:30:00Z"),
            nextRefreshInstant(listOf(90), Instant.parse("2026-11-01T05:30:00Z"), zone)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `非法时刻拒绝计算下一次执行时间`() {
        nextRefreshInstant(listOf(0, 1439), Instant.EPOCH, shanghai)
    }
}

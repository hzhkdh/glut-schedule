package com.glut.schedule

import com.glut.schedule.service.refresh.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ScheduledRefreshClockTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val plan = ScheduledRefreshPlan("evening", 18 * 60, emptySet())
    private fun instant(value: String) = Instant.parse(value)
    private fun needsCorrection(due: String, now: String, started: Boolean = false) =
        shouldRecalibrateScheduledOccurrence(instant(due).toEpochMilli(), instant(now), plan, zone, started)

    @Test fun `日期从九号纠正到八号取消错误的未来轮次并选择今天傍晚`() {
        val now = instant("2026-11-08T00:00:00Z")
        assertTrue(needsCorrection("2026-11-09T10:00:00Z", now.toString()))
        assertEquals(instant("2026-11-08T10:00:00Z"), nextUnrecordedRefreshInstant(plan, now, zone, emptySet()))
    }

    @Test fun `小幅调钟仍指向同一轮次时保持原请求`() {
        assertFalse(needsCorrection("2026-11-08T10:00:00Z", "2026-11-08T00:02:00Z"))
        assertFalse(needsCorrection("2026-11-08T10:00:00Z", "2026-11-07T23:58:00Z"))
    }

    @Test fun `向前调日不取消已到期且等待网络的请求`() {
        assertFalse(needsCorrection("2026-11-08T10:00:00Z", "2026-11-09T00:00:00Z"))
    }

    @Test fun `回拨后仍保留运行中和已有检查点的重试请求`() {
        assertFalse(needsCorrection("2026-11-09T10:00:00Z", "2026-11-08T00:00:00Z", started = true))
    }

    @Test fun `回拨到已完成时刻之前不重跑当天而继续安排下一天`() {
        val completed = instant("2026-11-08T10:00:00Z").toEpochMilli()
        assertEquals(instant("2026-11-09T10:00:00Z"), nextUnrecordedRefreshInstant(plan,
            instant("2026-11-08T00:00:00Z"), zone, setOf(completed)))
        assertFalse(shouldEnqueueScheduledOccurrence(false, true, emptyList(), "initial"))
        // 正在运行的轮次在回拨后结束，后继也必须跳过已经执行过的未来墙上时刻。
        assertEquals(instant("2026-11-09T10:00:00Z").toEpochMilli(), nextScheduledOccurrenceAfterRun(
            plan, instant("2026-11-08T00:00:00Z"), zone, completed, setOf(completed)))
    }

    @Test fun `校准取消的未来请求允许同一轮次重新排队但已完成请求不允许`() {
        val cancelled = ScheduledOccurrenceWorkSnapshot(setOf("initial"), true, cancelled = true)
        assertTrue(shouldEnqueueScheduledOccurrence(false, false, listOf(cancelled), "initial"))
        assertFalse(shouldEnqueueScheduledOccurrence(false, false,
            listOf(cancelled, ScheduledOccurrenceWorkSnapshot(setOf("initial"), true)), "initial"))
    }

    @Test fun `时区切换只重排未来请求`() {
        val now = instant("2026-11-08T00:00:00Z")
        val due = instant("2026-11-08T10:00:00Z").toEpochMilli()
        assertTrue(shouldRecalibrateScheduledOccurrence(due, now, plan, ZoneId.of("UTC"), false))
        assertFalse(shouldRecalibrateScheduledOccurrence(due, now, plan, ZoneId.of("UTC"), true))
        assertFalse(shouldRecalibrateScheduledOccurrence(due, instant("2026-11-08T11:00:00Z"),
            plan, ZoneId.of("UTC"), false))
    }
}

package com.glut.schedule

import com.glut.schedule.ui.AutoRefreshPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 间隔值随构建类型变化（release 8 小时 / debug 30 秒），所以断言一律引用
 * [AutoRefreshPolicy.INTERVAL_MILLIS]，不写死小时数——否则调参与测试会互相绊住。
 */
class AutoRefreshPolicyTest {
    private val now = 1_700_000_000_000L
    private val interval = AutoRefreshPolicy.INTERVAL_MILLIS

    @Test
    fun triggersWhenNeverAttemptedBefore() {
        assertTrue(AutoRefreshPolicy.isDue(now, null))
        assertTrue(AutoRefreshPolicy.isDue(now, 0L))
    }

    @Test
    fun skipsWhenLastAttemptIsInsideWindow() {
        assertFalse(AutoRefreshPolicy.isDue(now, now - interval + 1_000L))
    }

    @Test
    fun triggersExactlyAtWindowBoundary() {
        assertTrue(AutoRefreshPolicy.isDue(now, now - interval))
    }

    @Test
    fun triggersAfterWindow() {
        assertTrue(AutoRefreshPolicy.isDue(now, now - interval - 60L * 60L * 1000L))
    }
}

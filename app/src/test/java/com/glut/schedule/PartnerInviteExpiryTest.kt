package com.glut.schedule

import com.glut.schedule.partner.isPartnerInviteExpired
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 邀请码「是否已到期」的判定。
 *
 * 这条判定同时被三处使用：Store 冷启动读取、页面到点定时器、回到前台补判。
 * 口径错一次三处一起错，所以边界单独锁住。
 */
class PartnerInviteExpiryTest {

    private val expiry = "2026-07-30T12:00:00.000Z"

    @Test
    fun inviteExpiresAtTheExactMomentTheServerTtlEnds() {
        // 服务端 KV 到点即不可用，客户端恰好等于到期时刻时必须同样判过期，
        // 不能比服务端更宽松。
        assertTrue(isPartnerInviteExpired(expiry, Instant.parse(expiry)))
    }

    @Test
    fun inviteIsStillValidOneMillisecondBeforeExpiry() {
        assertFalse(isPartnerInviteExpired(expiry, Instant.parse(expiry).minusMillis(1)))
    }

    @Test
    fun inviteIsExpiredImmediatelyAfterExpiry() {
        assertTrue(isPartnerInviteExpired(expiry, Instant.parse(expiry).plusMillis(1)))
    }

    @Test
    fun inviteWithoutMillisecondsIsParsedTheSameWay() {
        val secondsOnly = "2026-07-30T12:00:00Z"
        assertTrue(isPartnerInviteExpired(secondsOnly, Instant.parse(secondsOnly)))
        assertFalse(isPartnerInviteExpired(secondsOnly, Instant.parse(secondsOnly).minusSeconds(1)))
    }

    @Test
    fun unparsableExpiryKeepsTheInviteAlive() {
        // 坏数据不该让用户莫名其妙丢掉一个可能还能用的邀请码，
        // 所以解析失败一律按「未过期」处理，由服务端在导入/撤销时兜底拒绝。
        val now = Instant.parse(expiry)
        assertFalse(isPartnerInviteExpired("", now))
        assertFalse(isPartnerInviteExpired("   ", now))
        assertFalse(isPartnerInviteExpired("不是时间", now))
        assertFalse(isPartnerInviteExpired("2026-07-30 12:00:00", now))
    }
}

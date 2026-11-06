package com.glut.schedule

import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicProfileSession
import com.glut.schedule.service.refresh.academicCacheWriteMutex
import com.glut.schedule.service.refresh.commitScheduledRefreshIfCurrent
import com.glut.schedule.service.refresh.supportsScheduledRefresh
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledRefreshSafetyTest {
    @Test fun wechatRenewalNeverFallsBackToPassword() = runTest {
        var passwordCalls = 0
        val result = com.glut.schedule.service.refresh.renewScheduledSession(
            com.glut.schedule.service.academic.AcademicSessionAuthMethod.WECHAT_SSO,
            { AcademicLoginResult.CaptchaOrInteractiveLoginRequired },
            { passwordCalls++; AcademicLoginResult.MissingCredentials })
        assertEquals(AcademicLoginResult.CaptchaOrInteractiveLoginRequired, result)
        assertEquals(0, passwordCalls)
    }
    @Test fun passwordRenewalDoesNotUseWechatSession() = runTest {
        var wechatCalls = 0
        val result = com.glut.schedule.service.refresh.renewScheduledSession(
            com.glut.schedule.service.academic.AcademicSessionAuthMethod.PASSWORD,
            { wechatCalls++; AcademicLoginResult.CaptchaOrInteractiveLoginRequired },
            { AcademicLoginResult.MissingCredentials })
        assertEquals(AcademicLoginResult.MissingCredentials, result)
        assertEquals(0, wechatCalls)
    }

    // 仅使用合成身份和公开的来源地址，不建立登录会话，也不访问网络。
    private val guilinSession = AcademicProfileSession(
        studentNo = "synthetic-student",
        base = AcademicLoginResult.DEFAULT_GUILIN_URL
    )

    @Test
    fun `桂林且会话来源和学号有效时允许后台刷新`() {
        assertTrue(supportsScheduledRefresh(CampusType.GUILIN, guilinSession))
        assertTrue(supportsScheduledRefresh(CampusType.GUILIN, guilinSession.copy(base = guilinSession.base + "/")))
    }

    @Test
    fun `南宁和研究生即使持有桂林会话也拒绝后台刷新`() {
        assertFalse(supportsScheduledRefresh(CampusType.NANNING, guilinSession))
        assertFalse(supportsScheduledRefresh(CampusType.GRADUATE, guilinSession))
    }

    @Test
    fun `空学号和错误来源拒绝后台刷新`() {
        listOf("", "   ").forEach { studentNo ->
            assertFalse(supportsScheduledRefresh(CampusType.GUILIN, guilinSession.copy(studentNo = studentNo)))
        }
        listOf("", "https://unrelated.invalid", guilinSession.base + ".unrelated.invalid").forEach { base ->
            assertFalse(supportsScheduledRefresh(CampusType.GUILIN, guilinSession.copy(base = base)))
        }
    }

    @Test
    fun `身份过期时不执行提交`() = runTest {
        var commits = 0
        val committed = commitScheduledRefreshIfCurrent(isCurrent = { false }, commit = { commits++ })
        assertFalse(committed)
        assertEquals(0, commits)
    }

    @Test
    fun `身份仍有效时提交恰好一次`() = runTest {
        var commits = 0
        val committed = commitScheduledRefreshIfCurrent(isCurrent = { true }, commit = { commits++ })
        assertTrue(committed)
        assertEquals(1, commits)
    }

    @Test
    fun `清理持有共享锁时提交等待并在拿锁后重新校验身份`() = runTest {
        val clearHasLock = CompletableDeferred<Unit>()
        val releaseClear = CompletableDeferred<Unit>()
        var current = true
        var identityChecks = 0
        var commits = 0
        val clear = launch {
            // 模拟清库期间持有与刷新提交相同的锁，用信号显式控制释放顺序。
            academicCacheWriteMutex.withLock {
                clearHasLock.complete(Unit)
                releaseClear.await()
            }
        }
        try {
            runCurrent()
            assertTrue(clearHasLock.isCompleted)
            val refresh = async {
                commitScheduledRefreshIfCurrent(
                    isCurrent = { identityChecks++; current },
                    commit = { commits++ }
                )
            }
            runCurrent()
            assertFalse(refresh.isCompleted)
            assertEquals(0, commits)
            assertEquals(0, identityChecks)

            // 清库完成前切换身份，迟到的旧刷新拿锁后必须放弃写入。
            current = false
            releaseClear.complete(Unit)
            runCurrent()
            assertFalse(refresh.await())
            clear.join()
            assertEquals(1, identityChecks)
            assertEquals(0, commits)
        } finally {
            // 即使中途断言失败也释放模拟清理，避免挂起整个测试作用域。
            releaseClear.complete(Unit)
        }
    }
}

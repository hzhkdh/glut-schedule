package com.glut.schedule

import com.glut.schedule.ui.pages.canUseProfessionalScoreRemoteDataWithoutLoginRetry
import com.glut.schedule.ui.pages.professionalScoreRefreshMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfessionalScoreRefreshPolicyTest {
    @Test
    fun completeRemoteDataCanSkipLoginRetry() {
        assertTrue(
            canUseProfessionalScoreRemoteDataWithoutLoginRetry(
                hasCookie = true,
                hasScores = true,
                hasStudyPlan = true,
                scoreUnavailableReason = ""
            )
        )
    }

    @Test
    fun scoresWithoutStudyPlanMustRetryLogin() {
        assertFalse(
            canUseProfessionalScoreRemoteDataWithoutLoginRetry(
                hasCookie = true,
                hasScores = true,
                hasStudyPlan = false,
                scoreUnavailableReason = ""
            )
        )
    }

    // ---- 刷新结束后状态条该显示什么 ----
    //
    // 这条决策原先写死在 refreshData() 的 when 里，于是「用户在续期弹窗里点了取消」
    // 刚写下的提示会被立刻覆盖：取消路径写「会话已过期，已取消登录」，紧接着那个 when
    // 无条件改写成「未获取到可用于计算的数据」——既不准确，又把用户刚做的动作抹掉，
    // 用户会以为自己点错了。

    @Test
    fun cancelledRenewalKeepsTheMessageTheUserJustCaused() {
        // null = 保持现状，不要覆盖。
        assertNull(
            professionalScoreRefreshMessage(
                renewalCancelled = true,
                scoreUnavailableReason = "",
                scoreCount = 0,
                courseCount = 0
            )
        )
    }

    @Test
    fun unavailableReasonWinsOverTheEmptyFallback() {
        // 「成绩页被评教拦住」这类原因必须说出来，否则用户只看到「未获取到可用于计算的数据」，
        // 会以为自己没有成绩。
        assertEquals(
            "你有课程未完成评教",
            professionalScoreRefreshMessage(
                renewalCancelled = false,
                scoreUnavailableReason = "你有课程未完成评教",
                scoreCount = 0,
                courseCount = 0
            )
        )
    }

    @Test
    fun successReportsWhatCameBack() {
        assertEquals(
            "已更新：3 条成绩，5 门计划课程",
            professionalScoreRefreshMessage(
                renewalCancelled = false,
                scoreUnavailableReason = "",
                scoreCount = 3,
                courseCount = 5
            )
        )
    }

    @Test
    fun emptyResultFallsBackToTheExistingWording() {
        assertEquals(
            "未获取到可用于计算的数据",
            professionalScoreRefreshMessage(
                renewalCancelled = false,
                scoreUnavailableReason = "",
                scoreCount = 0,
                courseCount = 0
            )
        )
    }
}

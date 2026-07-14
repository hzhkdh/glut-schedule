package com.glut.schedule

import com.glut.schedule.ui.pages.canUseProfessionalScoreRemoteDataWithoutLoginRetry
import org.junit.Assert.assertFalse
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
}

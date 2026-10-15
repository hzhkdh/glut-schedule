package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeExamRenewalContractTest {

    @Test
    fun validEmptyGradeExamResponseDoesNotEnterTheSessionRenewalBranch() {
        val module = File("src/main/java/com/glut/schedule/ui/pages/GradeExamViewModel.kt")
        val source = (if (module.exists()) module else {
            File("app/src/main/java/com/glut/schedule/ui/pages/GradeExamViewModel.kt")
        }).readText()

        // 空列表是“暂无等级考试记录”的合法结果，不能再直接当作登录态失效。
        assertFalse(source.contains("if (allExams.isEmpty())"))
        assertTrue(source.contains("GradeExamFetchResult.SessionExpired ->"))
        // HTTP 未授权或实际登录页才允许走验证码续期。
        assertTrue(source.contains("looksLikeAcademicLoginPage(html)"))
    }
}

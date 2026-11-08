package com.glut.schedule

import com.glut.schedule.service.refresh.AcademicRefreshQueries
import com.glut.schedule.service.refresh.ScheduledRefreshLoginRequiredException
import org.junit.Test
import java.io.IOException

/** 使用合成 HTML 验证失败关闭，错误页不得以空记录覆盖缓存。 */
class AcademicRefreshQueriesTest {
    @Test fun genericForbiddenResponseDoesNotTriggerLoginRenewal() {
        try {
            AcademicRefreshQueries.validateAcademicResponse(403, "gateway forbidden")
            throw AssertionError("403 必须保留失败语义")
        } catch (error: IOException) {
            org.junit.Assert.assertFalse(error is ScheduledRefreshLoginRequiredException)
        }
    }
    @Test(expected = ScheduledRefreshLoginRequiredException::class)
    fun expiredSessionIsDistinctFromEmptyData() {
        AcademicRefreshQueries.validateAcademicResponse(200, "<html>统一身份认证</html>")
    }
    @Test(expected = IOException::class)
    fun evaluationBlockedDoesNotBecomeEmptyScores() {
        AcademicRefreshQueries.validateAcademicResponse(200, "有课程未参加评教，不能查看成绩")
    }
    @Test(expected = IOException::class)
    fun errorPageWithHttp200IsRejected() {
        AcademicRefreshQueries.validateAcademicResponse(200, "<html><title>提示信息</title><table class='error'></table></html>")
    }
    @Test(expected = IOException::class)
    fun arbitraryTableCannotClearGradeExamCache() {
        AcademicRefreshQueries.validateGradeExamMarkup("<table><tr><td>学校公告</td></tr></table>")
    }
    @Test fun recognizableEmptyGradeExamTableIsAllowed() {
        AcademicRefreshQueries.validateGradeExamMarkup("我参加的考试<table class='infolist_tab'></table>")
    }
}

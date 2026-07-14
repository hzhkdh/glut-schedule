package com.glut.schedule

import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.service.finance.FinanceCookies
import com.glut.schedule.service.finance.FinanceRequests
import com.glut.schedule.service.finance.FinanceResponses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceApiServiceTest {
    @Test
    fun moduleRequestMatchesOfficialWebsiteContract() {
        assertEquals(
            mapOf("method" to "getorderlist", "stuid" to "1", "start" to "1", "pagesize" to "20"),
            FinanceRequests.module(FinanceModule.TRANSACTIONS, page = 2, pageSize = 20)
        )
        assertEquals(
            mapOf("method" to "getchargeitems", "money" to "0", "stuid" to "1"),
            FinanceRequests.module(FinanceModule.PENDING)
        )
    }

    @Test
    fun userNoLoginVariantsAreSessionExpired() {
        assertTrue(FinanceResponses.isSessionExpired(" user_no_login "))
        assertTrue(FinanceResponses.isSessionExpired("USERNOLOGIN"))
        assertTrue(FinanceResponses.isSessionExpired("登录状态已过期"))
        assertFalse(FinanceResponses.isSessionExpired("查询成功"))
    }

    @Test
    fun cookiesMergeByNameWithoutLeakingAttributes() {
        val merged = FinanceCookies.merge(
            "ASP.NET_SessionId=old; route=a",
            listOf("ASP.NET_SessionId=new; Path=/; HttpOnly", "token=t1; Secure")
        )

        assertEquals("ASP.NET_SessionId=new; route=a; token=t1", merged)
    }
}

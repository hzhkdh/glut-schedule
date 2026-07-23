package com.glut.schedule

import com.glut.schedule.ui.pages.LoginMessageTone
import com.glut.schedule.ui.pages.loginMessageTone
import org.junit.Assert.assertEquals
import org.junit.Test

class DirectLoginMessageToneTest {
    @Test
    fun cachedSemesterMessageIsSuccess() {
        assertEquals(
            LoginMessageTone.SUCCESS,
            loginMessageTone("已缓存2024·秋，历史学期为只读模式")
        )
    }

    @Test
    fun downloadFailureMessageIsError() {
        assertEquals(
            LoginMessageTone.ERROR,
            loginMessageTone("下载失败：登录状态已失效")
        )
    }

    @Test
    fun downloadingMessageIsInformational() {
        assertEquals(
            LoginMessageTone.INFO,
            loginMessageTone("正在下载2025·春...")
        )
    }
}

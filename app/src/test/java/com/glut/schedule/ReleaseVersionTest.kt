package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun releaseVersionIs0272WithVersionCode132() {
        // 验证实际编译常量，保证修复版可以被旧客户端识别并覆盖安装。
        assertEquals(132, BuildConfig.VERSION_CODE)
        assertEquals("0.27.2", BuildConfig.VERSION_NAME)
    }

    @Test
    fun financeUserAgentUsesBuildVersionInsteadOfHardcodedRelease() {
        val module = File("src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt")
        val source = (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt")).readText()

        assertTrue(source.contains("BuildConfig.VERSION_NAME"))
    }
}

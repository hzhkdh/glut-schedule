package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun releaseVersionIs0270WithVersionCode130() {
        // 验证实际编译常量，保证同名修复包具有可覆盖旧包的更高内部版本。
        assertEquals(130, BuildConfig.VERSION_CODE)
        assertEquals("0.27.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun financeUserAgentUsesBuildVersionInsteadOfHardcodedRelease() {
        val module = File("src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt")
        val source = (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt")).readText()

        assertTrue(source.contains("BuildConfig.VERSION_NAME"))
    }
}

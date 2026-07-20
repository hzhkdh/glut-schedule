package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun releaseVersionIs0200() {
        val module = File("build.gradle.kts")
        val source = (if (module.exists()) module else File("app/build.gradle.kts")).readText()
        assertTrue(source.contains("versionCode = 118"))
        assertTrue(source.contains("versionName = \"0.20.0\""))
    }

    @Test
    fun financeUserAgentUsesBuildVersionInsteadOfHardcodedRelease() {
        val module = File("src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt")
        val source = (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/service/finance/FinanceApiService.kt")).readText()

        assertTrue(source.contains("BuildConfig.VERSION_NAME"))
    }
}

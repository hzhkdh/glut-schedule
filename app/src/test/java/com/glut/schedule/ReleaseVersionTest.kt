package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun releaseVersionIs0180() {
        val module = File("build.gradle.kts")
        val source = (if (module.exists()) module else File("app/build.gradle.kts")).readText()
        assertTrue(source.contains("versionCode = 114"))
        assertTrue(source.contains("versionName = \"0.18.0\""))
    }
}

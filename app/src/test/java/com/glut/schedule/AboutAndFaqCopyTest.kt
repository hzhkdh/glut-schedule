package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutAndFaqCopyTest {
    @Test
    fun aboutPageShowsMaintainerInCardAndRemovesFooterAuthorLine() {
        val source = readSource("AboutScreen.kt")

        assertTrue(source.contains("维护者"))
        assertTrue(source.contains("24人工智能 hezh"))
        assertFalse(source.contains("Made with"))
    }

    @Test
    fun faqCopyMatchesAcceptedTextChanges() {
        val source = readSource("FaqScreen.kt")

        assertTrue(source.contains("更新逻辑"))
        assertTrue(source.contains("感谢前辈大佬，致敬开源！"))
        assertFalse(source.contains("为什么更新下载这么慢？"))
        assertFalse(source.contains("为什么想到开发这个项目？"))
        assertFalse(source.contains("他们的时代可没有 AI"))
    }

    private fun readSource(fileName: String): String {
        val appModulePath = File("src/main/java/com/glut/schedule/ui/pages/$fileName")
        if (appModulePath.exists()) return appModulePath.readText()
        return File("app/src/main/java/com/glut/schedule/ui/pages/$fileName").readText()
    }
}

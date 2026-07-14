package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutAndFaqCopyTest {
    @Test
    fun aboutPageLinksMaintainerAndCoreContributorProfiles() {
        val source = readSource("AboutScreen.kt")

        assertTrue(source.contains("维护者"))
        assertTrue(source.contains("24人工智能 hezh"))
        assertTrue(source.contains("uriHandler.openUri(\"https://github.com/hzhkdh\")"))
        assertTrue(source.contains("核心贡献者"))
        assertTrue(source.contains("24人工智能 m-z-jia"))
        assertTrue(source.contains("uriHandler.openUri(\"https://github.com/m-z-jia\")"))
        assertFalse(source.contains("Made with"))
    }

    @Test
    fun aboutPageLinksNewGithubIssueAndKeepsEmailFeedback() {
        val source = readSource("AboutScreen.kt")

        assertTrue(source.contains("LazyColumn("))
        assertTrue(source.contains("提交问题"))
        assertTrue(source.contains("GitHub Issues"))
        assertTrue(source.contains("uriHandler.openUri(\"https://github.com/hzhkdh/glut-schedule/issues/new\")"))
        assertTrue(source.contains("mailto:hezh0425@qq.com"))
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

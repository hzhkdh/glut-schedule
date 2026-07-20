package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AboutAndFaqCopyTest {
    @Test
    fun aboutPageLinksMaintainerAndContributorProfiles() {
        val source = readSource("AboutScreen.kt")

        assertTrue(source.contains("维护者"))
        assertTrue(source.contains("24人工智能 hezh"))
        assertTrue(source.contains("uriHandler.openUri(\"https://github.com/hzhkdh\")"))
        assertTrue(source.contains("label = \"贡献者\""))
        assertTrue(source.contains("24人工智能 mzjia"))
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
    fun aboutPageCopiesMiniProgramNameAndShowsWechatSearchFeedback() {
        val source = readSource("AboutScreen.kt")

        assertTrue(source.contains("label = \"小程序\""))
        assertTrue(source.contains("value = MINI_PROGRAM_SEARCH_TEXT"))
        assertTrue(source.contains("MINI_PROGRAM_SEARCH_TEXT = \"桂系一站式\""))
        assertTrue(source.contains("clipboard.setPrimaryClip"))
        assertTrue(source.contains("SnackbarHost("))
        assertTrue(source.contains("已复制“桂系一站式”，请前往微信搜索"))
        assertTrue(source.contains("复制失败，请手动在微信搜索“桂系一站式”"))
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

package com.glut.schedule

import com.glut.schedule.ui.components.MarkdownPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownContentTest {
    @Test
    fun summaryRemovesCommonMarkdownSyntaxAndCollapsesWhitespace() {
        val markdown = """
            ## 更新公告

            - 支持 **历史学期**
            - 修复 [下载链接](https://example.com)

            `只读缓存`
        """.trimIndent()

        assertEquals(
            "更新公告 支持 历史学期 修复 下载链接 只读缓存",
            MarkdownPolicy.toPlainText(markdown)
        )
    }

    @Test
    fun sanitizerKeepsHttpLinksAndNeutralizesUnsafeLinksImagesAndHtml() {
        val source = "[官网](https://example.com) [电话](tel:10086) ![图](https://example.com/a.png) <b>文本</b>"
        val safe = MarkdownPolicy.sanitize(source)

        assertTrue(safe.contains("[官网](https://example.com)"))
        assertTrue(safe.contains("电话"))
        assertFalse(safe.contains("tel:10086"))
        assertFalse(safe.contains("!["))
        assertFalse(safe.contains("<b>"))
        assertTrue(safe.contains("文本"))
    }
}

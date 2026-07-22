package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownUiContractTest {
    @Test
    fun noticeListRendersScrollableMarkdownInlineWithoutDetailRoute() {
        val notice = source("ui/pages/NoticeScreen.kt")

        assertTrue(notice.contains("MarkdownContent("))
        assertTrue(notice.contains("heightIn(max ="))
        assertTrue(notice.contains("verticalScroll("))
        assertTrue(notice.contains("AttachmentRow"))
        assertTrue(!notice.contains("NoticeDetail"))
        assertTrue(!notice.contains("selectedNotice"))
        assertTrue(!notice.contains("MarkdownPolicy.toPlainText(notice.content)"))

        val markdown = source("ui/components/MarkdownContent.kt")
        assertTrue(markdown.contains("markdownColor(text = contentColor)"))
    }

    @Test
    fun startupPopupUsesPlainPreviewAndUpdateDialogUsesMarkdown() {
        val main = source("MainActivity.kt")

        assertTrue(main.contains("MarkdownPolicy.toPlainText(notice.content)"))
        assertTrue(main.contains("MarkdownContent(markdown = state.info.releaseNotes"))
    }

    private fun source(path: String): String {
        val module = File("src/main/java/com/glut/schedule/$path")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$path")).readText()
    }
}

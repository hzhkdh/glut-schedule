package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownUiContractTest {
    @Test
    fun noticeListUsesSummaryAndDetailUsesMarkdownWithAttachments() {
        val notice = source("ui/pages/NoticeScreen.kt")

        assertTrue(notice.contains("MarkdownPolicy.toPlainText"))
        assertTrue(notice.contains("NoticeDetail"))
        assertTrue(notice.contains("MarkdownContent("))
        assertTrue(notice.contains("AttachmentRow"))
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

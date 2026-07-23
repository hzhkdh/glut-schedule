package com.glut.schedule

import com.glut.schedule.ui.pages.isSafeNoticeUrl
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoticePlainTextUiContractTest {
    @Test
    fun noticeLinksOnlyAllowHttpAndHttps() {
        assertTrue(isSafeNoticeUrl("https://example.com/notice"))
        assertTrue(isSafeNoticeUrl("http://example.com/file.pdf"))
        assertFalse(isSafeNoticeUrl("javascript:alert(1)"))
        assertFalse(isSafeNoticeUrl("file:///sdcard/private.txt"))
        assertFalse(isSafeNoticeUrl("intent://open"))
    }

    @Test
    fun noticeListUsesSinglePagePlainTextWithoutDetailRoute() {
        val notice = source("ui/pages/NoticeScreen.kt")

        assertFalse(notice.contains("MarkdownContent("))
        assertTrue(notice.contains("AttachmentRow"))
        assertFalse(notice.contains("NoticeDetail"))
        assertFalse(notice.contains("selectedNotice"))
        assertTrue(notice.contains("text = notice.content"))
        assertTrue(notice.contains("text = \"查看详情\""))
    }

    @Test
    fun startupPopupAndUpdateDialogUsePlainText() {
        val main = source("MainActivity.kt")

        assertFalse(main.contains("MarkdownPolicy.toPlainText(notice.content)"))
        assertFalse(main.contains("MarkdownContent("))
        assertTrue(main.contains("state.info.releaseNotes,"))
    }

    private fun source(path: String): String {
        val module = File("src/main/java/com/glut/schedule/$path")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$path")).readText()
    }
}

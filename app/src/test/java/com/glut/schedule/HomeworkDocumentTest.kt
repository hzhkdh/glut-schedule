package com.glut.schedule

import com.glut.schedule.service.chaoxing.homeworkConvertedPdfUrl
import com.glut.schedule.service.chaoxing.homeworkDocumentSourceRedirect
import com.glut.schedule.service.chaoxing.isHomeworkDocumentPreview
import com.glut.schedule.service.chaoxing.parseHomeworkDetailContent
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.junit.Assert.*
import org.junit.Test

class HomeworkDocumentTest {
    private val id = "1234567890abcdef1234567890abcdef"
    private val otherId = "abcdef1234567890abcdef1234567890"
    private val path = "/ananas/common-modules/attachment/insertCloud.html"

    private fun frame(name: String, type: String, objectId: String = id, src: String = path) =
        """<iframe class="attach-iframe" module="insertAttach" src="$src"
            objectid="$objectId" filetype="$type" filename="$name"></iframe>"""

    @Test fun recognizesOnlySupportedDocumentsFromTeacherContent() {
        val result = parseHomeworkDetailContent("""
            <div class="borderBox"><div class="pcon"><p>匿名作业要求</p>
              ${frame("讲义 &amp; 要求.PDF", "pdf")}
              ${frame("不匹配.pdf", "docx")}
              ${frame("外站.pdf", "pdf", src = "https://example.test$path")}
            </div></div>
            <div class="questionLi"><div class="mark_name">
              ${frame("范例.docx", "docx", otherId)}
              <div class="stem_answer">${frame("学生附件.pdf", "pdf")}</div>
            </div></div>
        """.trimIndent())
        assertEquals(listOf("讲义 & 要求.PDF", "范例.docx"), result.documents.map { it.name })
        assertTrue(result.html.contains("匿名作业要求"))
        assertFalse(result.html.contains("<iframe"))
    }

    @Test fun downloadRestrictionsAndPreviewAreBoundToTheCurrentDocument() {
        fun isAllowed(flag: String) = parseHomeworkDetailContent("""
            <script>window.ATTACH__NO_DOWNLOAD_PATH = "/read/only";</script>
            $flag
            <div class="borderBox"><div class="pcon">${frame("讲义.pdf", "pdf")}</div></div>
        """.trimIndent()).documents.single().downloadAllowed
        assertFalse(isAllowed(""))
        assertFalse(isAllowed("""<input id="allowDownloadAttachment" value="0">"""))
        assertTrue(isAllowed("""<input id="allowDownloadAttachment" value="1">"""))

        val page = Jsoup.parse("""<iframe class="insertdoc-online-pdf" objectid="$id"></iframe>""")
        assertTrue(isHomeworkDocumentPreview(page, id))
        assertFalse(isHomeworkDocumentPreview(page, otherId))
    }

    @Test fun attachmentSourceCanFollowOnlyTheOfficialSameWorkRedirect() {
        val source = "https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=7".toHttpUrl()
        val destination = "/mooc-ans/mooc2/work/dowork?workId=7"
        assertEquals("https://mooc1.chaoxing.com$destination", homeworkDocumentSourceRedirect(source, destination, "7")?.toString())
        assertNull(homeworkDocumentSourceRedirect(source, "$destination&workId=8", "7"))
        assertNull(homeworkDocumentSourceRedirect(source, destination.replace("=7", "=8"), "7"))
        assertNull(homeworkDocumentSourceRedirect(source, "https://example.test$destination", "7"))
        assertNull(homeworkDocumentSourceRedirect(source, destination.replace("/work/", "/exam/"), "7"))
    }

    @Test fun previewMustResolveToOfficialPdfForTheSameObject() {
        val url = "https://s3.cldisk.com/sv-w7/doc/12/34/56/$id/pdf/fcba0123-4567-89ab-cdef-0123456789ab"
        fun status(pdf: String = url, declaredId: String = id, state: String = "success") =
            """{"status":"$state","objectid":"$declaredId","pdf":"$pdf","pagenum":9}"""
        assertEquals(url, homeworkConvertedPdfUrl(status(), id)?.toString())
        val current = url.substringBeforeLast('/') + "/$id.pdf"
        for (partition in listOf("sv-w7", "sv-w8", "sv-w9")) {
            val preview = current.replace("sv-w7", partition)
            assertEquals(preview, homeworkConvertedPdfUrl(status(pdf = preview), id)?.toString())
        }
        assertNull(homeworkConvertedPdfUrl(status(pdf = current.replace("sv-w7", "sv-wtest")), id))
        assertNull(homeworkConvertedPdfUrl(status(pdf = current.replaceAfterLast('/', "$otherId.pdf")), id))
        assertNull(homeworkConvertedPdfUrl(status(state = "converting"), id))
        assertNull(homeworkConvertedPdfUrl(status(declaredId = otherId), id))
        assertNull(homeworkConvertedPdfUrl(status(pdf = url.replace(id, otherId)), id))
        assertNull(homeworkConvertedPdfUrl(status(pdf = url.replaceAfterLast('/', otherId)), id))
        assertNull(homeworkConvertedPdfUrl(status(pdf = url.replace("s3.cldisk.com", "example.test")), id))
        assertNull(homeworkConvertedPdfUrl(status(pdf = "$url?target=anything"), id))
    }
}

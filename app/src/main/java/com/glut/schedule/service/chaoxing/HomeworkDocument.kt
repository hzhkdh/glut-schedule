package com.glut.schedule.service.chaoxing

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.io.File
import java.util.Locale

internal data class HomeworkDetails(val html: String, val documents: List<HomeworkDocument> = emptyList())

internal enum class HomeworkDocumentFormat {
    PDF, DOCX;

    val extension: String get() = name.lowercase(Locale.ROOT)
}

internal data class HomeworkDocument(
    val objectId: String,
    val name: String,
    val format: HomeworkDocumentFormat,
    val downloadAllowed: Boolean
)

private val documentId = Regex("[0-9a-fA-F]{32}")
// 官网按数字分区存放预览，继续校验同一附件的对象路径与文件名。
private val convertedPdfPath = Regex("/sv-w[0-9]+/doc/(?:[0-9a-fA-F]{2}/){3}([0-9a-fA-F]{32})/pdf/([^/]+)")
private val legacyPdfName = Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
internal const val HOMEWORK_DOCUMENT_CACHE = "chaoxing_documents"
internal const val MAX_HOMEWORK_DOCUMENT_BYTES = 64L * 1024 * 1024

internal fun homeworkDocument(objectId: String, name: String, extension: String, downloadAllowed: Boolean): HomeworkDocument? {
    val format = HomeworkDocumentFormat.entries.firstOrNull { it.extension.equals(extension, ignoreCase = true) } ?: return null
    if (!documentId.matches(objectId) || !name.endsWith(".${format.extension}", ignoreCase = true) ||
        name.length !in 1..255 || name.any { it.isISOControl() || it == '/' || it == '\\' }) return null
    return HomeworkDocument(objectId, name, format, downloadAllowed)
}

/** 页面有独立的禁止下载路径时，只有官网明确开放的作业才使用原文件入口。 */
internal fun homeworkDocumentDownloadAllowed(document: Document): Boolean {
    val scripts = document.select("script").joinToString("\n") { it.data() }
    val configuredPath = Regex("""\bATTACH__NO_DOWNLOAD_PATH\s*=\s*(?:"([^"]*)"|'([^']*)')""")
        .findAll(scripts).any { match -> match.groupValues.drop(1).any { it.isNotBlank() } }
    val flags = document.select("input#allowDownloadAttachment")
    return !configuredPath || (flags.isNotEmpty() && flags.all { it.attr("value") == "1" })
}

internal fun isHomeworkDocumentPreview(document: Document, objectId: String): Boolean =
    documentId.matches(objectId) && document.select("iframe.insertdoc-online-pdf[objectid]")
        .any { it.attr("objectid") == objectId }

/** 仅接受官网同一作业的详情跳转，保持 GET 且校验作业 ID。 */
internal fun homeworkDocumentSourceRedirect(source: HttpUrl, location: String, workId: String): HttpUrl? =
    source.resolve(location)?.takeIf { url ->
        url.scheme == "https" && url.host == "mooc1.chaoxing.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.fragment == null &&
            url.encodedPath == "/mooc-ans/mooc2/work/dowork" && url.queryParameterValues("workId") == listOf(workId)
    }

/** 官网预览的 PDF 必须属于当前附件，兼容旧式预览文件名和当前的「对象 ID.pdf」。 */
internal fun homeworkConvertedPdfUrl(json: String, objectId: String): HttpUrl? {
    val status = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (!documentId.matches(objectId) || status.optString("status") != "success" ||
        status.optString("objectid") != objectId) return null
    return status.optString("pdf").toHttpUrlOrNull()?.takeIf { url ->
        val path = convertedPdfPath.matchEntire(url.encodedPath)
        val fileName = path?.groupValues?.get(2).orEmpty()
        url.scheme == "https" && url.host == "s3.cldisk.com" && url.port == 443 &&
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null &&
            path?.groupValues?.get(1) == objectId && (fileName == "$objectId.pdf" || legacyPdfName.matches(fileName))
    }
}

/** 网络错误页和空载荷不能交给系统 PDF 渲染器。 */
internal fun File.matchesHomeworkPdf(): Boolean = inputStream().buffered().use { input ->
    "%PDF-".all { input.read() == it.code }
}

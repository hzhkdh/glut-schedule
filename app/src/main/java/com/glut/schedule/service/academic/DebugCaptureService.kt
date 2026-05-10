package com.glut.schedule.service.academic

import android.content.Context
import android.os.Environment
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class DebugCaptureService(
    private val context: Context? = null,
    private val captureDirProvider: () -> File = {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "scheduleApp_debug"
        )
    },
    private val fileExportEnabled: Boolean = false
) {

    private val captureDir: File
        get() {
            val dir = captureDirProvider()
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    data class NetworkRequest(
        val url: String,
        val method: String,
        val headers: Map<String, String>?,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class CaptureResult(
        val html: String,
        val filePath: String,
        val networkRequests: List<NetworkRequest>,
        val diagnostics: String
    )

    val capturedRequests = mutableListOf<NetworkRequest>()

    fun recordRequest(url: String, method: String, headers: Map<String, String>?) {
        if (url.contains("glut.edu.cn") || url.contains("academic")) {
            synchronized(capturedRequests) {
                capturedRequests.add(NetworkRequest(sanitizeDebugUrl(url), method, headers))
            }
        }
    }

    fun findApiUrls(): List<String> {
        synchronized(capturedRequests) {
            return capturedRequests
                .map { it.url }
                .filter { url ->
                    url.contains("json", ignoreCase = true) ||
                        url.contains(".do", ignoreCase = true) ||
                        url.contains("ajax", ignoreCase = true) ||
                        url.contains("api", ignoreCase = true) ||
                        url.contains("getData", ignoreCase = true) ||
                        url.contains("query", ignoreCase = true) ||
                        url.contains("list", ignoreCase = true) ||
                        url.contains("search", ignoreCase = true) ||
                        url.endsWith(".json") ||
                        url.contains("timetable", ignoreCase = true) ||
                        url.contains("coursearrange", ignoreCase = true) ||
                        url.contains("schedule", ignoreCase = true) ||
                        url.contains("currcourse", ignoreCase = true)
                }
                .distinct()
        }
    }

    fun findTimetableUrls(): List<String> {
        synchronized(capturedRequests) {
            return capturedRequests
                .map { it.url }
                .filter { url ->
                    url.contains("showTimetable", ignoreCase = true) ||
                        url.contains("timetableType=STUDENT", ignoreCase = true) ||
                        url.contains("coursearrange", ignoreCase = true)
                }
                .distinct()
        }
    }

    suspend fun saveHtmlToFile(
        html: String,
        label: String = "timetable",
        forceFileExport: Boolean = false
    ): String =
        withContext(Dispatchers.IO) {
            if (!fileExportEnabled && !forceFileExport) {
                return@withContext "调试文件未保存：正式版默认关闭自动导出"
            }

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
            val filename = "glut_${label}_$timestamp.html"
            val file = File(captureDir, filename)
            file.writeText(html)

            val jsonDump = File(captureDir, "glut_${label}_$timestamp.json")
            val apiUrls = findApiUrls()
            val timetableUrls = findTimetableUrls()
            val jsonContent = buildString {
                appendLine("{")
                appendLine("  \"timestamp\": \"$timestamp\",")
                appendLine("  \"html_length\": ${html.length},")
                appendLine("  \"api_urls\": ${apiUrls.toTypedArray().contentToString()},")
                appendLine("  \"timetable_urls\": ${timetableUrls.toTypedArray().contentToString()},")
                appendLine("  \"all_requests\": [")
                synchronized(capturedRequests) {
                    capturedRequests.forEachIndexed { i, req ->
                        appendLine("    { \"url\": \"${req.url}\", \"method\": \"${req.method}\" }${if (i < capturedRequests.size - 1) "," else ""}")
                    }
                }
                appendLine("  ]")
                appendLine("}")
            }
            jsonDump.writeText(jsonContent)

            "已保存到: ${file.absolutePath}\nHTML: ${html.length} 字符\nAPI端点: ${apiUrls.size} 个\n课表URL: ${timetableUrls.size} 个"
        }

    suspend fun saveDiagnostics(
        diagnostics: String,
        htmlPreview: String,
        url: String,
        cookiePresent: Boolean,
        forceFileExport: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (!fileExportEnabled && !forceFileExport) {
            return@withContext "诊断文件未保存：正式版默认关闭自动导出"
        }

        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val file = File(captureDir, "glut_diagnostics_$timestamp.txt")
        val content = buildString {
            appendLine("=== GLUT Schedule Import Diagnostics ===")
            appendLine("Timestamp: $timestamp")
            appendLine("URL: $url")
            appendLine("Cookie present: $cookiePresent")
            appendLine("HTML preview length: ${htmlPreview.length}")
            appendLine()
            appendLine("=== Diagnostics ===")
            appendLine(diagnostics)
            appendLine()
            appendLine("=== Captured API URLs ===")
            findApiUrls().forEach { appendLine(it) }
            appendLine()
            appendLine("=== Captured Timetable URLs ===")
            findTimetableUrls().forEach { appendLine(it) }
            appendLine()
            appendLine("=== All Captured Requests ===")
            synchronized(capturedRequests) {
                capturedRequests.forEach { req ->
                    appendLine("${req.method} ${req.url}")
                }
            }
            appendLine()
            appendLine("=== HTML Preview (first 2000 chars) ===")
            appendLine(htmlPreview.take(2000))
        }
        file.writeText(content)
        "诊断信息已保存到: ${file.absolutePath}"
    }

    fun clearCookieHint(): String {
        val hasCapturedTimetable = findTimetableUrls().isNotEmpty()
        val hasApi = findApiUrls().isNotEmpty()
        synchronized(capturedRequests) {
            val academicRequests = capturedRequests
                .filter { it.url.contains("glut.edu.cn") }
                .map { it.url }
                .distinct()
            return buildString {
                appendLine("已记录 ${capturedRequests.size} 个网络请求")
                appendLine("GLUT域名请求: ${academicRequests.size} 个")
                if (academicRequests.isNotEmpty()) {
                    appendLine("最近请求:")
                    academicRequests.takeLast(5).forEach { appendLine("  $it") }
                }
                if (hasCapturedTimetable) appendLine("✓ 检测到课表页面请求")
                if (hasApi) appendLine("✓ 检测到 API 请求")
            }
        }
    }
}

fun sanitizeDebugUrl(url: String): String {
    return url.replace(
        Regex("""(?i)([?&](?:j_username|j_password|username|password|account|pwd|pass)=)[^&#]*""")
    ) { matchResult ->
        "${matchResult.groupValues[1]}<redacted>"
    }
}

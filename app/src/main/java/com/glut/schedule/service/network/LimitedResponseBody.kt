package com.glut.schedule.service.network

import okhttp3.ResponseBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset

const val MAX_HTML_RESPONSE_BYTES = 4 * 1024 * 1024
const val MAX_JSON_RESPONSE_BYTES = 512 * 1024
const val MAX_IMAGE_RESPONSE_BYTES = 2 * 1024 * 1024
const val MAX_BOOLEAN_RESPONSE_BYTES = 1024

/**
 * 在读取网络响应时强制执行硬上限，既检查 Content-Length，也防御分块传输或伪造长度。
 */
@Throws(IOException::class)
internal fun ResponseBody.readBytesLimited(maxBytes: Int): ByteArray {
    require(maxBytes > 0) { "maxBytes 必须大于 0" }
    val declaredLength = contentLength()
    if (declaredLength > maxBytes) {
        throw IOException("响应体超过允许上限：$declaredLength > $maxBytes")
    }

    val initialCapacity = when {
        declaredLength in 0..maxBytes.toLong() -> declaredLength.toInt()
        else -> minOf(8 * 1024, maxBytes)
    }
    val output = ByteArrayOutputStream(initialCapacity)
    byteStream().use { input ->
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) {
                throw IOException("响应体超过允许上限：$maxBytes 字节")
            }
            output.write(buffer, 0, count)
        }
    }
    return output.toByteArray()
}

@Throws(IOException::class)
internal fun ResponseBody.readStringLimited(
    maxBytes: Int,
    fallbackCharset: Charset = Charsets.UTF_8
): String {
    val charset = contentType()?.charset(fallbackCharset) ?: fallbackCharset
    return String(readBytesLimited(maxBytes), charset)
}

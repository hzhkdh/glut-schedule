package com.glut.schedule.service.background

import java.net.URI
import org.json.JSONObject

data class RemoteBackgroundCatalog(
    val revision: String,
    val generatedAt: String,
    val items: List<RemoteBackgroundItem>
)

data class RemoteBackgroundItem(
    val id: String,
    val displayName: String,
    val thumbnailUrl: String,
    val previewUrl: String,
    val originalUrl: String,
    val sha256: String,
    val byteSize: Long,
    val width: Int,
    val height: Int
)

object RemoteBackgroundCatalogParser {
    private val displayNamePattern = Regex("^.+《[^《》]+》$")
    private val idPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
    private val sha256Pattern = Regex("^[a-f0-9]{64}$")

    fun parse(raw: String): RemoteBackgroundCatalog {
        val root = JSONObject(raw)
        require(root.optInt("protocol") == 1) { "不支持的在线背景清单协议" }
        val revision = root.optString("revision").trim()
        require(revision.isNotEmpty()) { "在线背景清单缺少 revision" }
        val array = root.optJSONArray("items") ?: error("在线背景清单缺少 items")
        require(array.length() <= MAX_CATALOG_ITEMS) { "在线背景清单项目过多" }
        val ids = mutableSetOf<String>()
        val items = (0 until array.length()).map { index ->
            val value = array.getJSONObject(index)
            val id = value.getString("id").trim()
            require(idPattern.matches(id) && ids.add(id)) { "在线背景 ID 无效或重复" }
            val displayName = value.getString("displayName")
            // 名称由远程 JSON 完整控制；首尾空白视为配置错误，避免客户端静默改写展示文案。
            require(displayName == displayName.trim() && displayNamePattern.matches(displayName)) {
                "在线背景名称格式无效"
            }
            val thumbnailUrl = trustedUrl(value.getString("thumbnailUrl"), PREVIEW_HOSTS)
            val previewUrl = trustedUrl(value.getString("previewUrl"), PREVIEW_HOSTS)
            val originalUrl = trustedUrl(value.getString("originalUrl"), ORIGINAL_HOST)
            val sha256 = value.getString("sha256").lowercase()
            require(sha256Pattern.matches(sha256)) { "在线背景哈希无效" }
            val byteSize = value.getLong("byteSize")
            val width = value.getInt("width")
            val height = value.getInt("height")
            require(byteSize in 1..MAX_ORIGINAL_BYTES) { "在线背景文件大小无效" }
            require(width > 0 && height > 0 && width.toLong() * height <= MAX_ORIGINAL_PIXELS) {
                "在线背景图片尺寸无效"
            }
            RemoteBackgroundItem(
                id = id,
                displayName = displayName,
                thumbnailUrl = thumbnailUrl,
                previewUrl = previewUrl,
                originalUrl = originalUrl,
                sha256 = sha256,
                byteSize = byteSize,
                width = width,
                height = height
            )
        }
        return RemoteBackgroundCatalog(
            revision = revision,
            generatedAt = root.optString("generatedAt"),
            items = items
        )
    }

    private fun trustedUrl(raw: String, expectedHost: String): String {
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri?.scheme == "https" && uri.host == expectedHost && uri.userInfo == null) {
            "在线背景地址不受信任"
        }
        return uri.toString()
    }

    private fun trustedUrl(raw: String, expectedHosts: Set<String>): String {
        val uri = runCatching { URI(raw) }.getOrNull()
        require(uri?.scheme == "https" && uri.host in expectedHosts && uri.userInfo == null) {
            "在线背景地址不受信任"
        }
        return uri.toString()
    }

    private val PREVIEW_HOSTS = setOf("background.999314.xyz", "schedule-background-host.pages.dev")
    private const val ORIGINAL_HOST = "img.999314.xyz"
    private const val MAX_CATALOG_ITEMS = 100
    private const val MAX_ORIGINAL_BYTES = 25L * 1024 * 1024
    private const val MAX_ORIGINAL_PIXELS = 50_000_000L
}

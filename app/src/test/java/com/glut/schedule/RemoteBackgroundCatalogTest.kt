package com.glut.schedule

import com.glut.schedule.service.background.RemoteBackgroundCatalogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteBackgroundCatalogTest {
    @Test
    fun parseKeepsDisplayNameConfiguredByJson() {
        val catalog = RemoteBackgroundCatalogParser.parse(validCatalog())

        assertEquals("revision-1", catalog.revision)
        assertEquals("梵高《盛开的杏花》", catalog.items.single().displayName)
        assertEquals(5_058_607L, catalog.items.single().byteSize)
    }

    @Test
    fun parseRejectsUntrustedPreviewHost() {
        val raw = validCatalog().replace(
            "https://background.999314.xyz/generated/thumb.webp",
            "https://example.com/generated/thumb.webp"
        )

        assertThrows(IllegalArgumentException::class.java) {
            RemoteBackgroundCatalogParser.parse(raw)
        }
    }

    @Test
    fun parseRejectsDisplayNameWithLeadingOrTrailingWhitespace() {
        val raw = validCatalog().replace(
            "\"displayName\": \"梵高《盛开的杏花》\"",
            "\"displayName\": \" 梵高《盛开的杏花》 \""
        )

        assertThrows(IllegalArgumentException::class.java) {
            RemoteBackgroundCatalogParser.parse(raw)
        }
    }

    private fun validCatalog() = """
        {
          "protocol": 1,
          "revision": "revision-1",
          "generatedAt": "2026-08-03T12:00:00.000Z",
          "items": [{
            "id": "van-gogh-almond-blossom",
            "displayName": "梵高《盛开的杏花》",
            "thumbnailUrl": "https://background.999314.xyz/generated/thumb.webp",
            "previewUrl": "https://background.999314.xyz/generated/preview.webp",
            "originalUrl": "https://img.999314.xyz/file/original.jpg",
            "sha256": "${"a".repeat(64)}",
            "byteSize": 5058607,
            "width": 3139,
            "height": 2480
          }]
        }
    """.trimIndent()
}

package com.glut.schedule

import com.glut.schedule.service.background.RemoteBackgroundCatalogParser
import com.glut.schedule.ui.pages.artworkDetailAspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteBackgroundCatalogTest {
    @Test
    fun horizontalArtworkUsesItsExactAspectRatioWithoutLetterboxHeight() {
        assertEquals(1.5f, artworkDetailAspectRatio(1500, 1000)!!, 0.0001f)
        assertNull(artworkDetailAspectRatio(1000, 1500))
    }
    @Test
    fun parseKeepsDisplayNameConfiguredByJson() {
        val catalog = RemoteBackgroundCatalogParser.parse(validCatalog())
        val artwork = catalog.items.single().artwork

        assertEquals("revision-1", catalog.revision)
        assertEquals("梵高《盛开的杏花》", catalog.items.single().displayName)
        assertEquals(5_058_607L, catalog.items.single().byteSize)
        assertEquals("盛开的杏花", artwork?.titleZh)
        assertEquals("Almond Blossom", artwork?.titleEn)
        assertEquals("文森特·梵高", artwork?.artistZh)
        assertEquals("Vincent van Gogh", artwork?.artistEn)
        assertEquals("荷兰", artwork?.nationality)
        assertEquals("1890", artwork?.year)
        assertEquals("布面油画", artwork?.medium)
        assertEquals("阿姆斯特丹梵高博物馆", artwork?.collection)
        assertEquals(
            "梵高为弟弟提奥新生的儿子创作了这幅作品。他借鉴日本浮世绘的构图，让开花枝条铺展在明亮蓝色背景上，以清新的色彩寄托新生命与春日希望。粗细交错的枝干与平涂背景形成鲜明节奏，也让近距离花朵呈现装饰性的平面效果。",
            artwork?.description
        )
    }

    @Test
    fun parseKeepsLegacyProtocolOneItemWithoutArtwork() {
        val catalog = RemoteBackgroundCatalogParser.parse(validCatalog(includeArtwork = false))

        assertNull(catalog.items.single().artwork)
        assertEquals("梵高《盛开的杏花》", catalog.items.single().displayName)
        assertTrue(catalog.items.single().sources.isEmpty())
    }

    @Test
    fun parseKeepsPublicArtworkSources() {
        val raw = validCatalog().replace(
            "\"thumbnailUrl\"",
            "\"sources\": [{\"label\": \"梵高博物馆作品页\", \"url\": \"https://www.vangoghmuseum.nl/en/collection/s0176V1962\"}],\n            \"thumbnailUrl\""
        )

        val source = RemoteBackgroundCatalogParser.parse(raw).items.single().sources.single()

        assertEquals("梵高博物馆作品页", source.label)
        assertEquals("https://www.vangoghmuseum.nl/en/collection/s0176V1962", source.url)
    }

    @Test
    fun parseRejectsInvalidSourcesInsteadOfHidingThem() {
        val wrongType = validCatalog().replace("\"thumbnailUrl\"", "\"sources\": 123,\n            \"thumbnailUrl\"")
        val unsafeUrl = validCatalog().replace(
            "\"thumbnailUrl\"",
            "\"sources\": [{\"label\": \"来源\", \"url\": \"http://example.com/work\"}],\n            \"thumbnailUrl\""
        )

        assertThrows(IllegalArgumentException::class.java) { RemoteBackgroundCatalogParser.parse(wrongType) }
        assertThrows(IllegalArgumentException::class.java) { RemoteBackgroundCatalogParser.parse(unsafeUrl) }
    }

    @Test
    fun parseRejectsArtworkWithWrongJsonType() {
        val raw = validCatalog(includeArtwork = false).replace(
            "\"displayName\": \"梵高《盛开的杏花》\",",
            "\"displayName\": \"梵高《盛开的杏花》\",\n            \"artwork\": 123,"
        )

        assertThrows(IllegalArgumentException::class.java) {
            RemoteBackgroundCatalogParser.parse(raw)
        }
    }

    @Test
    fun parseRejectsArtworkStringFieldWithNumericType() {
        val raw = validCatalog().replace("\"year\": \"1890\"", "\"year\": 1890")

        assertThrows(IllegalArgumentException::class.java) {
            RemoteBackgroundCatalogParser.parse(raw)
        }
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

    @Test
    fun parseAllowsOneThousandItemsButRejectsOneThousandAndOne() {
        assertEquals(1_000, RemoteBackgroundCatalogParser.parse(catalogWithItemCount(1_000)).items.size)
        assertThrows(IllegalArgumentException::class.java) {
            RemoteBackgroundCatalogParser.parse(catalogWithItemCount(1_001))
        }
    }

    private fun catalogWithItemCount(count: Int): String {
        val items = (1..count).joinToString(",") { index ->
            """
            {
              "id": "artwork-$index",
              "displayName": "作者《作品$index》",
              "thumbnailUrl": "https://background.999314.xyz/generated/thumb.webp",
              "previewUrl": "https://background.999314.xyz/generated/preview.webp",
              "originalUrl": "https://img.999314.xyz/file/original.jpg",
              "sha256": "${"a".repeat(64)}",
              "byteSize": 1,
              "width": 1,
              "height": 1
            }
            """.trimIndent()
        }
        return """
        {
          "protocol": 1,
          "revision": "revision-$count",
          "items": [$items]
        }
        """.trimIndent()
    }

    private fun validCatalog(includeArtwork: Boolean = true): String {
        val artwork = if (includeArtwork) """
            "artwork": {
              "title": { "zh": "盛开的杏花", "en": "Almond Blossom" },
              "artist": { "zh": "文森特·梵高", "en": "Vincent van Gogh", "nationality": "荷兰" },
              "year": "1890",
              "medium": "布面油画",
              "collection": "阿姆斯特丹梵高博物馆",
              "description": "梵高为弟弟提奥新生的儿子创作了这幅作品。他借鉴日本浮世绘的构图，让开花枝条铺展在明亮蓝色背景上，以清新的色彩寄托新生命与春日希望。粗细交错的枝干与平涂背景形成鲜明节奏，也让近距离花朵呈现装饰性的平面效果。"
            },
        """.trimIndent() else ""
        return """
        {
          "protocol": 1,
          "revision": "revision-1",
          "generatedAt": "2026-08-03T12:00:00.000Z",
          "items": [{
            "id": "van-gogh-almond-blossom",
            "displayName": "梵高《盛开的杏花》",
            $artwork
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
}

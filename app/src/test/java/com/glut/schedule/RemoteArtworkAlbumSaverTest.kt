package com.glut.schedule

import com.glut.schedule.service.background.artworkFileName
import com.glut.schedule.service.background.artworkMimeType
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteArtworkAlbumSaverTest {
    @Test
    fun jpegArtworkUsesReadableSafeFileName() {
        assertEquals(
            "莫奈《印象·日出》.jpg",
            artworkFileName("莫奈《印象·日出》", "image/jpeg")
        )
        assertEquals(
            "作者《作品》.png",
            artworkFileName("作者/《作品》", "image/png")
        )
    }

    @Test
    fun supportedArtworkMimeTypeRejectsNonImages() {
        assertEquals("image/jpeg", artworkMimeType("image/jpeg; charset=binary"))
        assertEquals("image/png", artworkMimeType("image/png"))
        assertEquals("image/webp", artworkMimeType("image/webp"))
        assertEquals(null, artworkMimeType("application/octet-stream"))
    }
}

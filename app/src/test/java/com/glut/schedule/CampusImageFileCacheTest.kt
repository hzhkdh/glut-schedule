package com.glut.schedule

import com.glut.schedule.service.campus.CampusImageDocument
import com.glut.schedule.service.campus.CampusImageFileCache
import com.glut.schedule.service.campus.CampusImageType
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CampusImageFileCacheTest {
    @Test
    fun cachePublishesOneValidatedGenerationFilePerImageType() {
        val directory = Files.createTempDirectory("campus-image-cache").toFile()
        try {
            val cache = CampusImageFileCache(directory)
            cache.save(
                CampusImageType.ACADEMIC_CALENDAR,
                CampusImageDocument(
                    imageUrl = "https://xxfw.glut.edu.cn/calendar.png",
                    bytes = PNG_BYTES,
                    fetchedAt = 123L
                )
            )

            val files = directory.listFiles().orEmpty()
            assertEquals(1, files.size)
            files.single().writeBytes(byteArrayOf(1, 2, 3))
            assertNull(cache.load(CampusImageType.ACADEMIC_CALENDAR))
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D
        )
    }
}

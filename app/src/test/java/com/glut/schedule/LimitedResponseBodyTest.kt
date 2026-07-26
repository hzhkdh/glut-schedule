package com.glut.schedule

import com.glut.schedule.service.network.readBytesLimited
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class LimitedResponseBodyTest {
    @Test
    fun readsBodyWithinLimit() {
        val bytes = "hello".encodeToByteArray()

        assertArrayEquals(bytes, bytes.toResponseBody().readBytesLimited(5))
    }

    @Test
    fun rejectsBodyLargerThanLimit() {
        val body = ByteArray(9) { 1 }.toResponseBody()

        assertThrows(IOException::class.java) {
            body.readBytesLimited(8)
        }
    }
}

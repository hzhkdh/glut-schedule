package com.glut.schedule

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class CampusMapAssetContractTest {
    @Test
    fun bundledYanshanMapMatchesTheApprovedOriginalBytes() {
        val module = File("src/main/res/drawable-nodpi/yanshan_campus_map.png")
        val map = if (module.exists()) module else {
            File("app/src/main/res/drawable-nodpi/yanshan_campus_map.png")
        }

        assertEquals(
            "F32940A1B5723A6FDFC2B6F22960F4569C2F03D52CF9AD10FB09168986B752F8",
            map.readBytes().sha256()
        )
    }

    private fun ByteArray.sha256(): String = MessageDigest
        .getInstance("SHA-256")
        .digest(this)
        .joinToString("") { byte -> "%02X".format(byte) }
}

package com.glut.schedule

import com.glut.schedule.service.UpdateDownloadPolicy
import com.glut.schedule.service.downloadFile
import com.glut.schedule.service.parseUpdateMetadata
import com.glut.schedule.service.isTrustedApkIdentity
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSecurityTest {

    @Test
    fun downloadPolicyOnlyAllowsKnownHttpsHosts() {
        assertTrue(UpdateDownloadPolicy.isAllowedDownloadUrl("https://update.999314.xyz/app.apk"))
        assertTrue(UpdateDownloadPolicy.isAllowedDownloadUrl("https://github.com/hzhkdh/glut-schedule/releases/download/v1/app.apk"))
        assertFalse(UpdateDownloadPolicy.isAllowedDownloadUrl("http://update.999314.xyz/app.apk"))
        assertFalse(UpdateDownloadPolicy.isAllowedDownloadUrl("https://update.999314.xyz.evil.example/app.apk"))
        assertFalse(UpdateDownloadPolicy.isAllowedDownloadUrl("https://attacker.example/app.apk"))
    }

    @Test
    fun updateMetadataDoesNotRequireHashOrSizeButStillRequiresAllowedUrl() {
        val valid = """
            {
              "versionCode":122,
              "versionName":"0.22.0",
              "downloadUrl":"https://update.999314.xyz/app.apk",
              "updateDesc":"安全更新"
            }
        """.trimIndent()
        val info = parseUpdateMetadata(valid, "0.21.0")

        assertNotNull(info)
        assertEquals("0.22.0", info?.latestVersion)
        assertNull(parseUpdateMetadata(valid.replace("https://", "http://"), "0.21.0"))
    }

    @Test
    fun oversizedDownloadIsRejectedAndDeleted() = runBlocking {
        MockWebServer().use { server ->
            val payload = ByteArray(32) { it.toByte() }
            val target = File.createTempFile("secure-update", ".apk").apply { delete() }

            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            assertThrows(Exception::class.java) {
                runBlocking {
                    downloadFile(
                        client = OkHttpClient(),
                        url = server.url("/too-large.apk").toString(),
                        target = target,
                        maxBytes = 16,
                        urlValidator = { true },
                        onProgress = { _, _ -> }
                    )
                }
            }
            assertFalse(target.exists())
        }
    }

    @Test
    fun apkIdentityRequiresSamePackageNewerVersionAndSameSigner() {
        assertTrue(
            isTrustedApkIdentity(
                currentPackageName = "com.glut.schedule",
                currentVersionCode = 121,
                currentSignerDigests = setOf("signer-a"),
                archivePackageName = "com.glut.schedule",
                archiveVersionCode = 122,
                archiveSignerDigests = setOf("signer-a")
            )
        )
        assertFalse(
            isTrustedApkIdentity(
                "com.glut.schedule", 121, setOf("signer-a"),
                "com.attacker.app", 999, setOf("signer-a")
            )
        )
        assertFalse(
            isTrustedApkIdentity(
                "com.glut.schedule", 121, setOf("signer-a"),
                "com.glut.schedule", 122, setOf("signer-b")
            )
        )
        assertFalse(
            isTrustedApkIdentity(
                "com.glut.schedule", 121, setOf("signer-a"),
                "com.glut.schedule", 121, setOf("signer-a")
            )
        )
    }
}

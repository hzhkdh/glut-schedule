package com.glut.schedule

import com.glut.schedule.service.UpdateDownloadPolicy
import com.glut.schedule.service.downloadFile
import com.glut.schedule.service.parseUpdateMetadata
import com.glut.schedule.service.isTrustedApkIdentity
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun updateMetadataRequiresHashSizeAndAllowedUrl() {
        val valid = """
            {
              "versionCode":122,
              "versionName":"0.22.0",
              "downloadUrl":"https://update.999314.xyz/app.apk",
              "apkSha256":"${"a".repeat(64)}",
              "apkSize":123456,
              "updateDesc":"安全更新"
            }
        """.trimIndent()
        val info = parseUpdateMetadata(valid, "0.21.0")
        assertEquals("a".repeat(64), info?.apkSha256)
        assertEquals(123456L, info?.apkSizeBytes)

        assertNull(parseUpdateMetadata(valid.replace("\"apkSha256\":\"${"a".repeat(64)}\",", ""), "0.21.0"))
        assertNull(parseUpdateMetadata(valid.replace("https://", "http://"), "0.21.0"))
    }

    @Test
    fun oversizedOrHashMismatchedDownloadIsRejectedAndDeleted() = runBlocking {
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
                        expectedSha256 = sha256(payload),
                        expectedSizeBytes = payload.size.toLong(),
                        maxBytes = 16,
                        urlValidator = { true },
                        onProgress = { _, _ -> }
                    )
                }
            }
            assertFalse(target.exists())

            server.enqueue(MockResponse().setBody(Buffer().write(payload)))
            assertThrows(Exception::class.java) {
                runBlocking {
                    downloadFile(
                        client = OkHttpClient(),
                        url = server.url("/bad-hash.apk").toString(),
                        target = target,
                        expectedSha256 = "0".repeat(64),
                        expectedSizeBytes = payload.size.toLong(),
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

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

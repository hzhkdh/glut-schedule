package com.glut.schedule

import com.glut.schedule.partner.PartnerCourse
import com.glut.schedule.partner.PartnerIdentityColor
import com.glut.schedule.partner.PartnerScheduleApiService
import com.glut.schedule.partner.PartnerScheduleSnapshot
import com.glut.schedule.partner.inviteCodeFromInput
import com.glut.schedule.partner.partnerInviteShareText
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate

class PartnerScheduleApiServiceTest {

    private val snapshot = PartnerScheduleSnapshot(
        identityColor = PartnerIdentityColor.PINK,
        campus = "guilin-yanshan",
        semesterStartMonday = LocalDate.of(2026, 3, 9),
        semesterEndDate = LocalDate.of(2026, 7, 19),
        courses = listOf(
            PartnerCourse(
                id = "occ-1",
                title = "数据库系统",
                room = null,
                teacher = null,
                dayOfWeek = 2,
                startSection = 3,
                endSection = 4,
                weeks = listOf(8),
                startTime = "10:00",
                endTime = "11:40",
                ownerColor = PartnerIdentityColor.PINK
            )
        )
    )

    @Test
    fun createInviteUsesV1ContractAndReturnsRevocationMaterial() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """{"code":"ABCD2345EFGH6789","revokeToken":"secret-token","expiresAt":"2026-07-30T12:00:00Z"}"""
                )
            )
            val service = PartnerScheduleApiService(
                client = OkHttpClient(),
                baseUrl = server.url("/").toString()
            )

            val invite = service.createInvite(snapshot)

            assertEquals("ABCD2345EFGH6789", invite.code)
            val request = server.takeRequest()
            assertEquals("/v1/invites", request.path)
            assertEquals("POST", request.method)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(1, body.getJSONObject("snapshot").getInt("schemaVersion"))
            assertFalse(body.toString().contains("student"))
        }
    }

    @Test
    fun fetchInviteAcceptsQrPayloadAndReturnsSnapshot() = runBlocking {
        MockWebServer().use { server ->
            val encodedSnapshot = com.glut.schedule.partner.PartnerScheduleSnapshotCodec.encode(snapshot)
            server.enqueue(
                MockResponse().setBody("""{"snapshot":$encodedSnapshot}""")
            )
            val service = PartnerScheduleApiService(
                client = OkHttpClient(),
                baseUrl = server.url("/").toString()
            )

            val imported = service.fetchInvite("GLUT-SCHEDULE:V1:abcd2345efgh6789")

            assertEquals(snapshot, imported)
            assertEquals("/v1/invites/ABCD2345EFGH6789", server.takeRequest().path)
        }
    }

    @Test
    fun invalidInviteInputIsRejectedBeforeNetworkRequest() {
        assertThrows(IllegalArgumentException::class.java) {
            inviteCodeFromInput("1234")
        }
    }

    @Test
    fun shareTextContainsOnlyTheImportableInviteCode() {
        assertEquals(
            "ABCD2345EFGH6789",
            partnerInviteShareText("abcd2345efgh6789")
        )
    }

    @Test
    fun revokeInviteTreatsMissingRemoteInviteAsAlreadyRevoked() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val service = PartnerScheduleApiService(
                client = OkHttpClient(),
                baseUrl = server.url("/").toString()
            )

            service.revokeInvite("ABCD2345EFGH6789", "expired-invite-token")

            val request = server.takeRequest()
            assertEquals("DELETE", request.method)
            assertEquals("/v1/invites/ABCD2345EFGH6789", request.path)
            assertEquals("Bearer expired-invite-token", request.getHeader("Authorization"))
        }
    }
}

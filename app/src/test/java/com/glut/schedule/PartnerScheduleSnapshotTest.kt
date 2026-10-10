package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.graduateClassPeriods
import com.glut.schedule.partner.PartnerIdentityColor
import com.glut.schedule.partner.PartnerScheduleSnapshot
import com.glut.schedule.partner.PartnerScheduleSnapshotCodec
import com.glut.schedule.partner.createPartnerScheduleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PartnerScheduleSnapshotTest {

    private val course = ScheduleCourse(
        id = "course-1",
        title = "数据库系统",
        room = "雁山 04105",
        teacher = "张老师",
        colorHex = "#336699",
        occurrences = listOf(
            CourseOccurrence(
                id = "occurrence-1",
                courseId = "course-1",
                dayOfWeek = 2,
                startSection = 3,
                endSection = 4,
                weekText = "1-16周",
                note = "不应共享的本地备注"
            )
        )
    )

    private val periods = listOf(
        ClassPeriod(1, "08:00", "08:45"),
        ClassPeriod(2, "08:55", "09:40"),
        ClassPeriod(3, "10:00", "10:45"),
        ClassPeriod(4, "10:55", "11:40")
    )

    @Test
    fun snapshotRespectsRoomAndTeacherPrivacyAndNeverIncludesLocalNote() {
        val snapshot = createPartnerScheduleSnapshot(
            identityColor = PartnerIdentityColor.PINK,
            campus = "guilin-yanshan",
            semesterStartMonday = LocalDate.of(2026, 3, 9),
            semesterEndDate = LocalDate.of(2026, 7, 19),
            courses = listOf(course),
            classPeriods = periods,
            shareRoom = true,
            shareTeacher = false
        )

        val shared = snapshot.courses.single()
        assertEquals("雁山 04105", shared.room)
        assertNull(shared.teacher)
        assertEquals("shared-1", shared.id)
        assertEquals("10:00", shared.startTime)
        assertEquals("11:40", shared.endTime)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16), shared.weeks)

        val encoded = PartnerScheduleSnapshotCodec.encode(snapshot)
        assertFalse(encoded.contains("不应共享的本地备注"))
        assertFalse(encoded.contains("张老师"))
        assertFalse(encoded.contains("occurrence-1"))
    }

    @Test
    fun snapshotCodecRoundTripsTheCrossPlatformContract() {
        val original = createPartnerScheduleSnapshot(
            identityColor = PartnerIdentityColor.BLUE,
            campus = "nanning",
            semesterStartMonday = LocalDate.of(2026, 3, 2),
            semesterEndDate = LocalDate.of(2026, 7, 12),
            courses = listOf(course),
            classPeriods = periods,
            shareRoom = false,
            shareTeacher = true
        )

        val decoded: PartnerScheduleSnapshot =
            PartnerScheduleSnapshotCodec.decode(PartnerScheduleSnapshotCodec.encode(original))

        assertEquals(original, decoded)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(PartnerIdentityColor.BLUE, decoded.identityColor)
        assertTrue(decoded.courses.single().room == null)
        assertEquals("张老师", decoded.courses.single().teacher)
    }

    @Test
    fun snapshotCodecAcceptsEveryPublishedIdentityColor() {
        val original = createPartnerScheduleSnapshot(
            identityColor = PartnerIdentityColor.BLUE,
            campus = "guilin-yanshan",
            semesterStartMonday = LocalDate.of(2026, 3, 9),
            semesterEndDate = LocalDate.of(2026, 7, 19),
            courses = listOf(course),
            classPeriods = periods,
            shareRoom = false,
            shareTeacher = false
        )
        val blueSnapshot = PartnerScheduleSnapshotCodec.encode(original)
        val publishedColors = listOf(
            "pink",
            "blue",
            "purple",
            "teal",
            "green",
            "orange",
            "red",
            "gold"
        )

        publishedColors.forEach { color ->
            val raw = blueSnapshot.replace(
                "\"identityColor\":\"blue\"",
                "\"identityColor\":\"$color\""
            )
            assertEquals(
                color,
                PartnerScheduleSnapshotCodec.decode(raw).identityColor.storageValue
            )
        }
    }

    @Test
    fun snapshotCodecAcceptsTheCanonicalWechatV1Payload() {
        // 此向量同步写入微信端交接文档，用于保证两端不依赖平台私有字段。
        val wechatPayload = """
            {
              "schemaVersion": 1,
              "identityColor": "pink",
              "campus": "guilin-yanshan",
              "semesterStartMonday": "2026-03-09",
              "semesterEndDate": "2026-07-19",
              "courses": [{
                "id": "occ-1",
                "title": "数据库系统",
                "room": null,
                "teacher": null,
                "dayOfWeek": 2,
                "startSection": 3,
                "endSection": 4,
                "weeks": [8],
                "startTime": "10:00",
                "endTime": "11:40"
              }]
            }
        """.trimIndent()

        val decoded = PartnerScheduleSnapshotCodec.decode(wechatPayload)

        assertEquals(PartnerIdentityColor.PINK, decoded.identityColor)
        assertEquals("guilin-yanshan", decoded.campus)
        assertEquals("10:00", decoded.courses.single().startTime)
        assertEquals("11:40", decoded.courses.single().endTime)
        assertNull(decoded.courses.single().room)
        assertNull(decoded.courses.single().teacher)
    }

    @Test
    fun graduateSnapshotUsesV2AndKeepsCoursesWithoutAuthoritativeClockTimes() {
        val snapshot = createPartnerScheduleSnapshot(
            identityColor = PartnerIdentityColor.BLUE,
            campus = "graduate",
            semesterStartMonday = LocalDate.of(2026, 9, 7),
            semesterEndDate = LocalDate.of(2027, 1, 15),
            courses = listOf(course),
            classPeriods = graduateClassPeriods().mapIndexed { index, period ->
                // 即使测试期保存过自定义值，研究生快照也不能上传非权威钟点。
                period.copy(
                    startsAt = "%02d:00".format(8 + index),
                    endsAt = "%02d:45".format(8 + index)
                )
            },
            shareRoom = true,
            shareTeacher = true
        )

        assertEquals(2, snapshot.schemaVersion)
        assertEquals(1, snapshot.courses.size)
        assertNull(snapshot.courses.single().startTime)
        assertNull(snapshot.courses.single().endTime)
        assertEquals(snapshot, PartnerScheduleSnapshotCodec.decode(PartnerScheduleSnapshotCodec.encode(snapshot)))
    }

    @Test
    fun inviteGenerationKeepsTheProtocolVersionSelectedForTheCampus() {
        val module = File("src/main/java/com/glut/schedule/partner/PartnerScheduleViewModel.kt")
        val source = (
            if (module.exists()) module
            else File("app/src/main/java/com/glut/schedule/partner/PartnerScheduleViewModel.kt")
        ).readText()

        assertTrue(source.contains("schemaVersion = partnerSnapshotSchemaVersion(state.campusKey)"))
    }

    @Test
    fun v2AcceptsOnlyACompleteValidOrCompletelyUnknownTimePair() {
        val validV2 = """
            {
              "schemaVersion": 2,
              "identityColor": "pink",
              "campus": "graduate",
              "semesterStartMonday": "2026-09-07",
              "semesterEndDate": "2027-01-15",
              "courses": [{
                "id": "shared-1",
                "title": "合成课程",
                "room": null,
                "teacher": null,
                "dayOfWeek": 1,
                "startSection": 1,
                "endSection": 2,
                "weeks": [1, 2],
                "startTime": null,
                "endTime": null
              }]
            }
        """.trimIndent()

        val decoded = PartnerScheduleSnapshotCodec.decode(validV2)
        assertNull(decoded.courses.single().startTime)
        assertNull(decoded.courses.single().endTime)

        val mixedPair = validV2.replace("\"endTime\": null", "\"endTime\": \"09:40\"")
        val v1WithNulls = validV2.replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
        val blankStrings = validV2
            .replace("\"startTime\": null", "\"startTime\": \"\"")
            .replace("\"endTime\": null", "\"endTime\": \"\"")
        assertThrows(IllegalArgumentException::class.java) {
            PartnerScheduleSnapshotCodec.decode(mixedPair)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartnerScheduleSnapshotCodec.decode(v1WithNulls)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartnerScheduleSnapshotCodec.decode(blankStrings)
        }
    }
}

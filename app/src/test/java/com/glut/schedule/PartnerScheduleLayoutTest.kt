package com.glut.schedule

import com.glut.schedule.partner.PartnerCourse
import com.glut.schedule.partner.PartnerIdentityColor
import com.glut.schedule.partner.PartnerOverlapKind
import com.glut.schedule.partner.classifyPartnerOverlap
import com.glut.schedule.partner.commonFreeSegments
import com.glut.schedule.partner.partnerDisplayGroups
import com.glut.schedule.partner.partnerCardTimeRange
import com.glut.schedule.partner.canGeneratePartnerInvite
import org.junit.Assert.assertEquals
import org.junit.Test

class PartnerScheduleLayoutTest {

    private fun course(
        title: String,
        owner: PartnerIdentityColor,
        start: String,
        end: String,
        room: String? = "04105"
    ) = PartnerCourse(
        id = "$owner-$title-$start",
        title = title,
        room = room,
        teacher = "教师",
        dayOfWeek = 2,
        startSection = 1,
        endSection = 2,
        weeks = listOf(8),
        startTime = start,
        endTime = end,
        ownerColor = owner
    )

    @Test
    fun exactPartialSameCourseAndSeparateTimesAreClassified() {
        val mine = course("数据库", PartnerIdentityColor.BLUE, "08:00", "09:40")

        assertEquals(
            PartnerOverlapKind.EXACT,
            classifyPartnerOverlap(mine, course("大学英语", PartnerIdentityColor.PINK, "08:00", "09:40"))
        )
        assertEquals(
            PartnerOverlapKind.SAME_COURSE,
            classifyPartnerOverlap(mine, course("数据库", PartnerIdentityColor.PINK, "08:00", "09:40"))
        )
        assertEquals(
            PartnerOverlapKind.PARTIAL,
            classifyPartnerOverlap(mine, course("概率论", PartnerIdentityColor.PINK, "09:00", "10:30"))
        )
        assertEquals(
            PartnerOverlapKind.NONE,
            classifyPartnerOverlap(mine, course("体育", PartnerIdentityColor.PINK, "09:40", "10:30"))
        )
    }

    @Test
    fun adjacentBusyPeriodsProduceMaximalCommonFreeSegments() {
        val courses = listOf(
            course("早课", PartnerIdentityColor.BLUE, "08:00", "09:00"),
            course("中段", PartnerIdentityColor.PINK, "10:00", "11:00"),
            course("中段续", PartnerIdentityColor.BLUE, "11:00", "12:00")
        )

        assertEquals(
            listOf("09:00-10:00", "12:00-13:00"),
            commonFreeSegments(
                dayOfWeek = 2,
                week = 8,
                dayStart = "08:00",
                dayEnd = "13:00",
                courses = courses
            ).map { "${it.startTime}-${it.endTime}" }
        )
    }

    @Test
    fun exactOverlapBecomesOneDualCardWhileSeparateCoursesRemainSingles() {
        val exactMine = course("数据库", PartnerIdentityColor.BLUE, "08:00", "09:40")
        val exactPartner = course("大学英语", PartnerIdentityColor.PINK, "08:00", "09:40")
        val separate = course("体育", PartnerIdentityColor.BLUE, "10:00", "11:00")

        val groups = partnerDisplayGroups(
            week = 8,
            courses = listOf(exactMine, exactPartner, separate)
        )

        assertEquals(2, groups.size)
        assertEquals(PartnerOverlapKind.EXACT, groups[0].kind)
        assertEquals(2, groups[0].courses.size)
        assertEquals(PartnerOverlapKind.NONE, groups[1].kind)
        assertEquals(listOf("体育"), groups[1].courses.map { it.title })
    }

    @Test
    fun partialOverlapCardKeepsTheDisplayedCourseRealTimeRange() {
        val first = course("数据库", PartnerIdentityColor.BLUE, "08:00", "09:40")
        val second = course("概率论", PartnerIdentityColor.PINK, "09:00", "10:30")
        val group = partnerDisplayGroups(8, listOf(first, second)).single()

        assertEquals("08:00" to "09:40", partnerCardTimeRange(group))
    }

    @Test
    fun activeInviteMustBeRevokedBeforeGeneratingAnotherOne() {
        assertEquals(true, canGeneratePartnerInvite(hasCourses = true, isBusy = false, hasActiveInvite = false))
        assertEquals(false, canGeneratePartnerInvite(hasCourses = true, isBusy = false, hasActiveInvite = true))
        assertEquals(false, canGeneratePartnerInvite(hasCourses = false, isBusy = false, hasActiveInvite = false))
    }
}

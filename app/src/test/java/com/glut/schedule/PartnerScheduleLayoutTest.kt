package com.glut.schedule

import com.glut.schedule.partner.PartnerCourse
import com.glut.schedule.partner.PartnerIdentityColor
import com.glut.schedule.partner.PartnerOverlapKind
import com.glut.schedule.partner.classifyPartnerOverlap
import com.glut.schedule.partner.partnerDisplayGroups
import com.glut.schedule.partner.partnerCardTimeRange
import com.glut.schedule.partner.partnerPagerPageForWeek
import com.glut.schedule.partner.partnerWeekForPagerPage
import com.glut.schedule.partner.canGeneratePartnerInvite
import com.glut.schedule.partner.partnerAvailableIdentityColors
import com.glut.schedule.partner.partnerCardShowsMetadata
import com.glut.schedule.partner.partnerCourseDetailTimeRange
import com.glut.schedule.partner.partnerGridCourseTimeRange
import com.glut.schedule.partner.partnerGridRowIndex
import com.glut.schedule.partner.partnerHeaderPrimaryText
import com.glut.schedule.partner.PartnerViewModeIcon
import com.glut.schedule.partner.partnerDefaultShareOptions
import com.glut.schedule.partner.partnerViewModeIcon
import com.glut.schedule.partner.partnerCoursesForMode
import com.glut.schedule.partner.partnerInviteExpiryText
import com.glut.schedule.partner.partnerImportLocalColor
import com.glut.schedule.partner.partnerMixedCardCourses
import com.glut.schedule.partner.partnerCardVisibleCourses
import com.glut.schedule.partner.partnerOverlapDetailTitle
import com.glut.schedule.partner.partnerVisibleSections
import com.glut.schedule.partner.resolveDistinctPartnerColor
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.settings.PartnerScheduleViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PartnerScheduleLayoutTest {

    private fun course(
        title: String,
        owner: PartnerIdentityColor,
        start: String,
        end: String,
        room: String? = "04105",
        startSection: Int = 1,
        endSection: Int = 2
    ) = PartnerCourse(
        id = "$owner-$title-$start",
        title = title,
        room = room,
        teacher = "教师",
        dayOfWeek = 2,
        startSection = startSection,
        endSection = endSection,
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
    fun partialOverlapUsesTheUnionOfBothCoursesOnTheLocalGrid() {
        val mine = course(
            "数据库",
            PartnerIdentityColor.BLUE,
            "08:00",
            "09:40",
            startSection = 1,
            endSection = 2
        )
        val partner = course(
            "概率论",
            PartnerIdentityColor.PINK,
            "09:00",
            "10:30",
            startSection = 2,
            endSection = 3
        )

        val group = partnerDisplayGroups(8, listOf(mine, partner)).single()

        assertEquals(1, group.startSection)
        assertEquals(3, group.endSection)
    }

    @Test
    fun overlappingCoursesFromTheSameOwnerBecomeOneVisibleConflictCard() {
        val first = course("数据库", PartnerIdentityColor.BLUE, "08:00", "09:40")
        val second = course("大学英语", PartnerIdentityColor.BLUE, "09:00", "10:30")

        val groups = partnerDisplayGroups(8, listOf(first, second))

        assertEquals(1, groups.size)
        assertEquals(PartnerOverlapKind.SAME_OWNER_CONFLICT, groups.single().kind)
        assertEquals(listOf("数据库", "大学英语"), groups.single().courses.map { it.title })
    }

    @Test
    fun gridUsesLocalClassPeriodsButDetailKeepsSenderOriginalTime() {
        val partnerCourse = course(
            title = "高等数学",
            owner = PartnerIdentityColor.PINK,
            start = "08:30",
            end = "10:05"
        )
        val localPeriods = listOf(
            ClassPeriod(1, "08:00", "08:45"),
            ClassPeriod(2, "08:55", "09:40")
        )

        assertEquals(
            "08:00" to "09:40",
            partnerGridCourseTimeRange(partnerCourse, localPeriods)
        )
        assertEquals(
            "08:30" to "10:05",
            partnerCourseDetailTimeRange(partnerCourse)
        )
    }

    @Test
    fun overlapDetailTitleDoesNotExposeCourseCount() {
        assertEquals("重叠课程", partnerOverlapDetailTitle(courseCount = 2))
        assertEquals("课程详情", partnerOverlapDetailTitle(courseCount = 1))
    }

    @Test
    fun inviteExpiryUsesReadableLocalDateAndSafeFallback() {
        assertEquals(
            "有效期至 7月30日 20:00",
            partnerInviteExpiryText(
                expiresAt = "2026-07-30T12:00:00Z",
                zoneId = ZoneId.of("Asia/Shanghai")
            )
        )
        assertEquals(
            "有效期：生成后24小时内",
            partnerInviteExpiryText(
                expiresAt = "unexpected",
                zoneId = ZoneId.of("Asia/Shanghai")
            )
        )
    }

    @Test
    fun activeInviteMustBeRevokedBeforeGeneratingAnotherOne() {
        assertEquals(true, canGeneratePartnerInvite(hasCourses = true, isBusy = false, hasActiveInvite = false))
        assertEquals(false, canGeneratePartnerInvite(hasCourses = true, isBusy = false, hasActiveInvite = true))
        assertEquals(false, canGeneratePartnerInvite(hasCourses = false, isBusy = false, hasActiveInvite = false))
    }

    @Test
    fun partnerPagerClampsWeeksAtBothEdges() {
        assertEquals(1, partnerWeekForPagerPage(page = -1, maxWeek = 20))
        assertEquals(8, partnerWeekForPagerPage(page = 7, maxWeek = 20))
        assertEquals(20, partnerWeekForPagerPage(page = 99, maxWeek = 20))
        assertEquals(0, partnerPagerPageForWeek(week = 1, maxWeek = 20))
        assertEquals(19, partnerPagerPageForWeek(week = 99, maxWeek = 20))
    }

    @Test
    fun partnerHeaderAlwaysUsesStableWeekTitle() {
        assertEquals("第1周", partnerHeaderPrimaryText(weekNumber = 1))
        assertEquals("第4周", partnerHeaderPrimaryText(weekNumber = 4))
    }

    @Test
    fun currentViewModeSelectsTheMatchingHeaderIcon() {
        assertEquals(
            PartnerViewModeIcon.SINGLE_PERSON,
            partnerViewModeIcon(PartnerScheduleViewMode.PARTNER)
        )
        assertEquals(
            PartnerViewModeIcon.GROUP,
            partnerViewModeIcon(PartnerScheduleViewMode.COMBINED)
        )
    }

    @Test
    fun newInviteSharesRoomAndTeacherByDefault() {
        val defaults = partnerDefaultShareOptions()

        assertEquals(true, defaults.shareRoom)
        assertEquals(true, defaults.shareTeacher)
    }

    @Test
    fun viewModeSelectsPartnerOnlyOrCombinedCourses() {
        val mine = course("我的课程", PartnerIdentityColor.BLUE, "08:00", "09:40")
        val partner = course("TA的课程", PartnerIdentityColor.PINK, "10:00", "11:40")

        assertEquals(
            listOf("TA的课程"),
            partnerCoursesForMode(
                mode = PartnerScheduleViewMode.PARTNER,
                ownCourses = listOf(mine),
                partnerCourses = listOf(partner)
            ).map { it.title }
        )
        assertEquals(
            listOf("我的课程", "TA的课程"),
            partnerCoursesForMode(
                mode = PartnerScheduleViewMode.COMBINED,
                ownCourses = listOf(mine),
                partnerCourses = listOf(partner)
            ).map { it.title }
        )
    }

    @Test
    fun overlapBadgeOnlyAppearsWhenAtLeastThreeCoursesShareACard() {
        assertEquals(false, com.glut.schedule.partner.partnerShouldShowOverlapBadge(2))
        assertEquals(true, com.glut.schedule.partner.partnerShouldShowOverlapBadge(3))
    }

    @Test
    fun partnerGridUsesEqualRowsAndCompactsHiddenNoonSections() {
        val periods = (1..14).map { section ->
            ClassPeriod(section, "%02d:00".format((section + 7).coerceAtMost(23)), "%02d:45".format((section + 7).coerceAtMost(23)))
        }

        assertEquals(
            listOf(1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13, 14),
            partnerVisibleSections(periods, showNoon = false)
        )
        assertEquals(null, partnerGridRowIndex(5, periods, showNoon = false))
        assertEquals(4, partnerGridRowIndex(7, periods, showNoon = false))
        assertEquals(6, partnerGridRowIndex(7, periods, showNoon = true))
    }

    @Test
    fun partnerIdentityColorCannotDuplicateImportedPartnerColor() {
        val purple = PartnerIdentityColor.fromStorage("purple")

        assertEquals(
            PartnerIdentityColor.PINK,
            resolveDistinctPartnerColor(
                current = PartnerIdentityColor.BLUE,
                partner = PartnerIdentityColor.BLUE
            )
        )
        assertEquals(
            purple,
            resolveDistinctPartnerColor(
                current = purple,
                partner = PartnerIdentityColor.PINK
            )
        )
        assertEquals(
            7,
            partnerAvailableIdentityColors(PartnerIdentityColor.BLUE).size
        )
        assertEquals(
            false,
            PartnerIdentityColor.BLUE in partnerAvailableIdentityColors(PartnerIdentityColor.BLUE)
        )
    }

    @Test
    fun activeInvitePreventsImportFromChangingThePublishedIdentityColor() {
        assertThrows(IllegalArgumentException::class.java) {
            partnerImportLocalColor(
                current = PartnerIdentityColor.BLUE,
                partner = PartnerIdentityColor.BLUE,
                hasActiveInvite = true
            )
        }
        assertEquals(
            PartnerIdentityColor.BLUE,
            partnerImportLocalColor(
                current = PartnerIdentityColor.BLUE,
                partner = PartnerIdentityColor.PINK,
                hasActiveInvite = true
            )
        )
    }

    @Test
    fun mixedCardAlwaysKeepsOneCourseFromEachIdentity() {
        val pinkFirst = course("粉方第一门", PartnerIdentityColor.PINK, "08:00", "09:40")
        val pinkSecond = course("粉方第二门", PartnerIdentityColor.PINK, "09:00", "10:30")
        val blue = course("蓝方课程", PartnerIdentityColor.BLUE, "09:10", "10:00")

        assertEquals(
            listOf("粉方第一门", "蓝方课程"),
            partnerMixedCardCourses(listOf(pinkFirst, pinkSecond, blue)).map { it.title }
        )
        assertEquals(
            listOf("蓝方课程", "粉方第一门"),
            partnerMixedCardCourses(listOf(blue, pinkFirst, pinkSecond)).map { it.title }
        )
    }

    @Test
    fun sameOwnerConflictCardKeepsTwoVisibleCourseTitles() {
        val first = course("第一门", PartnerIdentityColor.BLUE, "08:00", "09:40")
        val second = course("第二门", PartnerIdentityColor.BLUE, "09:00", "10:30")

        assertEquals(
            listOf("第一门", "第二门"),
            partnerCardVisibleCourses(listOf(first, second)).map { it.title }
        )
    }

    @Test
    fun mixedCardChoosesATrulyOverlappingCrossOwnerPair() {
        val pinkEarly = course("粉方早课", PartnerIdentityColor.PINK, "08:00", "09:00")
        val pinkBridge = course("粉方桥接课", PartnerIdentityColor.PINK, "08:30", "09:30")
        val blueLate = course("蓝方晚课", PartnerIdentityColor.BLUE, "09:15", "10:00")

        assertEquals(
            listOf("粉方桥接课", "蓝方晚课"),
            partnerCardVisibleCourses(listOf(pinkEarly, pinkBridge, blueLate)).map { it.title }
        )
    }

    @Test
    fun onlySingleOwnerCardsShowRoomAndTeacherMetadata() {
        assertEquals(true, partnerCardShowsMetadata(PartnerOverlapKind.NONE))
        assertEquals(false, partnerCardShowsMetadata(PartnerOverlapKind.EXACT))
        assertEquals(false, partnerCardShowsMetadata(PartnerOverlapKind.SAME_COURSE))
        assertEquals(false, partnerCardShowsMetadata(PartnerOverlapKind.PARTIAL))
        assertEquals(false, partnerCardShowsMetadata(PartnerOverlapKind.SAME_OWNER_CONFLICT))
    }
}

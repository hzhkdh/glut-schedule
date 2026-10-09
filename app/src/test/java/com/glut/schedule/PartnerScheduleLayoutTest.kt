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
import com.glut.schedule.partner.partnerProfileDefaultName
import com.glut.schedule.partner.partnerCoursesForMode
import com.glut.schedule.partner.partnerInviteExpiryText
import com.glut.schedule.partner.partnerImportLocalColor
import com.glut.schedule.partner.isPartnerSemesterCompatible
import com.glut.schedule.partner.requirePartnerSemesterCompatible
import com.glut.schedule.partner.partnerMixedCardCourses
import com.glut.schedule.partner.partnerCardVisibleCourses
import com.glut.schedule.partner.partnerCanonicalSection
import com.glut.schedule.partner.partnerOverlapDetailTitle
import com.glut.schedule.partner.partnerHasNoonSections
import com.glut.schedule.partner.partnerRawSectionForCanonical
import com.glut.schedule.partner.partnerVisibleSections
import com.glut.schedule.partner.resolveDistinctPartnerColor
import com.glut.schedule.partner.resolveImportedProfileColor
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.displaySectionLabel
import com.glut.schedule.data.settings.PartnerScheduleViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PartnerScheduleLayoutTest {

    @Test
    fun unnamedImportedProfilesUseCompactStableDefaultNames() {
        assertEquals("课表1", partnerProfileDefaultName(0))
        assertEquals("课表2", partnerProfileDefaultName(1))
    }

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
            classifyPartnerOverlap(
                mine,
                course(
                    "概率论",
                    PartnerIdentityColor.PINK,
                    "09:00",
                    "10:30",
                    startSection = 2,
                    endSection = 3
                )
            )
        )
        assertEquals(
            PartnerOverlapKind.NONE,
            classifyPartnerOverlap(
                mine,
                course(
                    "体育",
                    PartnerIdentityColor.PINK,
                    "09:40",
                    "10:30",
                    startSection = 3,
                    endSection = 4
                )
            )
        )
    }

    @Test
    fun exactOverlapBecomesOneDualCardWhileSeparateCoursesRemainSingles() {
        val exactMine = course("数据库", PartnerIdentityColor.BLUE, "08:00", "09:40")
        val exactPartner = course("大学英语", PartnerIdentityColor.PINK, "08:00", "09:40")
        val separate = course(
            "体育",
            PartnerIdentityColor.BLUE,
            "10:00",
            "11:00",
            startSection = 3,
            endSection = 4
        )

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
        val second = course(
            "概率论",
            PartnerIdentityColor.PINK,
            "09:00",
            "10:30",
            startSection = 2,
            endSection = 3
        )
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
    fun nanningRegularSectionsAlignWithGuilinSectionsAfterTheNoonRows() {
        assertEquals(1, partnerCanonicalSection("guilin-yanshan", 1))
        assertEquals(5, partnerCanonicalSection("guilin-yanshan", 5))
        assertEquals(7, partnerCanonicalSection("guilin-yanshan", 7))
        assertEquals(7, partnerCanonicalSection("nanning", 5))
        assertEquals(13, partnerCanonicalSection("nanning", 11))

        assertEquals(7, partnerRawSectionForCanonical("guilin-pingfeng", 7))
        assertEquals(5, partnerRawSectionForCanonical("nanning", 7))
        assertEquals(null, partnerRawSectionForCanonical("nanning", 5))
    }

    /**
     * 研究生课表的映射：1-4 上午、5-8 下午、9-12 晚上，落到桂林 14 行坐标是 1-4 / 7-10 / 11-14。
     *
     * 这是缺陷修复而不是新功能：此前 graduate 不在任何分支里，本地 campus key 回落成
     * `guilin-yanshan`，研究生的第 5、6 节被当成午休行过滤掉，反查不到行时整张卡片被丢弃。
     */
    @Test
    fun graduateSectionsShiftPastTheMissingNoonRows() {
        assertEquals(1, partnerCanonicalSection("graduate", 1))
        assertEquals(4, partnerCanonicalSection("graduate", 4))
        assertEquals(7, partnerCanonicalSection("graduate", 5))
        assertEquals(10, partnerCanonicalSection("graduate", 8))
        assertEquals(11, partnerCanonicalSection("graduate", 9))
        assertEquals(14, partnerCanonicalSection("graduate", 12))

        // 12 节全部可见，且一条都不落在午休行上。
        val periods = (1..12).map { section -> ClassPeriod(section, "08:00", "08:45") }
        assertEquals(
            listOf(1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13, 14),
            partnerVisibleSections(periods, showNoon = false, campus = "graduate")
        )
        // 下午第一节（内部第 5 节）必须能反查到行，否则卡片会被整张丢掉。
        assertEquals(4, partnerGridRowIndex(7, periods, showNoon = false, campus = "graduate"))
    }

    /** 正反两向必须互为逆运算，否则卡片能定位、点击/详情却拿不回正确的节次号。 */
    @Test
    fun graduateCanonicalAndRawSectionRoundTrip() {
        (1..12).forEach { raw ->
            val canonical = partnerCanonicalSection("graduate", raw)
            assertEquals(
                "研究生第 $raw 节往返后应还原",
                raw,
                partnerRawSectionForCanonical("graduate", canonical)
            )
        }
        // 研究生只有 12 节，比南宁多一节：13、14 这两个 canonical 行回不到任何原始节次。
        assertEquals(null, partnerRawSectionForCanonical("graduate", 5))
        assertEquals(null, partnerRawSectionForCanonical("graduate", 6))
        assertEquals(12, partnerRawSectionForCanonical("graduate", 14))
        assertEquals(null, partnerRawSectionForCanonical("graduate", 15))
        // 边界差异必须保留：南宁只到 13，研究生到 14。
        assertEquals(null, partnerRawSectionForCanonical("nanning", 14))
        assertEquals(11, partnerRawSectionForCanonical("nanning", 13))
    }

    /**
     * 节次列标号必须按来源能力判断，而不是按 `classPeriods.size <= 11` 推断。
     *
     * 研究生也是 12 节，旧推断会走「有中午」的分支，把它的第 5、6 节标成「午1/午2」，
     * 而研究生的第 5 节就是下午第一节。
     */
    @Test
    fun partnerSectionLabelsFollowCampusCapabilitiesNotPeriodCount() {
        assertTrue(partnerHasNoonSections("guilin-yanshan"))
        assertTrue(partnerHasNoonSections("guilin-pingfeng"))
        assertFalse(partnerHasNoonSections("nanning"))
        assertFalse(partnerHasNoonSections("graduate"))

        // 桂林内部第 7 节显示成「5」；研究生第 5 节本来就是「5」。
        assertEquals("5", displaySectionLabel(7, hasNoon = partnerHasNoonSections("guilin-yanshan")))
        assertEquals("5", displaySectionLabel(5, hasNoon = partnerHasNoonSections("graduate")))
        // 研究生 12 节的标签里一个「午」字都不该出现，且必须直排 1-12。
        assertEquals(
            (1..12).map(Int::toString),
            (1..12).map { displaySectionLabel(it, hasNoon = partnerHasNoonSections("graduate")) }
        )
    }

    @Test
    fun nanningAfternoonCoursesStayVisibleWhenGuilinNoonRowsAreHidden() {
        val nanningPeriods = listOf(
            ClassPeriod(1, "08:40", "09:20"),
            ClassPeriod(2, "09:25", "10:05"),
            ClassPeriod(3, "10:25", "11:05"),
            ClassPeriod(4, "11:10", "11:50"),
            ClassPeriod(5, "14:30", "15:10"),
            ClassPeriod(6, "15:15", "15:55")
        )

        assertEquals(
            listOf(1, 2, 3, 4, 7, 8),
            partnerVisibleSections(
                periods = nanningPeriods,
                showNoon = false,
                campus = "nanning"
            )
        )
        assertEquals(
            4,
            partnerGridRowIndex(
                section = 7,
                periods = nanningPeriods,
                showNoon = false,
                campus = "nanning"
            )
        )
    }

    @Test
    fun crossCampusCoursesOverlapByLogicalSectionInsteadOfClockTime() {
        val guilin = course(
            title = "桂林课程",
            owner = PartnerIdentityColor.BLUE,
            start = "18:30",
            end = "19:15",
            startSection = 11,
            endSection = 11
        )
        val nanning = course(
            title = "南宁课程",
            owner = PartnerIdentityColor.PINK,
            start = "19:30",
            end = "20:10",
            startSection = 9,
            endSection = 9
        )

        val group = partnerDisplayGroups(
            week = 8,
            courses = listOf(guilin, nanning),
            campusByOwner = mapOf(
                PartnerIdentityColor.BLUE to "guilin-yanshan",
                PartnerIdentityColor.PINK to "nanning"
            )
        ).single()

        assertEquals(PartnerOverlapKind.EXACT, group.kind)
        assertEquals(11, group.startSection)
        assertEquals(11, group.endSection)
        assertEquals(listOf("桂林课程", "南宁课程"), group.courses.map { it.title })
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
    fun importedProfileColorAvoidsMineAndBothStoredSlots() {
        val reserved = setOf(
            PartnerIdentityColor.BLUE,
            PartnerIdentityColor.PINK,
            PartnerIdentityColor.PURPLE
        )

        assertEquals(
            PartnerIdentityColor.TEAL,
            resolveImportedProfileColor(PartnerIdentityColor.BLUE, reserved)
        )
        assertEquals(
            PartnerIdentityColor.GREEN,
            resolveImportedProfileColor(PartnerIdentityColor.GREEN, reserved)
        )
        assertEquals(
            5,
            partnerAvailableIdentityColors(reserved).size
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
    fun partnerSnapshotMustBelongToTheCurrentSemesterBeforeImport() {
        val snapshot = com.glut.schedule.partner.PartnerScheduleSnapshot(
            identityColor = PartnerIdentityColor.PINK,
            campus = "guilin-yanshan",
            semesterStartMonday = LocalDate.of(2026, 9, 7),
            semesterEndDate = LocalDate.of(2027, 1, 17),
            courses = emptyList()
        )

        assertEquals(
            true,
            isPartnerSemesterCompatible(
                localStart = LocalDate.of(2026, 9, 7),
                localEnd = LocalDate.of(2027, 1, 17),
                snapshot = snapshot
            )
        )
        assertEquals(
            false,
            isPartnerSemesterCompatible(
                localStart = LocalDate.of(2026, 3, 9),
                localEnd = LocalDate.of(2026, 7, 19),
                snapshot = snapshot
            )
        )

        val error = assertThrows(IllegalArgumentException::class.java) {
            requirePartnerSemesterCompatible(
                localStart = LocalDate.of(2026, 3, 9),
                localEnd = LocalDate.of(2026, 7, 19),
                snapshot = snapshot
            )
        }
        assertEquals("该邀请码不是当前学期的课表，请让TA重新生成", error.message)
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
        val pinkEarly = course(
            "粉方早课",
            PartnerIdentityColor.PINK,
            "08:00",
            "09:00",
            startSection = 1,
            endSection = 1
        )
        val pinkBridge = course(
            "粉方桥接课",
            PartnerIdentityColor.PINK,
            "08:30",
            "09:30",
            startSection = 1,
            endSection = 2
        )
        val blueLate = course(
            "蓝方晚课",
            PartnerIdentityColor.BLUE,
            "09:15",
            "10:00",
            startSection = 2,
            endSection = 2
        )

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

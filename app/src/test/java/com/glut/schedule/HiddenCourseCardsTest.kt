package com.glut.schedule

import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.HiddenCardScope
import com.glut.schedule.data.model.HiddenCourseRule
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicWeeksForText
import com.glut.schedule.data.model.applyHiddenCourseRules
import com.glut.schedule.data.model.decodeHiddenCourseRules
import com.glut.schedule.data.model.encodeHiddenCourseRules
import com.glut.schedule.data.model.hiddenCardCount
import com.glut.schedule.data.model.hiddenCourseKey
import com.glut.schedule.data.model.hiddenRuleLabel
import com.glut.schedule.data.model.hiddenRuleScopeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动隐藏课程卡片的三档规则。
 *
 * 重点守两件事：一是「按周剥离」用的是与解析器同一套周次口径，二是过滤是纯函数、
 * 不改原对象——课程数据每次刷新都会被整表重建，规则必须能反复叠加到新数据上。
 */
class HiddenCourseCardsTest {

    private val semester = "guilin-2026-春"

    /** 编解码的字段分隔符与实现一致是 NUL；这里用 `Char(0)` 而不是转义字面量，避免源码里混进真实 NUL 字节。 */
    private val sep = Char(0)

    private fun occurrence(
        id: String = "c1-occurrence-0",
        dayOfWeek: Int = 2,
        startSection: Int = 3,
        endSection: Int = 4,
        weekText: String = "1-16周"
    ) = CourseOccurrence(
        id = id,
        courseId = "c1",
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        weekText = weekText,
        note = "04105"
    )

    private fun course(
        id: String = "c1",
        title: String = "数据库系统",
        occurrences: List<CourseOccurrence> = listOf(occurrence())
    ) = ScheduleCourse(
        id = id,
        title = title,
        room = "04105",
        teacher = "张老师",
        colorHex = "#1265D9",
        occurrences = occurrences
    )

    // ---- 键 ----

    @Test
    fun courseKeyMatchesTheColorOverrideKey() {
        // 隐藏与配色必须用同一个键：两处口径一旦分叉，同一张卡会出现「颜色记得住、删除记不住」。
        assertEquals(
            CourseColorMapper.colorKey("c1", " 数据库系统 @06404D "),
            hiddenCourseKey("c1", " 数据库系统 @06404D ")
        )
    }

    @Test
    fun courseKeyFallsBackToLowercasedIdWhenTitleIsBlank() {
        assertEquals("c1", hiddenCourseKey("C1", "   "))
    }

    // ---- 三档过滤 ----

    @Test
    fun courseScopeDropsEveryCardOfThatCourse() {
        val target = course(
            occurrences = listOf(
                occurrence(id = "a", dayOfWeek = 2),
                occurrence(id = "b", dayOfWeek = 4, startSection = 5, endSection = 6)
            )
        )
        val other = course(id = "c2", title = "高等数学")

        val result = applyHiddenCourseRules(
            listOf(target, other),
            listOf(HiddenCourseRule(HiddenCardScope.COURSE, "数据库系统"))
        )

        assertEquals(listOf("c2"), result.map { it.id })
    }

    @Test
    fun occurrenceScopeDropsAllWeeksButKeepsSiblingOccurrences() {
        val target = course(
            occurrences = listOf(
                occurrence(id = "tue", dayOfWeek = 2, startSection = 3, endSection = 4),
                occurrence(id = "thu", dayOfWeek = 4, startSection = 5, endSection = 6)
            )
        )

        val result = applyHiddenCourseRules(
            listOf(target),
            listOf(
                HiddenCourseRule(
                    HiddenCardScope.OCCURRENCE, "数据库系统",
                    dayOfWeek = 2, startSection = 3, endSection = 4
                )
            )
        )

        assertEquals(1, result.size)
        assertEquals(listOf("thu"), result.first().occurrences.map { it.id })
    }

    @Test
    fun weekScopeStripsOnlyThatWeekAndKeepsTheRest() {
        val target = course(occurrences = listOf(occurrence(weekText = "1-16周")))

        val result = applyHiddenCourseRules(
            listOf(target),
            listOf(
                HiddenCourseRule(
                    HiddenCardScope.WEEK, "数据库系统",
                    dayOfWeek = 2, startSection = 3, endSection = 4, week = 5
                )
            )
        )

        val weeks = result.first().occurrences.map { it.weekText }
        assertEquals(listOf("1-4周", "6-16周"), weeks)
        // 第 5 周在剩下的任何一段里都不该出现，两侧的周必须都还在。
        assertEquals(listOf(false, false), weeks.map { 5 in academicWeeksForText(it) })
        assertEquals(listOf(true, false), weeks.map { 4 in academicWeeksForText(it) })
        assertEquals(listOf(false, true), weeks.map { 6 in academicWeeksForText(it) })
    }

    @Test
    fun weekScopeOnlyAppliesToTheWeekThatActuallyExists() {
        // 这条防的是「规则命中了课次，但那一周本来就不在这个课次里」。第 20 周不在 1-16 周内，
        // 剔除后课次应当原样保留，而不是被改写成一段奇怪的周次文本。
        val target = course(occurrences = listOf(occurrence(weekText = "1-16周")))

        val result = applyHiddenCourseRules(
            listOf(target),
            listOf(
                HiddenCourseRule(
                    HiddenCardScope.WEEK, "数据库系统",
                    dayOfWeek = 2, startSection = 3, endSection = 4, week = 20
                )
            )
        )

        assertEquals(listOf("1-16周"), result.first().occurrences.map { it.weekText })
    }

    @Test
    fun strippingTheOnlyWeekDropsTheOccurrenceAndThenTheCourse() {
        val target = course(occurrences = listOf(occurrence(weekText = "第5周")))

        val result = applyHiddenCourseRules(
            listOf(target),
            listOf(
                HiddenCourseRule(
                    HiddenCardScope.WEEK, "数据库系统",
                    dayOfWeek = 2, startSection = 3, endSection = 4, week = 5
                )
            )
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun filteringDoesNotMutateTheInput() {
        val original = course(occurrences = listOf(occurrence(weekText = "1-16周")))

        applyHiddenCourseRules(
            listOf(original),
            listOf(
                HiddenCourseRule(
                    HiddenCardScope.WEEK, "数据库系统",
                    dayOfWeek = 2, startSection = 3, endSection = 4, week = 5
                )
            )
        )

        // 规则要能在每次刷新后重新叠加到新数据上，所以绝不能就地改输入对象。
        assertEquals("1-16周", original.occurrences.first().weekText)
        assertEquals(1, original.occurrences.size)
    }

    @Test
    fun unmatchedRuleLeavesTheCourseUntouched() {
        val target = course(occurrences = listOf(occurrence(weekText = "1-16周")))

        val result = applyHiddenCourseRules(
            listOf(target),
            listOf(
                HiddenCourseRule(
                    HiddenCardScope.OCCURRENCE, "另一门课",
                    dayOfWeek = 2, startSection = 3, endSection = 4
                )
            )
        )

        assertEquals(1, result.size)
        assertEquals(listOf("1-16周"), result.first().occurrences.map { it.weekText })
    }

    // ---- 计数 ----

    @Test
    fun hiddenCardCountOnlyCountsRulesThatStillHitSomething() {
        val courses = listOf(
            course(occurrences = listOf(occurrence(dayOfWeek = 2, startSection = 3, endSection = 4)))
        )

        val hitting = HiddenCourseRule(
            HiddenCardScope.OCCURRENCE, "数据库系统",
            dayOfWeek = 2, startSection = 3, endSection = 4
        )
        val stale = HiddenCourseRule(
            HiddenCardScope.OCCURRENCE, "数据库系统",
            dayOfWeek = 6, startSection = 9, endSection = 10
        )

        // 失效规则有意保留在存储里（课再排回来依然是隐藏的），但刷新弹窗上的「N 张」
        // 不能把它算进去，否则会出现「说保留了 2 张却只看得见 1 张」。
        assertEquals(1, hiddenCardCount(courses, listOf(hitting, stale)))
    }

    @Test
    fun hiddenCardCountIgnoresWeekRulesForWeeksThatAreNotInRange() {
        val courses = listOf(course(occurrences = listOf(occurrence(weekText = "1-16周"))))

        val inRange = HiddenCourseRule(HiddenCardScope.WEEK, "数据库系统", 2, 3, 4, week = 5)
        val outOfRange = HiddenCourseRule(HiddenCardScope.WEEK, "数据库系统", 2, 3, 4, week = 20)

        assertEquals(1, hiddenCardCount(courses, listOf(inRange, outOfRange)))
    }

    // ---- 文案 ----

    @Test
    fun weekLabelShowsTheExactWeek() {
        val courses = listOf(course())
        val rule = HiddenCourseRule(HiddenCardScope.WEEK, "数据库系统", 2, 3, 4, week = 5)
        assertEquals("周二 3-4 节 · 第 5 周", hiddenRuleScopeText(rule, courses))
        assertEquals("数据库系统 · 周二 3-4 节 · 第 5 周", hiddenRuleLabel(rule, courses))
    }

    @Test
    fun occurrenceLabelUsesTheCompactWeekRangeInsteadOfTheRawText() {
        val courses = listOf(course(occurrences = listOf(occurrence(weekText = "第1-16周"))))
        val rule = HiddenCourseRule(HiddenCardScope.OCCURRENCE, "数据库系统", 2, 3, 4)

        // 展示的是「该课次实际生效的周次」，不是 weekText 原文里的「第」噪声。
        assertEquals("周二 3-4 节 · 1-16周", hiddenRuleScopeText(rule, courses))
    }

    @Test
    fun courseLabelWarnsThatSameNamedCoursesAreHiddenTogether() {
        val courses = listOf(course())
        val rule = HiddenCourseRule(HiddenCardScope.COURSE, "数据库系统")

        assertEquals("数据库系统，同名课程一并隐藏", hiddenRuleScopeText(rule, courses))
        assertEquals("数据库系统（整门课，同名课程一并隐藏）", hiddenRuleLabel(rule, courses))
    }

    // ---- 编解码 ----

    @Test
    fun encodeDecodeRoundTripsAllThreeScopes() {
        val rules = listOf(
            HiddenCourseRule(HiddenCardScope.COURSE, "数据库系统"),
            HiddenCourseRule(HiddenCardScope.OCCURRENCE, "高等数学", 4, 5, 6),
            HiddenCourseRule(HiddenCardScope.WEEK, "大学物理 @06404D", 1, 1, 2, week = 3)
        )

        val decoded = decodeHiddenCourseRules(encodeHiddenCourseRules(mapOf(semester to rules)))

        assertEquals(rules.toSet(), decoded[semester]?.toSet())
    }

    @Test
    fun encodingKeepsSemestersApart() {
        val encoded = encodeHiddenCourseRules(
            mapOf(
                semester to listOf(HiddenCourseRule(HiddenCardScope.COURSE, "数据库系统")),
                "nanning-2026-秋" to listOf(HiddenCourseRule(HiddenCardScope.COURSE, "高等数学"))
            )
        )

        val decoded = decodeHiddenCourseRules(encoded)
        assertEquals(listOf("数据库系统"), decoded.getValue(semester).map { it.courseKey })
        assertEquals(listOf("高等数学"), decoded.getValue("nanning-2026-秋").map { it.courseKey })
    }

    @Test
    fun decodingDropsBadEntriesInsteadOfFailingWholesale() {
        val good = encodeHiddenCourseRules(
            mapOf(semester to listOf(HiddenCourseRule(HiddenCardScope.WEEK, "数据库系统", 2, 3, 4, week = 5)))
        ).first()

        val corrupted = setOf(
            good,
            "",
            "只有学期id",
            "$semester${sep}unknown-scope${sep}数据库系统",
            "$semester${sep}week${sep}数据库系统${sep}9${sep}3${sep}4${sep}5",   // 星期越界
            "$semester${sep}week${sep}数据库系统${sep}2${sep}3${sep}4${sep}99",  // 周次越界
            "$semester${sep}occurrence${sep}数据库系统${sep}2${sep}3",           // 字段不足
            "${sep}course${sep}数据库系统"                                        // 学期 id 为空
        )

        val decoded = decodeHiddenCourseRules(corrupted)

        // 一条坏记录不该让用户其余隐藏设置一起作废。
        assertEquals(1, decoded.getValue(semester).size)
        assertEquals("数据库系统", decoded.getValue(semester).first().courseKey)
    }

    @Test
    fun decodingDropsDuplicates() {
        val entry = encodeHiddenCourseRules(
            mapOf(semester to listOf(HiddenCourseRule(HiddenCardScope.COURSE, "数据库系统")))
        ).first()

        assertEquals(1, decodeHiddenCourseRules(setOf(entry, entry)).getValue(semester).size)
    }

    @Test
    fun decodingIgnoresASemesterWithABlankId() {
        assertTrue(decodeHiddenCourseRules(setOf("${sep}course${sep}数据库系统")).isEmpty())
    }

    @Test
    fun endSectionBeforeStartSectionIsRejected() {
        assertTrue(decodeHiddenCourseRules(setOf("$semester${sep}occurrence${sep}数据库系统${sep}2${sep}6${sep}3")).isEmpty())
    }

    @Test
    fun emptyRuleListEncodesToNothing() {
        val encoded = encodeHiddenCourseRules(mapOf(semester to emptyList()))
        assertTrue(encoded.isEmpty())
        assertTrue(decodeHiddenCourseRules(encoded).isEmpty())
    }

    @Test
    fun ruleIdsAreStableAndDistinguishTheThreeScopes() {
        assertFalse(
            HiddenCourseRule(HiddenCardScope.COURSE, "数据库系统").id ==
                HiddenCourseRule(HiddenCardScope.OCCURRENCE, "数据库系统", 2, 3, 4).id
        )
        assertFalse(
            HiddenCourseRule(HiddenCardScope.WEEK, "数据库系统", 2, 3, 4, week = 5).id ==
                HiddenCourseRule(HiddenCardScope.WEEK, "数据库系统", 2, 3, 4, week = 6).id
        )
    }
}

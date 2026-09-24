package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.ScheduleRefreshDiffItem
import com.glut.schedule.data.model.buildScheduleRefreshDiff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新前后差异的比对。
 *
 * 这套口径与小程序 `pages/schedule/schedule.js:931-1001` 的 `buildRefreshDiff` **逐字对齐**，
 * 所以断言里刻意写死了文案模板——改这里就等于改两端共同的用户可见文案，
 * 必须同步改小程序那边，并由这个测试提醒。
 */
class ScheduleRefreshDiffTest {

    private fun occurrence(
        dayOfWeek: Int = 1,
        startSection: Int = 1,
        endSection: Int = 2,
        weekText: String = "1-16周",
        note: String = "04105"
    ) = CourseOccurrence(
        id = "o-$dayOfWeek-$startSection-$endSection-$weekText",
        courseId = "c1",
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        weekText = weekText,
        note = note
    )

    private fun course(
        title: String = "大学英语",
        teacher: String = "张老师",
        room: String = "04105",
        occurrences: List<CourseOccurrence> = listOf(occurrence())
    ) = ScheduleCourse(
        id = "c-$title-$teacher",
        title = title,
        room = room,
        teacher = teacher,
        colorHex = "#1265D9",
        occurrences = occurrences
    )

    @Test
    fun noChangesProduceTheShortSummary() {
        val courses = listOf(course())
        val diff = buildScheduleRefreshDiff(courses, courses)

        assertEquals("课表已刷新，无变化", diff.summary)
        assertTrue(diff.items.isEmpty())
        assertFalse(diff.hasChanges)
    }

    @Test
    fun addedAndRemovedCoursesAreReportedSeparately() {
        val old = listOf(course(title = "旧课程"))
        val new = listOf(course(title = "新课程"))

        val diff = buildScheduleRefreshDiff(old, new)

        assertEquals("新增 1 项 · 移除 1 项 · 调整 0 项", diff.summary)
        // 顺序固定：新增 → 移除 → 调整。
        assertEquals(
            listOf(ScheduleRefreshDiffItem.Kind.ADDED, ScheduleRefreshDiffItem.Kind.REMOVED),
            diff.items.map { it.kind }
        )
        assertTrue(diff.hasChanges)
    }

    @Test
    fun sameCourseWithANewTimeIsAnAdjustmentNotAddPlusRemove() {
        // 分组键是「课程名 + 教师」，不含时段——所以改时间只报「调整」，
        // 而不是「删了一门又加了一门」。
        val old = listOf(course(occurrences = listOf(occurrence(dayOfWeek = 1, startSection = 1, endSection = 2))))
        val new = listOf(course(occurrences = listOf(occurrence(dayOfWeek = 3, startSection = 5, endSection = 6))))

        val diff = buildScheduleRefreshDiff(old, new)

        assertEquals("新增 0 项 · 移除 0 项 · 调整 1 项", diff.summary)
        val item = diff.items.single()
        assertEquals(ScheduleRefreshDiffItem.Kind.CHANGED, item.kind)
        assertEquals("大学英语", item.title)
        assertEquals("周一 第1-2节 · 04105", item.beforeText)
        assertEquals("周三 第5-6节 · 04105", item.afterText)
    }

    @Test
    fun changingOnlyTheTeacherCountsAsARemovalPlusAnAddition() {
        // 教师是分组键的一部分：换老师意味着这门课的归属变了，报成两条比悄悄「调整」更诚实。
        val old = listOf(course(teacher = "张老师"))
        val new = listOf(course(teacher = "李老师"))

        val diff = buildScheduleRefreshDiff(old, new)
        assertEquals("新增 1 项 · 移除 1 项 · 调整 0 项", diff.summary)
    }

    @Test
    fun multipleSlotsAreSummarisedWithTheFirstOne() {
        val old = listOf(course(occurrences = listOf(occurrence(dayOfWeek = 1, startSection = 1, endSection = 2))))
        val new = listOf(
            course(
                occurrences = listOf(
                    occurrence(dayOfWeek = 1, startSection = 1, endSection = 2),
                    occurrence(dayOfWeek = 3, startSection = 5, endSection = 6),
                    occurrence(dayOfWeek = 5, startSection = 7, endSection = 8)
                )
            )
        )

        val diff = buildScheduleRefreshDiff(old, new)
        assertEquals("周一 第1-2节 · 04105 等 3 个时段", diff.items.single().afterText)
    }

    @Test
    fun courseWithoutAnyOccurrenceSaysSoPlainly() {
        // 实习、实践这类课在教务里只有课程没有排课时间，不能显示成空白。
        val old = listOf(course())
        val new = listOf(course(occurrences = emptyList()))

        val diff = buildScheduleRefreshDiff(old, new)
        assertEquals("无固定排课时间", diff.items.single().afterText)
    }

    @Test
    fun blankRoomFallsBackToTheCourseRoom() {
        val old = listOf(
            course(occurrences = listOf(occurrence(dayOfWeek = 2, startSection = 3, endSection = 4, note = "")))
        )
        val new = listOf(
            course(occurrences = listOf(occurrence(dayOfWeek = 2, startSection = 3, endSection = 4, note = "06104D")))
        )

        val diff = buildScheduleRefreshDiff(old, new)
        assertEquals("周二 第3-4节 · 04105", diff.items.single().beforeText)
        assertEquals("周二 第3-4节 · 06104D", diff.items.single().afterText)
    }

    @Test
    fun emptyRoomIsOmittedInsteadOfLeavingADanglingSeparator() {
        // 课次级 note 与课程级 room 都为空时，文案不该留下一个孤零零的「 · 」。
        val old = listOf(
            course(
                room = "",
                occurrences = listOf(occurrence(dayOfWeek = 2, startSection = 3, endSection = 4, note = ""))
            )
        )
        val new = listOf(
            course(
                room = "",
                occurrences = listOf(occurrence(dayOfWeek = 2, startSection = 5, endSection = 6, note = ""))
            )
        )

        val diff = buildScheduleRefreshDiff(old, new)
        assertEquals("周二 第3-4节", diff.items.single().beforeText)
        assertEquals("周二 第5-6节", diff.items.single().afterText)
    }
}

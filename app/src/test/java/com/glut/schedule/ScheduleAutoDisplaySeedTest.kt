package com.glut.schedule

import com.glut.schedule.data.model.AutomaticScheduleDisplay
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.automaticScheduleDisplaySignature
import com.glut.schedule.data.model.planAutomaticScheduleSeed
import com.glut.schedule.data.settings.CampusType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动显示「什么时候允许重新播种」的判据测试。
 *
 * 这批测试护的是同一件事：**课表内容没变时，绝不能覆盖用户在设置里的选择**。
 * 判据只能由 [com.glut.schedule.data.model.automaticScheduleDisplay] 真正读的输入
 * （校区 + 星期/节次）构成——把每次重解析都会变的字段（课次 id、颜色、weekText 写法）
 * 纳进来，等于把「同一份课表刷新了一次」误判成「内容变了」，用户刚关掉的周末列
 * 会在下一次刷新里自己打开，那正是本次要修的 bug。
 */
class ScheduleAutoDisplaySeedTest {

    @Test
    fun signatureIsIndependentOfCourseAndOccurrenceOrder() {
        val courses = listOf(
            course("A", day = 2, start = 5, end = 6),
            course("B", day = 6, start = 9, end = 10),
            course("C", day = 7, start = 3, end = 4)
        )

        // 课程顺序反转：教务两次返回同一份课表，课程先后顺序不构成内容变化。
        assertEquals(
            automaticScheduleDisplaySignature(courses, CampusType.GUILIN),
            automaticScheduleDisplaySignature(courses.reversed(), CampusType.GUILIN)
        )
    }

    @Test
    fun signatureIsIndependentOfOccurrenceOrderInsideACourse() {
        val first = CourseOccurrence("o1", "c1", 2, 1, 2, "1-16", "")
        val second = CourseOccurrence("o2", "c1", 4, 9, 10, "1-16", "")
        val forward = listOf(course("A", occurrences = listOf(first, second)))
        val backward = listOf(course("A", occurrences = listOf(second, first)))

        assertEquals(
            automaticScheduleDisplaySignature(forward, CampusType.GUILIN),
            automaticScheduleDisplaySignature(backward, CampusType.GUILIN)
        )
    }

    @Test
    fun signatureIgnoresFieldsThatChangeOnEveryReparse() {
        // 同一份课表、同样的星期与节次，只是重新解析后这些字段必然不同。
        val before = listOf(
            course(
                "高等数学", day = 6, start = 9, end = 10,
                id = "course-old", colorHex = "#111111", room = "雁山A101", weekText = "1-16"
            )
        )
        val after = listOf(
            course(
                "高等数学", day = 6, start = 9, end = 10,
                id = "course-new", colorHex = "#ABCDEF", room = "雁山D404", weekText = "1-16周"
            )
        )

        assertEquals(
            automaticScheduleDisplaySignature(before, CampusType.GUILIN),
            automaticScheduleDisplaySignature(after, CampusType.GUILIN)
        )
    }

    @Test
    fun signatureChangesWithDaysSectionsOrCampus() {
        val base = listOf(course("A", day = 6, start = 9, end = 10))
        val baseSignature = automaticScheduleDisplaySignature(base, CampusType.GUILIN)

        // 加一门周末课、节次区间变化、换校区，都必须被识别为内容变化，
        // 否则会出现「该展开却没展开」的另一种坏法。
        val extraWeekend = base + course("B", day = 7, start = 1, end = 2)
        val widerRange = listOf(course("A", day = 6, start = 9, end = 11))

        assertNotEquals(
            baseSignature,
            automaticScheduleDisplaySignature(extraWeekend, CampusType.GUILIN)
        )
        assertNotEquals(
            baseSignature,
            automaticScheduleDisplaySignature(widerRange, CampusType.GUILIN)
        )
        assertNotEquals(
            baseSignature,
            automaticScheduleDisplaySignature(base, CampusType.NANNING)
        )
    }

    @Test
    fun unchangedContentKeepsWhateverTheUserChose() {
        val courses = listOf(course("A", day = 2, start = 5, end = 6))
        val stored = automaticScheduleDisplaySignature(courses, CampusType.GUILIN)

        // 返回 null 是唯一能表达「这次什么都别写」的返回值。
        // 只要这里不是 null，用户手动关掉「显示周末 / 显示中午」后，一次普通刷新就会把它改回去。
        assertNull(planAutomaticScheduleSeed(stored, courses, CampusType.GUILIN))
    }

    @Test
    fun changedContentReseedsFromScratch() {
        val before = listOf(course("A", day = 2, start = 5, end = 6))
        val stored = automaticScheduleDisplaySignature(before, CampusType.GUILIN)
        val after = before + course("B", day = 6, start = 3, end = 4)

        val seed = planAutomaticScheduleSeed(stored, after, CampusType.GUILIN)

        assertTrue("内容真的变了就必须重新播种", seed != null)
        assertEquals(true, seed!!.display.showWeekend)
        assertEquals(
            automaticScheduleDisplaySignature(after, CampusType.GUILIN),
            seed.signature
        )
    }

    @Test
    fun firstRunWithoutAStoredSignatureAlwaysSeeds() {
        val courses = listOf(course("A", day = 6, start = 9, end = 10))

        // 老用户升级后 / 清数据后没有签名：第一次必须照常播种一次（与今天行为一致），
        // 否则「课表有周末课却不展开」。
        val seed = planAutomaticScheduleSeed(null, courses, CampusType.GUILIN)

        assertTrue(seed != null)
        assertEquals(AutomaticScheduleDisplay(showWeekend = true, showNoon = false), seed!!.display)
    }

    @Test
    fun switchingCampusReseedsNoon() {
        val courses = listOf(course("A", day = 2, start = 5, end = 6))
        val guilinSignature = automaticScheduleDisplaySignature(courses, CampusType.GUILIN)

        // 桂林第 5-6 节是中午，南宁是直排——同一份课表换校区必须重新推导。
        val seed = planAutomaticScheduleSeed(guilinSignature, courses, CampusType.NANNING)

        assertTrue(seed != null)
        assertEquals(false, seed!!.display.showNoon)
    }

    private fun course(
        title: String,
        day: Int = 1,
        start: Int = 1,
        end: Int = 1,
        id: String = "course-$title-$day-$start-$end",
        colorHex: String = "#000000",
        room: String = "",
        weekText: String = "1-16"
    ): ScheduleCourse = ScheduleCourse(
        id = id,
        title = title,
        room = room,
        teacher = "",
        colorHex = colorHex,
        occurrences = listOf(
            CourseOccurrence(
                id = "occurrence-$id",
                courseId = id,
                dayOfWeek = day,
                startSection = start,
                endSection = end,
                weekText = weekText,
                note = ""
            )
        )
    )

    private fun course(title: String, occurrences: List<CourseOccurrence>): ScheduleCourse =
        ScheduleCourse(
            id = "course-$title",
            title = title,
            room = "",
            teacher = "",
            colorHex = "#000000",
            occurrences = occurrences
        )
}

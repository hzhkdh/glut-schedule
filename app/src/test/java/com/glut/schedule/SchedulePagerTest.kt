package com.glut.schedule

import com.glut.schedule.data.model.MAX_ACADEMIC_WEEK
import com.glut.schedule.data.model.MIN_ACADEMIC_WEEK
import com.glut.schedule.data.model.CourseBlock
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.ui.pages.courseBlocksByWeek
import com.glut.schedule.ui.pages.pagerPageForWeekNumber
import com.glut.schedule.ui.pages.weekNumberForPagerPage
import com.glut.schedule.ui.components.courseCardRoomTextSize
import com.glut.schedule.ui.components.courseCardTitleTextSize
import com.glut.schedule.ui.components.overlappingCourseBlockGroups
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulePagerTest {
    @Test
    fun pagerPageMapsToOneBasedAcademicWeek() {
        assertEquals(MIN_ACADEMIC_WEEK, weekNumberForPagerPage(0))
        assertEquals(7, weekNumberForPagerPage(6))
        assertEquals(MAX_ACADEMIC_WEEK, weekNumberForPagerPage(MAX_ACADEMIC_WEEK - 1))
    }

    @Test
    fun pagerPageAndWeekNumberClampToAcademicBounds() {
        assertEquals(MIN_ACADEMIC_WEEK, weekNumberForPagerPage(-5))
        assertEquals(MAX_ACADEMIC_WEEK, weekNumberForPagerPage(MAX_ACADEMIC_WEEK + 20))

        assertEquals(0, pagerPageForWeekNumber(-3))
        assertEquals(6, pagerPageForWeekNumber(7))
        assertEquals(MAX_ACADEMIC_WEEK - 1, pagerPageForWeekNumber(MAX_ACADEMIC_WEEK + 10))
    }

    @Test
    fun courseBlocksByWeekPrecomputesAllWeeksOnce() {
        val courses = listOf(
            course(
                id = "math",
                title = "高等数学",
                occurrences = listOf(
                    occurrence(id = "math-1", courseId = "math", weekText = "1-2周"),
                    occurrence(id = "math-3", courseId = "math", weekText = "3周")
                )
            ),
            course(
                id = "physics",
                title = "大学物理",
                occurrences = listOf(
                    occurrence(id = "physics-even", courseId = "physics", weekText = "2-4周 双周")
                )
            )
        )

        val blocksByWeek = courseBlocksByWeek(courses)

        assertEquals(1, blocksByWeek.getValue(1).size)
        assertEquals(2, blocksByWeek.getValue(2).size)
        assertEquals(1, blocksByWeek.getValue(3).size)
        assertEquals(1, blocksByWeek.getValue(4).size)
        assertEquals(0, blocksByWeek.getValue(5).size)
        assertEquals(MAX_ACADEMIC_WEEK, blocksByWeek.size)
    }

    @Test
    fun courseCardUsesConsistentTitleSizeAndLargerRoomText() {
        assertEquals(courseCardTitleTextSize("机器学习"), courseCardTitleTextSize("习近平新时代中国特色社会主义思想概论"))
        assertEquals(11f, courseCardTitleTextSize("机器学习").value)
        assertEquals(10f, courseCardRoomTextSize().value)
    }

    @Test
    fun overlappingCourseBlocksAreGroupedByDayAndSectionRange() {
        val java = block(id = "java", title = "Java", day = 2, start = 9, end = 10)
        val dataStructure = block(id = "data", title = "数据结构", day = 2, start = 9, end = 12)
        val machineLearning = block(id = "ml", title = "机器学习", day = 2, start = 7, end = 8)
        val english = block(id = "english", title = "大学英语", day = 3, start = 9, end = 10)

        val groups = overlappingCourseBlockGroups(
            listOf(dataStructure, english, java, machineLearning)
        )

        assertEquals(3, groups.size)
        assertEquals(listOf("ml"), groups[0].map { it.course.id })
        assertEquals(listOf("java", "data"), groups[1].map { it.course.id })
        assertEquals(listOf("english"), groups[2].map { it.course.id })
    }

    private fun course(
        id: String,
        title: String,
        occurrences: List<CourseOccurrence>
    ): ScheduleCourse {
        return ScheduleCourse(
            id = id,
            title = title,
            room = "06408D",
            teacher = "待确认",
            colorHex = "#3B82F6",
            occurrences = occurrences
        )
    }

    private fun occurrence(id: String, courseId: String, weekText: String): CourseOccurrence {
        return CourseOccurrence(
            id = id,
            courseId = courseId,
            dayOfWeek = 1,
            startSection = 1,
            endSection = 2,
            weekText = weekText,
            note = ""
        )
    }

    private fun block(id: String, title: String, day: Int, start: Int, end: Int): CourseBlock {
        val occurrence = CourseOccurrence(
            id = "$id-occurrence",
            courseId = id,
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            weekText = "1-22周",
            note = ""
        )
        return CourseBlock(
            course = ScheduleCourse(
                id = id,
                title = title,
                room = "06408D",
                teacher = "待确认",
                colorHex = "#3B82F6",
                occurrences = listOf(occurrence)
            ),
            occurrence = occurrence
        )
    }
}

package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseColorMapperTest {
    @Test
    fun sameCourseTitleKeepsSameColorAcrossDifferentCourseIds() {
        val courses = CourseColorMapper.assignColors(
            listOf(
                course(id = "course-a", title = "机器学习", day = 2, start = 3),
                course(id = "course-b", title = "机器学习", day = 2, start = 5)
            )
        )

        assertEquals(courses[0].colorHex, courses[1].colorHex)
    }

    @Test
    fun differentCoursesUseAHighVarietyPalette() {
        val titles = listOf(
            "数字逻辑",
            "嵌入式系统",
            "大学英语4",
            "机器学习",
            "习近平新时代中国特色社会主义思想概论",
            "操作系统B",
            "算法设计与分析",
            "面向对象程序设计Java"
        )

        val colors = CourseColorMapper.assignColors(
            titles.mapIndexed { index, title ->
                course(id = "course-$index", title = title, day = (index % 5) + 1, start = 1 + (index % 4) * 2)
            }
        ).map { it.colorHex }

        assertTrue(colors.distinct().size >= 7)
        assertTrue(colors.all { it in CourseColorMapper.palette })
    }

    @Test
    fun adjacentCoursesDoNotKeepTheSameColorWhenHashCollides() {
        val collision = findPaletteCollision()

        val courses = CourseColorMapper.assignColors(
            listOf(
                course(id = "a", title = collision.first, day = 3, start = 1),
                course(id = "b", title = collision.second, day = 3, start = 3)
            )
        )

        assertNotEquals(courses[0].colorHex, courses[1].colorHex)
    }

    private fun findPaletteCollision(): Pair<String, String> {
        val seen = mutableMapOf<String, String>()
        for (index in 0..300) {
            val title = "测试课程$index"
            val color = CourseColorMapper.colorForCourse(courseId = "id-$index", title = title)
            val previous = seen[color]
            if (previous != null) return previous to title
            seen[color] = title
        }
        error("Expected to find a palette collision")
    }

    private fun course(id: String, title: String, day: Int, start: Int): ScheduleCourse {
        return ScheduleCourse(
            id = id,
            title = title,
            room = "06408D",
            teacher = "待确认",
            colorHex = "#000000",
            occurrences = listOf(
                CourseOccurrence(
                    id = "$id-occ",
                    courseId = id,
                    dayOfWeek = day,
                    startSection = start,
                    endSection = start + 1,
                    weekText = "1-16周",
                    note = "06408D"
                )
            )
        )
    }
}

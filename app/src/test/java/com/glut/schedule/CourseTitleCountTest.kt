package com.glut.schedule

import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.countDistinctCourseTitles
import org.junit.Assert.assertEquals
import org.junit.Test

class CourseTitleCountTest {
    @Test
    fun countsOnlyNormalizedCourseTitlesAcrossRoomsAndTeachers() {
        val courses = listOf(
            course("数字逻辑", "06408D", "教师甲"),
            course(" 数字逻辑 ", "05207D", "教师乙"),
            course("面向对象  程序设计", "04525D", "教师丙"),
            course("面向对象 程序设计", "04109D", "教师丙"),
            course("高等数学 @06404D", "06404D", "教师丁"),
            course("高等数学 @08991A", "08991A", "教师戊"),
            course("   ", "待定", "待确认")
        )

        assertEquals(3, courses.countDistinctCourseTitles())
    }

    private fun course(title: String, room: String, teacher: String) = ScheduleCourse(
        id = "$title-$room-$teacher",
        title = title,
        room = room,
        teacher = teacher,
        colorHex = "#4477AA",
        occurrences = emptyList()
    )
}

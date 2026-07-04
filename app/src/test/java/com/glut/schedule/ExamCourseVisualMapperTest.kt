package com.glut.schedule

import com.glut.schedule.ui.pages.ExamCourseVisualCategory
import com.glut.schedule.ui.pages.ExamCourseVisualMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ExamCourseVisualMapperTest {
    @Test
    fun physicsCoursesUsePhysicsCategoryInsteadOfLabScience() {
        val visual = ExamCourseVisualMapper.visualFor("大学物理（二）2")

        assertEquals(ExamCourseVisualCategory.Physics, visual.category)
        assertNotEquals(ExamCourseVisualCategory.LabScience, visual.category)
    }

    @Test
    fun politicsCoursesUsePublicAffairsCategory() {
        val visual = ExamCourseVisualMapper.visualFor("马克思主义基本原理")

        assertEquals(ExamCourseVisualCategory.PublicAffairs, visual.category)
    }

    @Test
    fun mathCoursesUseMathCategory() {
        val visual = ExamCourseVisualMapper.visualFor("高等数学A")

        assertEquals(ExamCourseVisualCategory.Math, visual.category)
    }

    @Test
    fun programmingCoursesUseCodeCategory() {
        val visual = ExamCourseVisualMapper.visualFor("Java 程序设计")

        assertEquals(ExamCourseVisualCategory.Code, visual.category)
    }

    @Test
    fun chemistryLabCoursesUseLabScienceCategory() {
        val visual = ExamCourseVisualMapper.visualFor("分析化学实验")

        assertEquals(ExamCourseVisualCategory.LabScience, visual.category)
    }
}

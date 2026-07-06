package com.glut.schedule

import com.glut.schedule.data.model.CourseStatus
import com.glut.schedule.data.model.ProfessionalScoreCalculator
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfessionalScoreCalculatorTest {
    @Test
    fun calculatesWeightedAverageForRequiredAndLimitedCoursesInSelectedSemester() {
        val groups = listOf(
            groupWithCourses("专业核心课", "必修", "高等数学（二）" to 4.0, "程序设计基础" to 3.0),
            groupWithCourses("专业方向限选", "限选", "人工智能导论" to 2.0),
            groupWithCourses("公共任选", "任选", "音乐鉴赏" to 2.0)
        )
        val scores = listOf(
            score("高等数学(二)", "90"),
            score("程序设计基础", "80"),
            score("人工智能导论", "70"),
            score("音乐鉴赏", "100")
        )

        val result = ProfessionalScoreCalculator.calculate("2025春", groups, scores)

        assertEquals(3, result.courses.size)
        assertEquals(9.0, result.totalCredit, 0.01)
        assertEquals((90 * 4 + 80 * 3 + 70 * 2) / 9.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun excludesMinorGroupsAndReportsMissingScores() {
        val groups = listOf(
            groupWithCourses("专业核心课", "必修", "数据库系统" to 3.0),
            groupWithCourses("辅修专业课", "必修", "辅修课程" to 2.0)
        )

        val result = ProfessionalScoreCalculator.calculate("2025春", groups, emptyList())

        assertEquals(0, result.courses.size)
        assertEquals(1, result.missingCourses.size)
        assertEquals("数据库系统", result.missingCourses.single().courseName)
        assertNull(result.professionalScore)
    }

    @Test
    fun usesHighestNumericScoreWhenCourseHasMultipleScores() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", "线性代数" to 2.0))
        val scores = listOf(score("线性代数", "58"), score("线性代数", "86"))

        val result = ProfessionalScoreCalculator.calculate("2025春", groups, scores)

        assertEquals(1, result.courses.size)
        assertEquals(86.0, result.courses.single().scoreValue, 0.01)
        assertEquals(86.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun usesScoreFromSelectedSemesterBeforeFallingBackToHighestSameNameScore() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", "线性代数" to 2.0))
        val scores = listOf(
            score("线性代数", "99", year = "2024", term = 2),
            score("线性代数", "76", year = "2025", term = 1)
        )

        val result = ProfessionalScoreCalculator.calculate("2025春", groups, scores)

        assertEquals(1, result.courses.size)
        assertEquals(76.0, result.courses.single().scoreValue, 0.01)
        assertEquals(76.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun convertsQualitativeScoresToPercentScores() {
        assertEquals(95.0, ProfessionalScoreCalculator.parsePercentScore("优秀")!!, 0.01)
        assertEquals(85.0, ProfessionalScoreCalculator.parsePercentScore("良好")!!, 0.01)
        assertEquals(75.0, ProfessionalScoreCalculator.parsePercentScore("合格")!!, 0.01)
        assertEquals(65.0, ProfessionalScoreCalculator.parsePercentScore("及格")!!, 0.01)
        assertEquals(0.0, ProfessionalScoreCalculator.parsePercentScore("不及格")!!, 0.01)
        assertEquals(0.0, ProfessionalScoreCalculator.parsePercentScore("不合格")!!, 0.01)
        assertNull(ProfessionalScoreCalculator.parsePercentScore("424.0"))
        assertEquals(89.5, ProfessionalScoreCalculator.parsePercentScore("89.5")!!, 0.01)
    }

    @Test
    fun exposesCourseDetailsUsedByTheResult() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", "数据结构" to 3.0))
        val scores = listOf(score("数据结构", "良好"))

        val result = ProfessionalScoreCalculator.calculate("2025春", groups, scores)

        val course = result.courses.single()
        assertEquals("数据结构", course.courseName)
        assertEquals("专业核心课", course.groupName)
        assertEquals("必修", course.attribute)
        assertEquals("良好", course.scoreText)
        assertEquals(85.0, course.scoreValue, 0.01)
        assertEquals(3.0, course.credit, 0.01)
    }

    private fun groupWithCourses(
        groupName: String,
        attribute: String,
        vararg courses: Pair<String, Double>
    ): StudyPlanGroupWithCourses {
        val group = StudyPlanGroup(
            id = StudyPlanGroup.stableId(groupName, attribute),
            groupName = groupName,
            attribute = attribute,
            creditRequired = courses.sumOf { it.second },
            creditEarned = 0.0,
            countRequired = courses.size,
            countPassed = 0,
            isPassed = false
        )
        return StudyPlanGroupWithCourses(
            group = group,
            courses = courses.map { (courseName, credit) ->
                StudyPlanCourse(
                    id = StudyPlanCourse.stableId(group.id, courseName),
                    groupId = group.id,
                    courseName = courseName,
                    credit = credit,
                    hours = "",
                    assessment = "考试",
                    semester = "2025春",
                    status = CourseStatus.UNKNOWN
                )
            }
        )
    }

    private fun score(
        courseName: String,
        scoreText: String,
        year: String = "2025",
        term: Int = 1
    ): ScoreInfo {
        return ScoreInfo(
            id = ScoreInfo.stableId(courseName, year, term),
            courseName = courseName,
            score = scoreText,
            gpa = 0.0,
            credit = 0.0,
            year = year,
            term = term,
            category = "必修",
            examType = "正常考试"
        )
    }
}

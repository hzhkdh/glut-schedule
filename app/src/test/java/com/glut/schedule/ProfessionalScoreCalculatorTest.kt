package com.glut.schedule

import com.glut.schedule.data.model.CourseStatus
import com.glut.schedule.data.model.ProfessionalScoreCalculator
import com.glut.schedule.data.model.ProfessionalScoreManualCourse
import com.glut.schedule.data.model.ProfessionalScoreOptions
import com.glut.schedule.data.model.ProfessionalScoreOverrides
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanGroupWithCourses
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class ProfessionalScoreCalculatorTest {
    @Test
    fun defaultAcademicYearUsesSavedCurrentSpringSemester() {
        val selected = ProfessionalScoreCalculator.resolveDefaultAcademicYear(
            availableAcademicYears = listOf("2025学年", "2026学年"),
            semesterStartDate = LocalDate.of(2026, 3, 9),
            semesterEndDate = LocalDate.of(2026, 7, 19),
            today = LocalDate.of(2026, 7, 17)
        )

        assertEquals("2025学年", selected)
    }

    @Test
    fun defaultAcademicYearUsesSavedCurrentAutumnSemester() {
        val selected = ProfessionalScoreCalculator.resolveDefaultAcademicYear(
            availableAcademicYears = listOf("2025学年", "2026学年"),
            semesterStartDate = LocalDate.of(2026, 9, 7),
            semesterEndDate = LocalDate.of(2027, 1, 17),
            today = LocalDate.of(2026, 10, 1)
        )

        assertEquals("2026学年", selected)
    }

    @Test
    fun defaultAcademicYearFallsBackToTodayWhenSavedSemesterIsStale() {
        val selected = ProfessionalScoreCalculator.resolveDefaultAcademicYear(
            availableAcademicYears = listOf("2024学年", "2025学年", "2026学年"),
            semesterStartDate = LocalDate.of(2025, 9, 1),
            semesterEndDate = LocalDate.of(2026, 1, 18),
            today = LocalDate.of(2026, 9, 10)
        )

        assertEquals("2026学年", selected)
    }

    @Test
    fun defaultAcademicYearFallsBackToLatestAvailableYearWhenCurrentIsMissing() {
        val selected = ProfessionalScoreCalculator.resolveDefaultAcademicYear(
            availableAcademicYears = listOf("2023学年", "2024学年"),
            semesterStartDate = null,
            semesterEndDate = null,
            today = LocalDate.of(2026, 7, 17)
        )

        assertEquals("2024学年", selected)
    }

    @Test
    fun availableAcademicYearsAreDerivedFromStudyPlanAutumnAndNextSpring() {
        val groups = listOf(
            groupWithCourses(
                groupName = "专业核心课",
                attribute = "必修",
                courses = arrayOf(
                    course("2025春", "离散数学" to 3.0),
                    course("2025秋", "数据结构" to 3.0),
                    course("2026春", "操作系统" to 3.0),
                    course("2026秋", "编译原理" to 3.0)
                )
            )
        )

        val years = ProfessionalScoreCalculator.availableAcademicYears(groups)

        assertEquals(listOf("2024学年", "2025学年", "2026学年"), years)
    }

    @Test
    fun calculatesWeightedAverageForWholeAcademicYearWithoutChangingFormula() {
        val groups = listOf(
            groupWithCourses(
                groupName = "专业核心课",
                attribute = "必修",
                courses = arrayOf(
                    course("2025秋", "高等数学（二）" to 4.0),
                    course("2026春", "程序设计基础" to 3.0)
                )
            )
        )
        val scores = listOf(
            score("高等数学(二)", "90", year = "2025", term = 2),
            score("程序设计基础", "80", year = "2026", term = 1)
        )

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(listOf("2025秋", "2026春"), result.includedSemesters)
        assertEquals(2, result.courses.size)
        assertEquals(7.0, result.totalCredit, 0.01)
        assertEquals((90 * 4 + 80 * 3) / 7.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun normalScoreDoesNotExposeRedundantSourceReason() {
        val groups = listOf(
            groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "程序设计实践" to 3.0)))
        )
        val scores = listOf(score("程序设计实践", "良", year = "2025", term = 2))

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals("良", result.courses.single().scoreText)
        assertEquals("", result.courses.single().scoreSourceReason)
    }

    @Test
    fun excludesCoursesThatDoNotParticipateInProfessionalScore() {
        val groups = listOf(
            groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "数据库系统" to 3.0))),
            groupWithCourses("专业方向限选", "限选", arrayOf(course("2025秋", "人工智能导论" to 2.0))),
            groupWithCourses("公共任选", "任选", arrayOf(course("2025秋", "音乐鉴赏" to 2.0))),
            groupWithCourses("体育课组", "必修", arrayOf(course("2025秋", "大学体育" to 1.0))),
            groupWithCourses("补修课程", "必修", arrayOf(course("2025秋", "补修数学" to 2.0))),
            groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "重修英语" to 2.0))),
            groupWithCourses("辅修专业课", "必修", arrayOf(course("2025秋", "辅修课程" to 2.0))),
            groupWithCourses("双学位课程", "必修", arrayOf(course("2025秋", "双学位课程" to 2.0))),
            groupWithCourses("第二专业课程", "必修", arrayOf(course("2025秋", "第二专业课程" to 2.0)))
        )
        val scores = listOf(
            score("数据库系统", "90", year = "2025", term = 2),
            score("人工智能导论", "80", year = "2025", term = 2),
            score("音乐鉴赏", "100", year = "2025", term = 2),
            score("大学体育", "100", year = "2025", term = 2),
            score("补修数学", "100", year = "2025", term = 2),
            score("重修英语", "100", year = "2025", term = 2),
            score("辅修课程", "100", year = "2025", term = 2),
            score("双学位课程", "100", year = "2025", term = 2),
            score("第二专业课程", "100", year = "2025", term = 2)
        )

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(listOf("人工智能导论", "数据库系统"), result.courses.map { it.courseName })
        assertEquals(5.0, result.totalCredit, 0.01)
        assertEquals((90 * 3 + 80 * 2) / 5.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun convertsQualitativeScoresWithRequiredFiveLevelMapping() {
        assertEquals(95.0, ProfessionalScoreCalculator.parsePercentScore("优")!!, 0.01)
        assertEquals(95.0, ProfessionalScoreCalculator.parsePercentScore("优秀")!!, 0.01)
        assertEquals(85.0, ProfessionalScoreCalculator.parsePercentScore("良")!!, 0.01)
        assertEquals(85.0, ProfessionalScoreCalculator.parsePercentScore("良好")!!, 0.01)
        assertEquals(75.0, ProfessionalScoreCalculator.parsePercentScore("中")!!, 0.01)
        assertEquals(75.0, ProfessionalScoreCalculator.parsePercentScore("中等")!!, 0.01)
        assertEquals(65.0, ProfessionalScoreCalculator.parsePercentScore("及格")!!, 0.01)
        assertEquals(40.0, ProfessionalScoreCalculator.parsePercentScore("不及格")!!, 0.01)
        assertEquals(40.0, ProfessionalScoreCalculator.parsePercentScore("不合格")!!, 0.01)
        assertEquals(40.0, ProfessionalScoreCalculator.parsePercentScore("未通过")!!, 0.01)
        assertEquals(89.5, ProfessionalScoreCalculator.parsePercentScore("89.5")!!, 0.01)
        assertNull(ProfessionalScoreCalculator.parsePercentScore("424.0"))
    }

    @Test
    fun countsCheatingAndAbsenceAsZeroInsteadOfMissing() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "线性代数" to 2.0))))
        val scores = listOf(score("线性代数", "作弊", year = "2025", term = 2))

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(1, result.courses.size)
        assertEquals(0.0, result.courses.single().scoreValue, 0.01)
        assertEquals(0.0, result.professionalScore!!, 0.01)
        assertTrue(result.courses.single().scoreSourceReason.contains("0"))
    }

    @Test
    fun passedMakeupExamCountsAsSixty() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "线性代数" to 2.0))))
        val scores = listOf(
            score("线性代数", "55", year = "2025", term = 2),
            score("线性代数", "70", year = "2025", term = 2, examType = "补考通过")
        )

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(60.0, result.courses.single().scoreValue, 0.01)
        assertEquals(60.0, result.professionalScore!!, 0.01)
        assertEquals("补考通过按 60 分计", result.courses.single().scoreSourceReason)
    }

    @Test
    fun failedMakeupExamCountsOriginalScore() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "线性代数" to 2.0))))
        val scores = listOf(
            score("线性代数", "45", year = "2025", term = 2),
            score("线性代数", "50", year = "2025", term = 2, examType = "补考未通过")
        )

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(45.0, result.courses.single().scoreValue, 0.01)
        assertEquals(45.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun deferredExamCountsExamAfterDeferred() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", arrayOf(course("2026春", "数据结构" to 3.0))))
        val scores = listOf(score("数据结构", "82", year = "2026", term = 1, examType = "缓考"))

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(82.0, result.courses.single().scoreValue, 0.01)
        assertEquals(82.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun doesNotUseHigherScoreFromAnotherAcademicYear() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "线性代数" to 2.0))))
        val scores = listOf(
            score("线性代数", "60", year = "2025", term = 2),
            score("线性代数", "95", year = "2026", term = 2, examType = "重修")
        )

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(60.0, result.courses.single().scoreValue, 0.01)
        assertEquals(60.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun sameNamedCoursesInAutumnAndSpringAreBothIncluded() {
        val groups = listOf(
            groupWithCourses(
                "思想政治课",
                "必修",
                arrayOf(
                    course("2025秋", "形势与政策" to 1.0),
                    course("2026春", "形势与政策" to 1.0)
                )
            )
        )
        val scores = listOf(
            score("形势与政策", "80", year = "2025", term = 2),
            score("形势与政策", "90", year = "2026", term = 1)
        )

        val result = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)

        assertEquals(2, result.courses.size)
        assertEquals(listOf("2025秋", "2026春"), result.courses.map { it.semester })
        assertEquals(2.0, result.totalCredit, 0.01)
        assertEquals(85.0, result.professionalScore!!, 0.01)
    }

    @Test
    fun emptyOverridesDoNotChangeResultAndManualIncludeCanExtendBeforeAggregation() {
        val groups = listOf(groupWithCourses("专业核心课", "必修", arrayOf(course("2025秋", "数据库系统" to 3.0))))
        val scores = listOf(score("数据库系统", "90", year = "2025", term = 2))

        val base = ProfessionalScoreCalculator.calculate("2025学年", groups, scores)
        val emptyOverride = ProfessionalScoreCalculator.calculate(
            "2025学年",
            groups,
            scores,
            ProfessionalScoreOptions(ProfessionalScoreOverrides.empty)
        )
        val withManual = ProfessionalScoreCalculator.calculate(
            "2025学年",
            groups,
            scores,
            ProfessionalScoreOptions(
                ProfessionalScoreOverrides(
                    includedManualCourses = listOf(
                        ProfessionalScoreManualCourse(
                            id = "manual-1",
                            courseName = "手动课程",
                            semester = "2026春",
                            scoreText = "80",
                            credit = 1.0
                        )
                    )
                )
            )
        )

        assertEquals(base.professionalScore!!, emptyOverride.professionalScore!!, 0.01)
        assertEquals(2, withManual.courses.size)
        assertEquals((90 * 3 + 80 * 1) / 4.0, withManual.professionalScore!!, 0.01)
    }

    private fun groupWithCourses(
        groupName: String,
        attribute: String,
        courses: Array<StudyPlanCourse>
    ): StudyPlanGroupWithCourses {
        val group = StudyPlanGroup(
            id = StudyPlanGroup.stableId(groupName, attribute),
            groupName = groupName,
            attribute = attribute,
            creditRequired = courses.sumOf { it.credit },
            creditEarned = 0.0,
            countRequired = courses.size,
            countPassed = 0,
            isPassed = false
        )
        return StudyPlanGroupWithCourses(
            group = group,
            courses = courses.map { it.copy(groupId = group.id) }
        )
    }

    private fun course(
        semester: String,
        course: Pair<String, Double>,
        status: CourseStatus = CourseStatus.UNKNOWN
    ): StudyPlanCourse {
        return StudyPlanCourse(
            id = StudyPlanCourse.stableId("group", course.first),
            groupId = "group",
            courseName = course.first,
            credit = course.second,
            hours = "",
            assessment = "考试",
            semester = semester,
            status = status
        )
    }

    private fun score(
        courseName: String,
        scoreText: String,
        year: String = "2025",
        term: Int = 1,
        category: String = "必修",
        examType: String = "正常考试"
    ): ScoreInfo {
        return ScoreInfo(
            id = ScoreInfo.stableId(courseName, year, term),
            courseName = courseName,
            score = scoreText,
            gpa = 0.0,
            credit = 0.0,
            year = year,
            term = term,
            category = category,
            examType = examType
        )
    }
}

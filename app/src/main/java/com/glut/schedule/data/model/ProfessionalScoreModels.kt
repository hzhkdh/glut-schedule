package com.glut.schedule.data.model

import java.text.Collator
import java.util.Locale

data class ProfessionalScoreCourse(
    val courseName: String,
    val groupName: String,
    val semester: String,
    val attribute: String,
    val scoreText: String,
    val scoreValue: Double,
    val credit: Double
) {
    val weightedScore: Double = scoreValue * credit
}

data class ProfessionalScoreMissingCourse(
    val courseName: String,
    val groupName: String,
    val semester: String,
    val attribute: String,
    val credit: Double,
    val reason: String
)

data class ProfessionalScoreResult(
    val semester: String,
    val courses: List<ProfessionalScoreCourse>,
    val missingCourses: List<ProfessionalScoreMissingCourse>
) {
    val totalCredit: Double = courses.sumOf { it.credit }
    val totalWeightedScore: Double = courses.sumOf { it.weightedScore }
    val professionalScore: Double? =
        if (totalCredit > 0.0) totalWeightedScore / totalCredit else null
}

object ProfessionalScoreCalculator {
    private val chineseCollator: Collator = Collator.getInstance(Locale.CHINA)

    fun availableSemesters(groupsWithCourses: List<StudyPlanGroupWithCourses>): List<String> {
        return groupsWithCourses
            .flatMap { groupWithCourses ->
                groupWithCourses.courses
                    .filter { isIncludedAttribute(groupWithCourses.group.attribute, groupWithCourses.group.groupName) }
                    .map { it.semester.trim() }
            }
            .filter { it.isNotBlank() }
            .distinct()
            .sortedWith(compareBy(::semesterSortKey))
    }

    fun calculate(
        semester: String,
        groupsWithCourses: List<StudyPlanGroupWithCourses>,
        scores: List<ScoreInfo>
    ): ProfessionalScoreResult {
        val scoreByCourseName = scores
            .mapNotNull { score ->
                val value = parsePercentScore(score.score) ?: return@mapNotNull null
                normalizeCourseName(score.courseName) to (score to value)
            }
            .groupBy({ it.first }, { it.second })

        val eligibleCourses = groupsWithCourses
            .flatMap { groupWithCourses ->
                val group = groupWithCourses.group
                if (!isIncludedAttribute(group.attribute, group.groupName)) return@flatMap emptyList()
                groupWithCourses.courses
                    .filter { sameSemester(it.semester, semester) }
                    .map { course -> EligibleCourse(group.groupName, group.attribute, course) }
            }
            .distinctBy { normalizeCourseName(it.course.courseName) }
            .sortedWith { left, right ->
                chineseCollator.compare(left.course.courseName, right.course.courseName)
            }

        val calculatedCourses = mutableListOf<ProfessionalScoreCourse>()
        val missingCourses = mutableListOf<ProfessionalScoreMissingCourse>()

        eligibleCourses.forEach { eligible ->
            val course = eligible.course
            val scoreCandidates = scoreByCourseName[normalizeCourseName(course.courseName)].orEmpty()
            val matchedScore = scoreCandidates
                .filter { (scoreInfo, _) -> sameSemester(scoreInfo.year, scoreInfo.term, semester) }
                .ifEmpty { scoreCandidates }
                .maxByOrNull { it.second }
            if (matchedScore == null) {
                missingCourses.add(
                    ProfessionalScoreMissingCourse(
                        courseName = course.courseName,
                        groupName = eligible.groupName,
                        semester = course.semester,
                        attribute = eligible.attribute,
                        credit = course.credit,
                        reason = "未找到可用于换算的成绩"
                    )
                )
            } else {
                val (scoreInfo, scoreValue) = matchedScore
                calculatedCourses.add(
                    ProfessionalScoreCourse(
                        courseName = course.courseName,
                        groupName = eligible.groupName,
                        semester = course.semester,
                        attribute = eligible.attribute,
                        scoreText = scoreInfo.score,
                        scoreValue = scoreValue,
                        credit = course.credit
                    )
                )
            }
        }

        return ProfessionalScoreResult(
            semester = semester,
            courses = calculatedCourses,
            missingCourses = missingCourses
        )
    }

    fun parsePercentScore(scoreText: String): Double? {
        val numeric = Regex("""(?<!\d)\d{1,3}(?:\.\d+)?(?!\d)""")
            .find(scoreText)
            ?.value
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }
        if (numeric != null) return numeric

        val normalized = scoreText.trim().replace(Regex("""\s+"""), "")
        val qualitativeScore = qualitativeScoreMap.firstOrNull { (keyword, _) ->
            normalized.contains(keyword, ignoreCase = true)
        }?.second
        if (qualitativeScore != null) return qualitativeScore

        return letterGradeScoreMap[normalized.uppercase()]
    }

    fun semesterSortKey(semester: String): Int {
        val year = Regex("""\d{4}""").find(semester)?.value?.toIntOrNull() ?: 0
        val termOrder = when {
            semester.contains("春") -> 1
            semester.contains("夏") -> 2
            semester.contains("秋") -> 3
            semester.contains("冬") -> 4
            semester.contains("1") && !semester.contains("2") -> 1
            semester.contains("2") || semester.contains("3") -> 3
            else -> 0
        }
        return year * 10 + termOrder
    }

    private fun isIncludedAttribute(attribute: String, groupName: String): Boolean {
        val isRequiredOrLimited = attribute.contains("必修") || attribute.contains("限选")
        val isMinor = listOf("辅修", "双学位", "第二专业").any { keyword ->
            attribute.contains(keyword) || groupName.contains(keyword)
        }
        return isRequiredOrLimited && !isMinor
    }

    private fun sameSemester(left: String, right: String): Boolean {
        return left.trim() == right.trim()
    }

    private fun sameSemester(year: String, term: Int, semester: String): Boolean {
        val semesterYear = Regex("""\d{4}""").find(semester)?.value ?: return false
        if (!year.contains(semesterYear)) return false
        val semesterTerm = when {
            semester.contains("春") -> 1
            semester.contains("秋") -> 2
            else -> return false
        }
        return term == semesterTerm
    }

    private fun normalizeCourseName(name: String): String {
        return name
            .replace('（', '(')
            .replace('）', ')')
            .replace(Regex("""\s+"""), "")
            .replace(Regex("""[：:；;，,。.]"""), "")
            .trim()
    }

    private data class EligibleCourse(
        val groupName: String,
        val attribute: String,
        val course: StudyPlanCourse
    )

    private val qualitativeScoreMap = listOf(
        "不合格" to 0.0,
        "不及格" to 0.0,
        "未通过" to 0.0,
        "不通过" to 0.0,
        "优秀" to 95.0,
        "优" to 95.0,
        "良好" to 85.0,
        "良" to 85.0,
        "中等" to 75.0,
        "中" to 75.0,
        "合格" to 75.0,
        "通过" to 75.0,
        "及格" to 65.0
    )

    private val letterGradeScoreMap = mapOf(
        "A" to 95.0,
        "B" to 85.0,
        "C" to 75.0,
        "D" to 65.0,
        "F" to 0.0
    )
}

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
    val credit: Double,
    val scoreSourceReason: String = ""
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

data class ProfessionalScoreManualCourse(
    val id: String,
    val courseName: String,
    val groupName: String = "手动添加",
    val semester: String,
    val attribute: String = "必修",
    val scoreText: String,
    val credit: Double
)

data class ProfessionalScoreOverrides(
    val excludedCourseIds: Set<String> = emptySet(),
    val includedManualCourses: List<ProfessionalScoreManualCourse> = emptyList()
) {
    companion object {
        val empty = ProfessionalScoreOverrides()
    }
}

data class ProfessionalScoreOptions(
    val overrides: ProfessionalScoreOverrides = ProfessionalScoreOverrides.empty
)

data class ProfessionalScoreResult(
    val academicYear: String,
    val includedSemesters: List<String>,
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

    fun availableAcademicYears(groupsWithCourses: List<StudyPlanGroupWithCourses>): List<String> {
        return groupsWithCourses
            .flatMap { groupWithCourses ->
                groupWithCourses.courses
                    .filter { isEligiblePlanCourse(groupWithCourses.group, it) }
                    .mapNotNull { academicYearOfSemester(it.semester) }
            }
            .distinct()
            .sortedBy { academicYearStart(it) }
    }

    fun calculate(
        academicYear: String,
        groupsWithCourses: List<StudyPlanGroupWithCourses>,
        scores: List<ScoreInfo>,
        options: ProfessionalScoreOptions = ProfessionalScoreOptions()
    ): ProfessionalScoreResult {
        val scoreByCourseName = scores
            .map { normalizeCourseName(it.courseName) to it }
            .groupBy({ it.first }, { it.second })

        val eligibleCourses = groupsWithCourses
            .flatMap { groupWithCourses ->
                val group = groupWithCourses.group
                groupWithCourses.courses
                    .filter { course -> isEligiblePlanCourse(group, course) }
                    .filter { course -> academicYearOfSemester(course.semester) == academicYear }
                    .filterNot { course -> course.id in options.overrides.excludedCourseIds }
                    .map { course -> EligibleCourse(group.groupName, group.attribute, course) }
            }
            .distinctBy {
                normalizeCourseName(it.course.courseName) to it.course.semester.trim()
            }
            .sortedWith { left, right ->
                chineseCollator.compare(left.course.courseName, right.course.courseName)
            }

        val calculatedCourses = mutableListOf<ProfessionalScoreCourse>()
        val missingCourses = mutableListOf<ProfessionalScoreMissingCourse>()

        eligibleCourses.forEach { eligible ->
            val course = eligible.course
            val scoreCandidates = scoreByCourseName[normalizeCourseName(course.courseName)].orEmpty()
                .filter { belongsToCourseSemester(it, course.semester) }
            val resolvedScore = resolveScore(scoreCandidates)
            if (resolvedScore == null) {
                missingCourses.add(
                    ProfessionalScoreMissingCourse(
                        courseName = course.courseName,
                        groupName = eligible.groupName,
                        semester = course.semester,
                        attribute = eligible.attribute,
                        credit = course.credit,
                        reason = "未找到该学年可用于换算的成绩"
                    )
                )
            } else {
                calculatedCourses.add(
                    ProfessionalScoreCourse(
                        courseName = course.courseName,
                        groupName = eligible.groupName,
                        semester = course.semester,
                        attribute = eligible.attribute,
                        scoreText = resolvedScore.score.score,
                        scoreValue = resolvedScore.value,
                        credit = course.credit,
                        scoreSourceReason = resolvedScore.reason
                    )
                )
            }
        }

        options.overrides.includedManualCourses.forEach { manual ->
            if (academicYearOfSemester(manual.semester) != academicYear) return@forEach
            val scoreValue = parsePercentScore(manual.scoreText) ?: return@forEach
            calculatedCourses.add(
                ProfessionalScoreCourse(
                    courseName = manual.courseName,
                    groupName = manual.groupName,
                    semester = manual.semester,
                    attribute = manual.attribute,
                    scoreText = manual.scoreText,
                    scoreValue = scoreValue,
                    credit = manual.credit,
                    scoreSourceReason = "手动添加"
                )
            )
        }

        return ProfessionalScoreResult(
            academicYear = academicYear,
            includedSemesters = semestersOfAcademicYear(academicYear),
            courses = calculatedCourses,
            missingCourses = missingCourses
        )
    }

    fun parsePercentScore(scoreText: String): Double? {
        val normalized = normalizeScoreText(scoreText)
        if (zeroScoreKeywords.any { normalized.contains(it) }) return 0.0

        val qualitativeScore = qualitativeScoreMap.firstOrNull { (keyword, _) ->
            normalized.contains(keyword)
        }?.second
        if (qualitativeScore != null) return qualitativeScore

        val numeric = Regex("""(?<!\d)\d{1,3}(?:\.\d+)?(?!\d)""")
            .find(scoreText)
            ?.value
            ?.toDoubleOrNull()
            ?.takeIf { it in 0.0..100.0 }
        if (numeric != null) return numeric

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

    fun academicYearOfSemester(semester: String): String? {
        val year = Regex("""\d{4}""").find(semester)?.value?.toIntOrNull() ?: return null
        return when {
            semester.contains("秋") -> "${year}学年"
            semester.contains("春") -> "${year - 1}学年"
            else -> null
        }
    }

    private fun semestersOfAcademicYear(academicYear: String): List<String> {
        val start = academicYearStart(academicYear) ?: return emptyList()
        return listOf("${start}秋", "${start + 1}春")
    }

    private fun academicYearStart(academicYear: String): Int? {
        return Regex("""\d{4}""").find(academicYear)?.value?.toIntOrNull()
    }

    private fun isEligiblePlanCourse(group: StudyPlanGroup, course: StudyPlanCourse): Boolean {
        val attribute = group.attribute.trim()
        val groupName = group.groupName.trim()
        val courseName = course.courseName.trim()
        val isRequiredOrLimited = attribute.contains("必修") || attribute.contains("限选")
        if (!isRequiredOrLimited) return false
        val combined = listOf(attribute, groupName, courseName).joinToString(" ")
        val excludedByKeyword = excludedCourseKeywords.any { combined.contains(it) }
        val excludedByStatus = course.status == CourseStatus.FAILED_REELECT || course.status == CourseStatus.PASSED_REELECT
        return !excludedByKeyword && !excludedByStatus
    }

    private fun belongsToCourseSemester(score: ScoreInfo, courseSemester: String): Boolean {
        val courseYear = Regex("""\d{4}""").find(courseSemester)?.value?.toIntOrNull() ?: return false
        val scoreYear = Regex("""\d{4}""").find(score.year)?.value?.toIntOrNull() ?: return false
        return when {
            courseSemester.contains("秋") -> score.term == 2 && scoreYear == courseYear
            courseSemester.contains("春") -> score.term == 1 && (scoreYear == courseYear || scoreYear == courseYear - 1)
            else -> false
        }
    }

    private fun resolveScore(candidates: List<ScoreInfo>): ResolvedScore? {
        if (candidates.isEmpty()) return null
        val withValues = candidates.mapNotNull { score ->
            val value = parsePercentScore(score.score) ?: return@mapNotNull null
            ScoreCandidate(score, value)
        }
        if (withValues.isEmpty()) return null

        val zeroPenalty = withValues.firstOrNull { it.score.isZeroPenalty() }
        if (zeroPenalty != null) return ResolvedScore(zeroPenalty.score, 0.0, "作弊/旷考/缺考按 0 分计")

        val deferred = withValues
            .filter { it.score.isDeferredExam() }
            .maxByOrNull { it.value }
        if (deferred != null) return ResolvedScore(deferred.score, deferred.value, "缓考后成绩")

        val makeups = withValues.filter { it.score.isMakeupExam() }
        val passedMakeup = makeups.firstOrNull { !it.score.isExplicitlyFailed() && (it.score.isPassed() || it.value >= 60.0) }
        if (passedMakeup != null) return ResolvedScore(passedMakeup.score, 60.0, "补考通过按 60 分计")

        val failedMakeup = makeups.firstOrNull()
        if (failedMakeup != null) {
            val original = withValues
                .filterNot { it.score.isMakeupExam() || it.score.isDeferredExam() }
                .maxByOrNull { it.value }
            return if (original != null) {
                ResolvedScore(original.score, original.value, "补考未通过按补考前分数计")
            } else {
                ResolvedScore(failedMakeup.score, failedMakeup.value, "补考未通过且缺少补考前分数，按可见成绩折算")
            }
        }

        val normal = withValues
            .filterNot { it.score.isMakeupExam() || it.score.isDeferredExam() }
            .maxByOrNull { it.value }
            ?: withValues.maxByOrNull { it.value }
            ?: return null
        return ResolvedScore(normal.score, normal.value, "成绩单原始成绩")
    }

    private fun ScoreInfo.factText(): String = listOf(
        score,
        category,
        examType
    ).joinToString(" ") { it.trim() }

    private fun ScoreInfo.isZeroPenalty(): Boolean {
        val text = normalizeScoreText(factText())
        return zeroScoreKeywords.any { text.contains(it) }
    }

    private fun ScoreInfo.isDeferredExam(): Boolean {
        val text = normalizeScoreText(factText())
        return text.contains("缓考")
    }

    private fun ScoreInfo.isMakeupExam(): Boolean {
        val text = normalizeScoreText(factText())
        return text.contains("补考") && !text.contains("缓考")
    }

    private fun ScoreInfo.isExplicitlyFailed(): Boolean {
        val text = normalizeScoreText(factText())
        return listOf("不及格", "不合格", "未通过", "不通过").any { text.contains(it) }
    }

    private fun ScoreInfo.isPassed(): Boolean {
        val text = normalizeScoreText(factText())
        if (isExplicitlyFailed()) return false
        return listOf("通过", "合格", "及格").any { text.contains(it) }
    }

    private fun normalizeCourseName(name: String): String {
        return name
            .replace('（', '(')
            .replace('）', ')')
            .replace(Regex("""\s+"""), "")
            .replace(Regex("""[：:；;，,。.]"""), "")
            .trim()
    }

    private fun normalizeScoreText(text: String): String {
        return text.trim().replace(Regex("""\s+"""), "")
    }

    private data class EligibleCourse(
        val groupName: String,
        val attribute: String,
        val course: StudyPlanCourse
    )

    private data class ScoreCandidate(
        val score: ScoreInfo,
        val value: Double
    )

    private data class ResolvedScore(
        val score: ScoreInfo,
        val value: Double,
        val reason: String
    )

    private val excludedCourseKeywords = listOf(
        "任选",
        "公共选修",
        "公选",
        "体育",
        "补修",
        "重修",
        "重学",
        "重新学习",
        "辅修",
        "双学位",
        "第二专业",
        "第二学位"
    )

    private val zeroScoreKeywords = listOf("作弊", "旷考", "无故旷考", "缺考", "违纪")

    private val qualitativeScoreMap = listOf(
        "不合格" to 40.0,
        "不及格" to 40.0,
        "未通过" to 40.0,
        "不通过" to 40.0,
        "优秀" to 95.0,
        "优" to 95.0,
        "良好" to 85.0,
        "良" to 85.0,
        "中等" to 75.0,
        "中" to 75.0,
        "合格" to 65.0,
        "通过" to 65.0,
        "及格" to 65.0
    )

    private val letterGradeScoreMap = mapOf(
        "A" to 95.0,
        "B" to 85.0,
        "C" to 75.0,
        "D" to 65.0,
        "F" to 40.0
    )
}

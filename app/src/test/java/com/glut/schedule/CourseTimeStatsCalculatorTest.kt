package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseTimeDimension
import com.glut.schedule.data.model.CourseTimeSemesterSource
import com.glut.schedule.data.model.CourseTimeStatsCalculator
import com.glut.schedule.data.model.CourseTimeStatsUnavailableReason
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.allocateCourseTimeStatsColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseTimeStatsCalculatorTest {
    @Test
    fun sumsTeachingMinutesWithoutBreaksAndExpandsWeeks() {
        val result = CourseTimeStatsCalculator.calculate(
            sources = listOf(
                source(
                    courses = listOf(
                        course(
                            title = "软件工程",
                            weekText = "1-2周",
                            startSection = 1,
                            endSection = 2
                        )
                    )
                )
            ),
            dimension = CourseTimeDimension.COURSE
        )

        // 两节各 45 分钟，中间 10 分钟课间不计；连续上课两周共 180 分钟。
        assertEquals(180, result.totalMinutes)
        assertEquals(180, result.items.single().minutes)
        assertEquals(1.0, result.items.single().share, 0.000001)
        assertEquals("#A21CAF", result.items.single().colorHex)
    }

    @Test
    fun mergesNormalizedCourseNamesAcrossSemesters() {
        val result = CourseTimeStatsCalculator.calculate(
            sources = listOf(
                source(
                    id = "new",
                    label = "2026·春",
                    courses = listOf(course(title = "大学英语 @01", weekText = "1周"))
                ),
                source(
                    id = "old",
                    label = "2025·秋",
                    courses = listOf(course(title = "  大学英语   @02  ", weekText = "1周"))
                )
            ),
            dimension = CourseTimeDimension.COURSE
        )

        assertEquals(1, result.items.size)
        assertEquals("大学英语", result.items.single().label)
        assertEquals(90, result.items.single().minutes)
        assertEquals(2, result.coverage.eligibleSemesters)
        assertEquals(2, result.coverage.downloadedSemesters)
    }

    @Test
    fun attributesFullDurationToEveryExplicitTeacher() {
        val result = CourseTimeStatsCalculator.calculate(
            sources = listOf(
                source(
                    courses = listOf(
                        course(
                            teacher = "张三、李四、张三",
                            weekText = "1周",
                            startSection = 1,
                            endSection = 2
                        )
                    )
                )
            ),
            dimension = CourseTimeDimension.TEACHER
        )

        assertEquals(180, result.totalMinutes)
        assertEquals(listOf("张三", "李四"), result.items.map { it.label })
        assertTrue(result.items.all { it.minutes == 90 })
        assertTrue(result.items.all { it.share == 0.5 })
    }

    @Test
    fun usesOccurrenceRoomAndKeepsMissingRoomInTheDenominator() {
        val result = CourseTimeStatsCalculator.calculate(
            sources = listOf(
                source(
                    courses = listOf(
                        course(id = "a", room = "旧教室", occurrenceRoom = "A101", weekText = "1周"),
                        course(id = "b", room = "", occurrenceRoom = "", weekText = "1周")
                    )
                )
            ),
            dimension = CourseTimeDimension.ROOM
        )

        assertEquals(listOf("A101", "未填写教室"), result.items.map { it.label })
        assertEquals(90, result.totalMinutes)
        assertEquals(45, result.items[0].minutes)
        assertEquals(45, result.items[1].minutes)
    }

    @Test
    fun excludesPendingEmptyPlaceholderWhenSameCourseHasReliableOccurrences() {
        val courses = listOf(
            course(
                id = "sport-real",
                title = "体育4",
                teacher = "修佳伟",
                occurrenceRoom = "体育训练馆",
                weekText = "1-15单周"
            ),
            course(
                id = "sport-placeholder",
                title = "体育4",
                room = "",
                teacher = "待确认",
                occurrenceRoom = "",
                weekText = "2-16双周"
            )
        )

        val courseResult = CourseTimeStatsCalculator.calculate(
            listOf(source(courses = courses)),
            CourseTimeDimension.COURSE
        )
        val roomResult = CourseTimeStatsCalculator.calculate(
            listOf(source(courses = courses)),
            CourseTimeDimension.ROOM
        )
        val teacherResult = CourseTimeStatsCalculator.calculate(
            listOf(source(courses = courses)),
            CourseTimeDimension.TEACHER
        )

        assertEquals(360, courseResult.totalMinutes)
        assertEquals(listOf("体育4"), courseResult.items.map { it.label })
        assertEquals(360, roomResult.totalMinutes)
        assertEquals(listOf("体育训练馆"), roomResult.items.map { it.label })
        assertEquals(360, teacherResult.totalMinutes)
        assertEquals(listOf("修佳伟"), teacherResult.items.map { it.label })
    }

    @Test
    fun keepsPendingCourseWhenItHasNoReliableSameCourseCounterpart() {
        val courses = listOf(
            course(
                title = "临时课程",
                teacher = "待确认",
                occurrenceRoom = "A101",
                weekText = "1周"
            )
        )

        val courseResult = CourseTimeStatsCalculator.calculate(
            listOf(source(courses = courses)),
            CourseTimeDimension.COURSE
        )
        val teacherResult = CourseTimeStatsCalculator.calculate(
            listOf(source(courses = courses)),
            CourseTimeDimension.TEACHER
        )

        assertEquals(45, courseResult.totalMinutes)
        assertEquals(listOf("临时课程"), courseResult.items.map { it.label })
        assertEquals(45, teacherResult.totalMinutes)
        assertEquals(listOf("未填写教师"), teacherResult.items.map { it.label })
    }

    @Test
    fun excludesWholeSemesterWhenPeriodOrWeekMetadataIsInvalid() {
        val missingPeriod = source(
            id = "missing-period",
            label = "2025·秋",
            courses = listOf(course(startSection = 1, endSection = 2)),
            periods = listOf(ClassPeriod(1, "08:00", "08:45"))
        )
        val invalidWeek = source(
            id = "invalid-week",
            label = "2025·春",
            courses = listOf(course(weekText = "待确认"))
        )

        val result = CourseTimeStatsCalculator.calculate(
            sources = listOf(missingPeriod, invalidWeek),
            dimension = CourseTimeDimension.COURSE
        )

        assertEquals(0, result.totalMinutes)
        assertEquals(0, result.coverage.eligibleSemesters)
        assertEquals(2, result.coverage.downloadedSemesters)
        assertEquals(
            listOf(
                CourseTimeStatsUnavailableReason.MISSING_CLASS_PERIODS,
                CourseTimeStatsUnavailableReason.INVALID_WEEK_TEXT
            ),
            result.coverage.excludedSemesters.map { it.reason }
        )
    }

    @Test
    fun keepsEveryDistributionItemAndUsesStableColorsIndependentOfRanking() {
        val sixCourses = (1..6).map { index ->
            course(
                id = "course-$index",
                title = "课程$index",
                weekText = "1-${7 - index}周"
            )
        }
        val first = CourseTimeStatsCalculator.calculate(
            sources = listOf(source(courses = sixCourses)),
            dimension = CourseTimeDimension.COURSE
        )
        val second = CourseTimeStatsCalculator.calculate(
            sources = listOf(source(courses = sixCourses.reversed())),
            dimension = CourseTimeDimension.COURSE
        )

        assertEquals(6, first.items.size)
        assertEquals(6, first.distribution.size)
        assertEquals(first.items, first.distribution)
        assertTrue(first.distribution.none { it.label == "其他" })
        assertEquals(
            first.items.associate { it.key to it.colorHex },
            second.items.associate { it.key to it.colorHex }
        )
    }

    @Test
    fun assignsSameDeterministicTwentyFourColorSequenceAsWeChat() {
        val colors = allocateCourseTimeStatsColors(
            dimension = CourseTimeDimension.COURSE,
            keys = listOf("alpha", "beta", "delta", "epsilon", "gamma", "zeta")
        )

        assertEquals(
            listOf("#DB2777", "#9333EA", "#EA580C", "#16A34A", "#DC2626", "#6D28D9"),
            colors
        )
    }

    @Test
    fun usesEveryPaletteColorBeforeNonAdjacentReuseAndSeparatesRingEnds() {
        val colors = allocateCourseTimeStatsColors(
            dimension = CourseTimeDimension.TEACHER,
            keys = (1..28).map { "item-${it.toString().padStart(2, '0')}" }
        )

        assertEquals(24, colors.take(24).toSet().size)
        colors.indices.forEach { index ->
            val next = (index + 1) % colors.size
            assertFalse(
                "相邻扇区 ${colors[index]} 与 ${colors[next]} 不应相同或近似",
                colorsAreTooClose(colors[index], colors[next])
            )
        }
    }

    @Test
    fun countsDownloadedEmptySemesterAsEligibleCoverage() {
        val result = CourseTimeStatsCalculator.calculate(
            sources = listOf(
                source(id = "empty", label = "2024·秋", courses = emptyList(), periods = emptyList())
            ),
            dimension = CourseTimeDimension.COURSE
        )

        assertEquals(0, result.totalMinutes)
        assertEquals(1, result.coverage.eligibleSemesters)
        assertEquals(1, result.coverage.downloadedSemesters)
        assertTrue(result.coverage.excludedSemesters.isEmpty())
    }

    private fun source(
        id: String = "current",
        label: String = "2026·春",
        courses: List<ScheduleCourse>,
        periods: List<ClassPeriod> = listOf(
            ClassPeriod(1, "08:00", "08:45"),
            ClassPeriod(2, "08:55", "09:40")
        )
    ) = CourseTimeSemesterSource(
        semesterId = id,
        semesterLabel = label,
        isCurrent = id == "current",
        isDownloaded = true,
        portalMaxWeek = 20,
        courses = courses,
        classPeriods = periods
    )

    private fun course(
        id: String = "course",
        title: String = "课程",
        room: String = "A101",
        teacher: String = "教师",
        occurrenceRoom: String = room,
        weekText: String = "1周",
        startSection: Int = 1,
        endSection: Int = 1
    ) = ScheduleCourse(
        id = id,
        title = title,
        room = room,
        teacher = teacher,
        colorHex = "#3B82F6",
        occurrences = listOf(
            CourseOccurrence(
                id = "$id-occurrence",
                courseId = id,
                dayOfWeek = 1,
                startSection = startSection,
                endSection = endSection,
                weekText = weekText,
                note = occurrenceRoom
            )
        )
    )

    private fun colorsAreTooClose(left: String, right: String): Boolean {
        fun channel(value: String, start: Int) = value.substring(start, start + 2).toInt(16)
        val distanceSquared = listOf(1, 3, 5).sumOf { start ->
            val difference = channel(left, start) - channel(right, start)
            difference * difference
        }
        return distanceSquared < 95 * 95
    }
}

package com.glut.schedule.data.model

data class SemesterAdjustment(
    val id: String,
    val type: String,
    val title: String,
    val teacher: String,
    val originalWeek: Int,
    val originalDay: Int,
    val originalStartSection: Int,
    val originalEndSection: Int,
    val originalRoom: String,
    val makeupWeek: Int,
    val makeupDay: Int,
    val makeupStartSection: Int,
    val makeupEndSection: Int,
    val makeupRoom: String
)

/** 调课一览按实际发生的调后时段归组；没有调后时段的停课/代课才回退原周次。 */
fun SemesterAdjustment.overviewWeek(): Int = makeupWeek.takeIf { it > 0 } ?: originalWeek

data class HolidayInfo(
    val name: String,
    val startDate: String,
    val endDate: String,
    val daysOff: Int
)

/**
 * 大节课表底部「没有具体上课时间或地点的课程」。
 * 这些课程没有可落到网格的星期/节次，只作为学期概览里的独立列表展示。
 */
data class NoArrangementCourse(
    val id: String,
    val courseCode: String,
    val courseName: String,
    val classSequence: String,
    val teacher: String,
    val classGroup: String,
    val weekText: String,
    val dayOfWeek: String,
    val location: String
)

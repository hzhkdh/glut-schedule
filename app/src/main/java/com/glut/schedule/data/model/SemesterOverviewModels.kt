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

data class HolidayInfo(
    val name: String,
    val startDate: String,
    val endDate: String,
    val daysOff: Int
)

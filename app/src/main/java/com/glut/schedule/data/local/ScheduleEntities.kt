package com.glut.schedule.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse

@Entity(tableName = "courses")
data class CourseEntity(
    @PrimaryKey val id: String,
    val title: String,
    val room: String,
    val teacher: String,
    val colorHex: String
)

@Entity(tableName = "course_occurrences")
data class CourseOccurrenceEntity(
    @PrimaryKey val id: String,
    val courseId: String,
    val dayOfWeek: Int,
    val startSection: Int,
    val endSection: Int,
    val weekText: String,
    val note: String
)

@Entity(tableName = "class_periods")
data class ClassPeriodEntity(
    @PrimaryKey val section: Int,
    val startsAt: String,
    val endsAt: String
)

fun ScheduleCourse.toEntity(): CourseEntity = CourseEntity(id, title, room, teacher, colorHex)

fun CourseOccurrence.toEntity(): CourseOccurrenceEntity = CourseOccurrenceEntity(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    weekText = weekText,
    note = note
)

fun ClassPeriod.toEntity(): ClassPeriodEntity = ClassPeriodEntity(section, startsAt, endsAt)

fun CourseOccurrenceEntity.toModel(): CourseOccurrence = CourseOccurrence(
    id = id,
    courseId = courseId,
    dayOfWeek = dayOfWeek,
    startSection = startSection,
    endSection = endSection,
    weekText = weekText,
    note = note
)

fun ClassPeriodEntity.toModel(): ClassPeriod = ClassPeriod(section, startsAt, endsAt)

fun CourseEntity.toModel(occurrences: List<CourseOccurrence>): ScheduleCourse = ScheduleCourse(
    id = id,
    title = title,
    room = room,
    teacher = teacher,
    colorHex = colorHex,
    occurrences = occurrences
)

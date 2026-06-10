package com.glut.schedule.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.data.model.GradeExamInfo
import com.glut.schedule.data.model.CourseStatus
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.sanitized

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

@Entity(tableName = "exams")
data class ExamEntity(
    @PrimaryKey val id: String,
    val courseName: String,
    val examDate: String,
    val startTime: String,
    val endTime: String,
    val location: String,
    val seatNumber: String,
    val examType: String,
    val note: String
)

fun ExamInfo.toEntity(): ExamEntity {
    val clean = sanitized()
    return ExamEntity(
        id = clean.id,
        courseName = clean.courseName,
        examDate = clean.examDate.toString(),
        startTime = clean.startTime,
        endTime = clean.endTime,
        location = clean.location,
        seatNumber = clean.seatNumber,
        examType = clean.examType,
        note = clean.note
    )
}

fun ExamEntity.toModel(): ExamInfo = ExamInfo(
    id = id,
    courseName = courseName,
    examDate = java.time.LocalDate.parse(examDate),
    startTime = startTime,
    endTime = endTime,
    location = location,
    seatNumber = seatNumber,
    examType = examType,
    note = note
).sanitized()

@Entity(tableName = "scores")
data class ScoreEntity(
    @PrimaryKey val id: String,
    val courseName: String,
    val score: String,
    val gpa: Double,
    val credit: Double,
    val year: String,
    val term: Int,
    val category: String,
    val examType: String
)

fun ScoreInfo.toEntity(): ScoreEntity = ScoreEntity(
    id = id, courseName = courseName, score = score,
    gpa = gpa, credit = credit, year = year,
    term = term, category = category, examType = examType
)

fun ScoreEntity.toModel(): ScoreInfo = ScoreInfo(
    id = id, courseName = courseName, score = score,
    gpa = gpa, credit = credit, year = year,
    term = term, category = category, examType = examType
)

@Entity(tableName = "grade_exams")
data class GradeExamEntity(
    @PrimaryKey val id: String,
    val examName: String,
    val examTime: String,
    val ticketNumber: String,
    val score: String,
    val status: String
)

fun GradeExamInfo.toEntity(): GradeExamEntity = GradeExamEntity(
    id = id, examName = examName, examTime = examTime,
    ticketNumber = ticketNumber, score = score,
    status = status
)

fun GradeExamEntity.toModel(): GradeExamInfo = GradeExamInfo(
    id = id, examName = examName, examTime = examTime,
    ticketNumber = ticketNumber, score = score,
    status = status
)

@Entity(tableName = "study_plan_groups")
data class StudyPlanGroupEntity(
    @PrimaryKey val id: String,
    val groupName: String,
    val attribute: String,
    val creditRequired: Double,
    val creditEarned: Double,
    val countRequired: Int,
    val countPassed: Int,
    val isPassed: Boolean
)

fun StudyPlanGroup.toEntity(): StudyPlanGroupEntity = StudyPlanGroupEntity(
    id = id, groupName = groupName, attribute = attribute,
    creditRequired = creditRequired, creditEarned = creditEarned,
    countRequired = countRequired, countPassed = countPassed,
    isPassed = isPassed
)

fun StudyPlanGroupEntity.toModel(): StudyPlanGroup = StudyPlanGroup(
    id = id, groupName = groupName, attribute = attribute,
    creditRequired = creditRequired, creditEarned = creditEarned,
    countRequired = countRequired, countPassed = countPassed,
    isPassed = isPassed
)

@Entity(
    tableName = "study_plan_courses",
    foreignKeys = [ForeignKey(
        entity = StudyPlanGroupEntity::class,
        parentColumns = ["id"],
        childColumns = ["groupId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["groupId"])]
)
data class StudyPlanCourseEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val courseName: String,
    val credit: Double,
    val hours: String,
    val assessment: String,
    val semester: String,
    val status: String
)

fun StudyPlanCourse.toEntity(): StudyPlanCourseEntity = StudyPlanCourseEntity(
    id = id, groupId = groupId, courseName = courseName,
    credit = credit, hours = hours, assessment = assessment,
    semester = semester, status = status.name
)

fun StudyPlanCourseEntity.toModel(): StudyPlanCourse = StudyPlanCourse(
    id = id, groupId = groupId, courseName = courseName,
    credit = credit, hours = hours, assessment = assessment,
    semester = semester,
    status = try { CourseStatus.valueOf(status) } catch (_: Exception) { CourseStatus.UNKNOWN }
)

@Entity(tableName = "semester_adjustments")
data class SemesterAdjustmentEntity(
    @PrimaryKey val id: String,
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

fun SemesterAdjustment.toEntity(): SemesterAdjustmentEntity = SemesterAdjustmentEntity(
    id = id, title = title, teacher = teacher,
    originalWeek = originalWeek, originalDay = originalDay,
    originalStartSection = originalStartSection, originalEndSection = originalEndSection,
    originalRoom = originalRoom,
    makeupWeek = makeupWeek, makeupDay = makeupDay,
    makeupStartSection = makeupStartSection, makeupEndSection = makeupEndSection,
    makeupRoom = makeupRoom
)

fun SemesterAdjustmentEntity.toModel(): SemesterAdjustment = SemesterAdjustment(
    id = id, title = title, teacher = teacher,
    originalWeek = originalWeek, originalDay = originalDay,
    originalStartSection = originalStartSection, originalEndSection = originalEndSection,
    originalRoom = originalRoom,
    makeupWeek = makeupWeek, makeupDay = makeupDay,
    makeupStartSection = makeupStartSection, makeupEndSection = makeupEndSection,
    makeupRoom = makeupRoom
)

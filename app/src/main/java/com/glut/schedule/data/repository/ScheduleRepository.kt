package com.glut.schedule.data.repository

import com.glut.schedule.data.local.ScheduleDao
import com.glut.schedule.data.local.toEntity
import com.glut.schedule.data.local.toModel
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.sampleCourses
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

class ScheduleRepository(
    private val dao: ScheduleDao
) {
    val courses: Flow<List<ScheduleCourse>> = combine(
        dao.observeCourses(),
        dao.observeOccurrences()
    ) { courses, occurrences ->
        val occurrencesByCourse = occurrences.map { it.toModel() }.groupBy { it.courseId }
        courses.map { course -> course.toModel(occurrencesByCourse[course.id].orEmpty()) }
    }

    val classPeriods: Flow<List<ClassPeriod>> = dao.observeClassPeriods().map { periods ->
        periods.map { it.toModel() }.ifEmpty { defaultClassPeriods() }
    }

    suspend fun seedIfEmpty() {
        dao.insertClassPeriods(defaultClassPeriods().map { it.toEntity() })

        if (dao.courseCount() > 0) return

        val courses = sampleCourses()
        dao.insertCourses(courses.map { it.toEntity() })
        dao.insertOccurrences(courses.flatMap { course -> course.occurrences.map { it.toEntity() } })
    }

    suspend fun replaceImportedCourses(courses: List<ScheduleCourse>) {
        dao.insertClassPeriods(defaultClassPeriods().map { it.toEntity() })
        dao.replaceCourses(
            courses = courses.map { it.toEntity() },
            occurrences = courses.flatMap { course -> course.occurrences.map { it.toEntity() } }
        )
    }
}

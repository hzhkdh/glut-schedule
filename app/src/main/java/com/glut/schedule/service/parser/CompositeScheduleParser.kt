package com.glut.schedule.service.parser

import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.SemesterAdjustment

class CompositeScheduleParser(
    private val parsers: List<AcademicScheduleParser>
) : AcademicScheduleParser {

    override fun parsePersonalSchedule(html: String): List<ScheduleCourse> {
        for (parser in parsers) {
            val result = runCatching {
                parser.parsePersonalSchedule(html)
            }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    override fun parseAdjustments(html: String): List<SemesterAdjustment> {
        for (parser in parsers) {
            val result = runCatching {
                parser.parseAdjustments(html)
            }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    override fun applyAdjustmentsToCourses(courses: List<ScheduleCourse>, adjustmentHtml: String): List<ScheduleCourse> {
        for (parser in parsers) {
            val result = runCatching {
                parser.applyAdjustmentsToCourses(courses, adjustmentHtml)
            }.getOrDefault(courses)
            if (result != courses) return result
        }
        return courses
    }
}

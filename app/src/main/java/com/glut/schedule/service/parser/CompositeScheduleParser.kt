package com.glut.schedule.service.parser

import com.glut.schedule.data.model.ScheduleCourse

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
}

package com.glut.schedule

import com.glut.schedule.service.academic.AcademicTodayPlanParser
import org.junit.Assert.assertEquals
import org.junit.Test

class AcademicTodayPlanParserTest {
    @Test
    fun parsesCurrentTodayPlanJsonIntoCoursesForCurrentWeek() {
        val json = """
            {
              "code": 1,
              "data": [
                {
                  "planType": 1,
                  "dateType": 1,
                  "resBuildingName": "雁山6号楼",
                  "endDate": "2000-12-31 10:00:00",
                  "name": "嵌入式系统",
                  "time": "第1、2节",
                  "arrangeDate": "2026-05-06 00:00:00",
                  "startDate": "2000-12-31 08:20:00",
                  "roomName": "06104D"
                },
                {
                  "planType": 1,
                  "dateType": 2,
                  "resBuildingName": "雁山操场",
                  "name": "体育4",
                  "time": "第5、6节",
                  "arrangeDate": "2026-05-06 00:00:00",
                  "roomName": "体育训练馆"
                }
              ]
            }
        """.trimIndent()

        val courses = AcademicTodayPlanParser.parse(json, currentWeekNumber = 9)

        assertEquals(2, courses.size)
        assertEquals("嵌入式系统", courses[0].title)
        assertEquals("06104D", courses[0].room)
        assertEquals("待确认", courses[0].teacher)
        assertEquals(3, courses[0].occurrences.single().dayOfWeek)
        assertEquals(1, courses[0].occurrences.single().startSection)
        assertEquals(2, courses[0].occurrences.single().endSection)
        assertEquals("第9周", courses[0].occurrences.single().weekText)
        assertEquals("体育训练馆", courses[1].room)
    }
}

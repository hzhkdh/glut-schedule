package com.glut.schedule

import com.glut.schedule.service.parser.GlutAcademicScheduleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcademicScheduleParserTest {
    private val parser = GlutAcademicScheduleParser()

    @Test
    fun parsesCourseCellsWithExplicitDayAndSectionAttributes() {
        val html = """
            <table>
              <tr>
                <td data-day="2" data-start="1" data-end="2">
                  数字逻辑<br/>@06408D<br/>卢佩
                </td>
                <td data-day="3" data-start="3" data-end="4">
                  嵌入式系统<br/>06104D<br/>蒋志军
                </td>
              </tr>
            </table>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)

        assertEquals(2, courses.size)
        assertEquals("数字逻辑", courses[0].title)
        assertEquals("06408D", courses[0].room)
        assertEquals("卢佩", courses[0].teacher)
        assertEquals(2, courses[0].occurrences.first().dayOfWeek)
        assertEquals(1, courses[0].occurrences.first().startSection)
        assertEquals(2, courses[0].occurrences.first().endSection)
    }

    @Test
    fun ignoresNonTimetableAuditPages() {
        val html = """
            <html><body>
              <h1>综合审查结果</h1>
              <table><tr><td>5-警示_累计学分审查 2026春</td><td>学籍处理</td></tr></table>
            </body></html>
        """.trimIndent()

        assertTrue(parser.parsePersonalSchedule(html).isEmpty())
    }

    @Test
    fun parsesGlutCourseArrangementRows() {
        val html = """
            <html><body>
              <h2>2026春 课程安排</h2>
              <table>
                <tr>
                  <th>课程号</th><th>课程序号</th><th>课程名称</th><th>任课教师</th>
                  <th>学分</th><th>选课属性</th><th>考核方式</th><th>考试性质</th>
                  <th>是否缓考</th><th>上课时间、地点</th><th>教材</th><th>教学记录</th>
                </tr>
                <tr>
                  <td>518740</td><td>20</td><td>形势与政策5</td><td>梁英</td>
                  <td>0.5</td><td>必修</td><td>考查</td><td>正常考试</td>
                  <td>非缓考</td>
                  <td>
                    11-12周 星期一 第5、6节 07120D<br/>
                    1-12周 星期二 第1、2节 06408D
                  </td>
                  <td></td><td></td>
                </tr>
                <tr>
                  <td>398690</td><td>1</td><td>机器学习</td><td>石凯</td>
                  <td>3</td><td>必修</td><td>考试</td><td>正常考试</td>
                  <td>非缓考</td>
                  <td>
                    1-6周 星期二 第3、4节 06409D<br/>
                    7-12周 星期二 第3、4节 014104J
                  </td>
                  <td></td><td></td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)

        assertEquals(2, courses.size)
        assertEquals("形势与政策5", courses[0].title)
        assertEquals("梁英", courses[0].teacher)
        assertEquals("07120D", courses[0].room)
        assertEquals(2, courses[0].occurrences.size)
        assertEquals(1, courses[0].occurrences[0].dayOfWeek)
        assertEquals(5, courses[0].occurrences[0].startSection)
        assertEquals(6, courses[0].occurrences[0].endSection)
        assertEquals("11-12周", courses[0].occurrences[0].weekText)
        assertEquals(2, courses[0].occurrences[1].dayOfWeek)
        assertEquals(1, courses[0].occurrences[1].startSection)
        assertEquals(2, courses[0].occurrences[1].endSection)
        assertEquals("06408D", courses[0].occurrences[1].note)
    }

    @Test
    fun parsesGlutStudentTimetableGridCellsAndMergesRepeatedSections() {
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第1节<br>08:20<br>┆<br>09:05</th>
                  <td id="1-1" class="center">&nbsp;</td>
                  <td id="2-1" class="center">&lt;&lt;数字逻辑&gt;&gt;;2<br>06408D<br>卢佩<br>1-12周<br>讲课学时</td>
                  <td id="3-1" class="center">&lt;&lt;嵌入式系统&gt;&gt;;1<br>06104D<br>蒋志军<br>3-12,16-17<br>讲课学时</td>
                  <td id="4-1" class="center">&lt;&lt;大学生创新创业教育&gt;&gt;;16<br>线上教学<br>张威<br>1-2周<br>讲课学时<br>&lt;&lt;大学英语 4&gt;&gt;;13<br>06403D<br>莫梓<br>5-14周<br>讲课学时</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第2节<br>09:15<br>┆<br>10:00</th>
                  <td id="1-2" class="center">&nbsp;</td>
                  <td id="2-2" class="center">&lt;&lt;数字逻辑&gt;&gt;;2<br>06408D<br>卢佩<br>1-12周<br>讲课学时</td>
                  <td id="3-2" class="center">&lt;&lt;嵌入式系统&gt;&gt;;1<br>06104D<br>蒋志军<br>3-12,16-17<br>讲课学时</td>
                  <td id="4-2" class="center">&lt;&lt;大学生创新创业教育&gt;&gt;;16<br>线上教学<br>张威<br>1-2周<br>讲课学时<br>&lt;&lt;大学英语 4&gt;&gt;;13<br>06403D<br>莫梓<br>5-14周<br>讲课学时</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        val digitalLogic = courses.first { it.title == "数字逻辑" }
        val embedded = courses.first { it.title == "嵌入式系统" }
        val innovation = courses.first { it.title == "大学生创新创业教育" }
        val english = courses.first { it.title == "大学英语 4" }

        assertEquals(4, courses.size)
        assertEquals("06408D", digitalLogic.room)
        assertEquals("卢佩", digitalLogic.teacher)
        assertEquals(2, digitalLogic.occurrences.single().dayOfWeek)
        assertEquals(1, digitalLogic.occurrences.single().startSection)
        assertEquals(2, digitalLogic.occurrences.single().endSection)
        assertEquals("1-12周", digitalLogic.occurrences.single().weekText)
        assertEquals("06104D", embedded.room)
        assertEquals("蒋志军", embedded.teacher)
        assertEquals("3-12,16-17", embedded.occurrences.single().weekText)
        assertEquals("线上教学", innovation.room)
        assertEquals("张威", innovation.teacher)
        assertEquals("06403D", english.room)
        assertEquals("莫梓", english.teacher)
    }
}

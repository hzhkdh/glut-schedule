package com.glut.schedule

import com.glut.schedule.service.parser.GlutAcademicScheduleParser
import com.glut.schedule.data.model.isActiveInWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        val policyMonday = courses.single { it.title == "形势与政策5" && it.room == "07120D" }
        val policyTuesday = courses.single { it.title == "形势与政策5" && it.room == "06408D" }
        val machineLearningLab = courses.single { it.title == "机器学习" && it.room == "014104J" }

        assertEquals("梁英", policyMonday.teacher)
        assertEquals(1, policyMonday.occurrences.single().dayOfWeek)
        assertEquals(5, policyMonday.occurrences.single().startSection)
        assertEquals(6, policyMonday.occurrences.single().endSection)
        assertEquals("11-12周", policyMonday.occurrences.single().weekText)
        assertEquals(2, policyTuesday.occurrences.single().dayOfWeek)
        assertEquals(1, policyTuesday.occurrences.single().startSection)
        assertEquals(2, policyTuesday.occurrences.single().endSection)
        assertEquals("06408D", policyTuesday.occurrences.single().note)
        assertEquals("石凯", machineLearningLab.teacher)
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

    @Test
    fun prefersExplicitWeekTextOverInvalidCompactFieldsInExperimentCells() {
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第1节<br>08:20<br>┆<br>09:05</th>
                  <td id="1-1">&nbsp;</td><td id="2-1">&nbsp;</td><td id="3-1">&nbsp;</td><td id="4-1">&nbsp;</td>
                  <td id="5-1">&lt;&lt;微机原理与接口技术&gt;&gt;;1<br>014102S<br>陈守学<br>2-1<br>第11周<br>实验学时</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第2节<br>09:15<br>┆<br>10:00</th>
                  <td id="1-2">&nbsp;</td><td id="2-2">&nbsp;</td><td id="3-2">&nbsp;</td><td id="4-2">&nbsp;</td>
                  <td id="5-2">&lt;&lt;微机原理与接口技术&gt;&gt;;1<br>014102S<br>陈守学<br>2-1<br>第11周<br>实验学时</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第3节<br>10:20<br>┆<br>11:05</th>
                  <td id="1-3">&nbsp;</td><td id="2-3">&nbsp;</td><td id="3-3">&nbsp;</td><td id="4-3">&nbsp;</td>
                  <td id="5-3">&lt;&lt;微机原理与接口技术&gt;&gt;;1<br>014102S<br>陈守学<br>2-1<br>第11周<br>实验学时</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第4节<br>11:15<br>┆<br>12:00</th>
                  <td id="1-4">&nbsp;</td><td id="2-4">&nbsp;</td><td id="3-4">&nbsp;</td><td id="4-4">&nbsp;</td>
                  <td id="5-4">&lt;&lt;微机原理与接口技术&gt;&gt;;1<br>014102S<br>陈守学<br>2-1<br>第11周<br>实验学时</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val course = parser.parsePersonalSchedule(html).single()
        val occurrence = course.occurrences.single()

        assertEquals("微机原理与接口技术", course.title)
        assertEquals("014102S", course.room)
        assertEquals("陈守学", course.teacher)
        assertEquals(5, occurrence.dayOfWeek)
        assertEquals(1, occurrence.startSection)
        assertEquals(4, occurrence.endSection)
        assertEquals("第11周", occurrence.weekText)
    }

    @Test
    fun keepsFullGridWhenPartialExplicitCellsExist() {
        val html = """
            <html><body>
              <table>
                <tr>
                  <td data-day="2" data-start="1" data-end="2">
                    数字逻辑<br/>@06408D<br/>卢佩<br/>1-12周
                  </td>
                </tr>
              </table>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第5节<br>14:30<br>┆<br>15:15</th>
                  <td id="1-5">&lt;&lt;习近平新时代中国特色社会主义思想概论&gt;&gt;;20<br>07120D<br>梁英<br>1-10周<br>讲课学时</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第6节<br>15:20<br>┆<br>16:05</th>
                  <td id="1-6">&lt;&lt;习近平新时代中国特色社会主义思想概论&gt;&gt;;20<br>07120D<br>梁英<br>1-10周<br>讲课学时</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        val politics = courses.first { it.title == "习近平新时代中国特色社会主义思想概论" }
        val occurrence = politics.occurrences.single()

        assertTrue(courses.any { it.title == "数字逻辑" })
        assertEquals("梁英", politics.teacher)
        assertEquals("07120D", politics.room)
        assertEquals(1, occurrence.dayOfWeek)
        assertEquals(5, occurrence.startSection)
        assertEquals(6, occurrence.endSection)
        assertEquals("1-10周", occurrence.weekText)
    }

    @Test
    fun keepsRoomBoundToEachCourseBlockWhenSameCourseUsesDifferentRooms() {
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第3节<br>10:20<br>┆<br>11:05</th>
                  <td id="1-3">&nbsp;</td>
                  <td id="2-3">
                    &lt;&lt;机器学习&gt;&gt;;1<br>06409D<br>石凯<br>1-6周<br>讲课学时<br>
                    &lt;&lt;机器学习&gt;&gt;;1<br>014104J<br>石凯<br>7-12周<br>上机学时
                  </td>
                  <td id="3-3">
                    &lt;&lt;算法设计与分析&gt;&gt;;1<br>07120D<br>敬超<br>11-12周<br>讲课学时
                  </td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第4节<br>11:15<br>┆<br>12:00</th>
                  <td id="1-4">&nbsp;</td>
                  <td id="2-4">
                    &lt;&lt;机器学习&gt;&gt;;1<br>06409D<br>石凯<br>1-6周<br>讲课学时<br>
                    &lt;&lt;机器学习&gt;&gt;;1<br>014104J<br>石凯<br>7-12周<br>上机学时
                  </td>
                  <td id="3-4">
                    &lt;&lt;算法设计与分析&gt;&gt;;1<br>06402D<br>敬超<br>16-18周<br>讲课学时
                  </td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)

        val machineLearningLab = courses.single {
            it.title == "机器学习" &&
                it.room == "014104J" &&
                it.occurrences.any { occurrence ->
                    occurrence.dayOfWeek == 2 &&
                        occurrence.startSection == 3 &&
                        occurrence.endSection == 4 &&
                        occurrence.weekText == "7-12周"
                }
        }
        val algorithmEvening = courses.single {
            it.title == "算法设计与分析" &&
                it.room == "06402D" &&
                it.occurrences.any { occurrence ->
                    occurrence.dayOfWeek == 3 &&
                        occurrence.startSection == 4 &&
                        occurrence.endSection == 4 &&
                        occurrence.weekText == "16-18周"
                }
        }

        assertEquals("石凯", machineLearningLab.teacher)
        assertEquals("敬超", algorithmEvening.teacher)
    }

    @Test
    fun appliesAdjustmentRowsByRemovingOriginalOccurrenceAndAddingMakeupOccurrence() {
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第3节<br>10:20<br>┆<br>11:05</th>
                  <td id="1-3">&nbsp;</td><td id="2-3">&nbsp;</td><td id="3-3">&nbsp;</td>
                  <td id="4-3">&lt;&lt;嵌入式系统&gt;&gt;;1<br>06104D<br>蒋志军<br>3-6周<br>讲课学时</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第4节<br>11:15<br>┆<br>12:00</th>
                  <td id="1-4">&nbsp;</td><td id="2-4">&nbsp;</td><td id="3-4">&nbsp;</td>
                  <td id="4-4">&lt;&lt;嵌入式系统&gt;&gt;;1<br>06104D<br>蒋志军<br>3-6周<br>讲课学时</td>
                </tr>
              </table>
              <table>
                <tr>
                  <th>类型</th><th>课程号</th><th>课程名</th><th>课序号</th><th>教师姓名</th><th>代理人</th><th>学时</th>
                  <th>日期</th><th>周</th><th>星期</th><th>节次</th><th>教室</th>
                  <th>日期</th><th>周</th><th>星期</th><th>节次</th><th>教室</th>
                </tr>
                <tr>
                  <td>调课</td><td>325180</td><td>嵌入式系统</td><td>1</td><td>蒋志军</td><td></td><td>2.0</td>
                  <td>03-26</td><td>3</td><td>周四</td><td>第3、4节</td><td>06104D</td>
                  <td>06-25</td><td>16</td><td>周四</td><td>第1、2节</td><td>06102D</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        val embeddedOriginal = courses.single { it.title == "嵌入式系统" && it.room == "06104D" }
        val embeddedMakeup = courses.single { it.title == "嵌入式系统" && it.room == "06102D" }

        assertTrue(embeddedOriginal.occurrences.all { it.courseId == embeddedOriginal.id })
        assertTrue(embeddedMakeup.occurrences.all { it.courseId == embeddedMakeup.id })
        assertFalse(embeddedOriginal.occurrences.any {
            it.dayOfWeek == 4 &&
                it.startSection == 3 &&
                it.endSection == 4 &&
                it.isActiveInWeek(3) &&
                it.note == "06104D"
        })
        assertTrue(embeddedMakeup.occurrences.any {
            it.dayOfWeek == 4 &&
                it.startSection == 1 &&
                it.endSection == 2 &&
                it.weekText == "第16周" &&
                it.note == "06102D"
        })
    }

    @Test
    fun nanningTimetableMapsSectionsDirectlyWithoutNoonOffset() {
        // 南宁课表无中午时段，第5节应直排为 section 5（非桂林的 section 7）
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第5节<br>14:30<br>┆<br>15:10</th>
                  <td id="1-5" class="center">&lt;&lt;岩土工程测试与监测&gt;&gt;;1<br>5402D<br>潘红艳<br>3-14周<br>课程学时</td>
                  <td id="2-5" class="center">&nbsp;</td><td id="3-5" class="center">&nbsp;</td>
                  <td id="4-5" class="center">&nbsp;</td><td id="5-5" class="center">&nbsp;</td>
                  <td id="6-5" class="center">&nbsp;</td><td id="7-5" class="center">&nbsp;</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        val course = courses.single { it.title == "岩土工程测试与监测" }
        val occ = course.occurrences.single()

        // 南宁第5节应直排为 section 5，不应被 +2 偏移成 section 7
        assertEquals(5, occ.startSection)
        assertEquals(5, occ.endSection)
        assertEquals(1, occ.dayOfWeek)
        assertEquals("5402D", course.room)
        assertEquals("潘红艳", course.teacher)
        assertEquals("3-14周", occ.weekText)
    }

    @Test
    fun nanningTimetableSplitsSameTitleDifferentRoomsIntoDistinctCourses() {
        // 同一单元格内"基础工程"第3-4节，不同教室(6304D/6302D)应拆分为独立课程
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第3节<br>10:25<br>┆<br>11:05</th>
                  <td id="1-3">&nbsp;</td><td id="2-3">&nbsp;</td>
                  <td id="3-3" class="center">
                    &lt;&lt;基础工程&gt;&gt;;3<wbr><wbr><br>6304D<br>毕鹏雁<br>1-12周<br>课程学时<br>
                    &lt;&lt;基础工程&gt;&gt;;3<wbr><wbr><br>6302D<br>毕鹏雁<br>第14周<br>课程学时
                  </td>
                  <td id="4-3">&nbsp;</td><td id="5-3">&nbsp;</td><td id="6-3">&nbsp;</td><td id="7-3">&nbsp;</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第4节<br>11:10<br>┆<br>11:50</th>
                  <td id="1-4">&nbsp;</td><td id="2-4">&nbsp;</td>
                  <td id="3-4" class="center">
                    &lt;&lt;基础工程&gt;&gt;;3<wbr><wbr><br>6304D<br>毕鹏雁<br>1-12周<br>课程学时<br>
                    &lt;&lt;基础工程&gt;&gt;;3<wbr><wbr><br>6302D<br>毕鹏雁<br>第14周<br>课程学时
                  </td>
                  <td id="4-4">&nbsp;</td><td id="5-4">&nbsp;</td><td id="6-4">&nbsp;</td><td id="7-4">&nbsp;</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)

        // 应拆分为两个独立课程，各有不同教室
        val course6304 = courses.single {
            it.title == "基础工程" && it.room == "6304D"
        }
        val course6302 = courses.single {
            it.title == "基础工程" && it.room == "6302D"
        }

        assertEquals("毕鹏雁", course6304.teacher)
        assertEquals("毕鹏雁", course6302.teacher)
        // 6304D 有1-12周的两节连排（第3-4节合并）
        assertEquals(1, course6304.occurrences.size)
        assertEquals(3, course6304.occurrences[0].startSection)
        assertEquals(4, course6304.occurrences[0].endSection)
        assertEquals("1-12周", course6304.occurrences[0].weekText)
        // 6302D 有第14周的两节连排
        assertEquals(1, course6302.occurrences.size)
        assertEquals(3, course6302.occurrences[0].startSection)
        assertEquals(4, course6302.occurrences[0].endSection)
        assertEquals("第14周", course6302.occurrences[0].weekText)
    }

    @Test
    fun nanningTimetableParsesAllCoursesInMultiCourseCell() {
        // 南宁第5节课周一单元格包含4门课，全部应被解析
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第5节<br>14:30<br>┆<br>15:10</th>
                  <td id="1-5" class="center">
                    &lt;&lt;岩土工程勘察实习&gt;&gt;;1<br>6202D<br>王俊璇<br>18-19周<br>课程学时<br>
                    &lt;&lt;形势与政策（四）&gt;&gt;;22<br>8201D<br>俸芳娜<br>12-13周<br>课程学时<br>
                    &lt;&lt;习近平新时代中国特色社会主义思想概论&gt;&gt;;22<br>8201D<br>俸芳娜<br>1-11周<br>课程学时<br>
                    &lt;&lt;思想政治理论课实践教学（四）&gt;&gt;;22<br>8203D<br>俸芳娜<br>14-15周<br>课程学时
                  </td>
                  <td id="2-5">&nbsp;</td><td id="3-5">&nbsp;</td><td id="4-5">&nbsp;</td>
                  <td id="5-5">&nbsp;</td><td id="6-5">&nbsp;</td><td id="7-5">&nbsp;</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        assertEquals(4, courses.size)

        val practice = courses.single { it.title == "思想政治理论课实践教学（四）" }
        assertEquals("8203D", practice.room)
        assertEquals("俸芳娜", practice.teacher)
        assertEquals("14-15周", practice.occurrences.single().weekText)
        assertEquals(5, practice.occurrences.single().startSection)
    }

    @Test
    fun adjustmentRowsSplitMultiWeekOriginalOccurrenceAroundRemovedWeek() {
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第3节<br>10:20<br>┆<br>11:05</th>
                  <td id="1-3">&nbsp;</td><td id="2-3">&nbsp;</td><td id="3-3">&nbsp;</td>
                  <td id="4-3">&lt;&lt;嵌入式系统&gt;&gt;;1<br>06104D<br>蒋志军<br>3-6周<br>讲课学时</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第4节<br>11:15<br>┆<br>12:00</th>
                  <td id="1-4">&nbsp;</td><td id="2-4">&nbsp;</td><td id="3-4">&nbsp;</td>
                  <td id="4-4">&lt;&lt;嵌入式系统&gt;&gt;;1<br>06104D<br>蒋志军<br>3-6周<br>讲课学时</td>
                </tr>
              </table>
              <table>
                <tr>
                  <th>类型</th><th>课程号</th><th>课程名</th><th>课序号</th><th>教师姓名</th><th>代理人</th><th>学时</th>
                  <th>日期</th><th>周</th><th>星期</th><th>节次</th><th>教室</th>
                  <th>日期</th><th>周</th><th>星期</th><th>节次</th><th>教室</th>
                </tr>
                <tr>
                  <td>调课</td><td>325180</td><td>嵌入式系统</td><td>1</td><td>蒋志军</td><td></td><td>2.0</td>
                  <td>03-26</td><td>3</td><td>周四</td><td>第3、4节</td><td>06104D</td>
                  <td>06-25</td><td>16</td><td>周四</td><td>第1、2节</td><td>06102D</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)
        val embeddedOriginal = courses.single { it.title == "嵌入式系统" && it.room == "06104D" }

        assertTrue(embeddedOriginal.occurrences.any { it.isActiveInWeek(4) })
        assertTrue(embeddedOriginal.occurrences.any { it.isActiveInWeek(5) })
        assertTrue(embeddedOriginal.occurrences.any { it.isActiveInWeek(6) })
        assertFalse(embeddedOriginal.occurrences.any { it.isActiveInWeek(3) })
    }

    @Test
    fun nanningMultiRoomSameTitleAcrossDifferentDaysSplitsCorrectly() {
        // 安全生产管理：周三同一单元格3个教室(8208D/6301D/6502D)
        // 基础工程实训：周一四6310D，周二6302D，周三6304D
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第1节<br>08:40<br>┆<br>09:20</th>
                  <td id="1-1" class="center">&lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6310D<br>王俊璇<br>16-17周<br>课程学时</td>
                  <td id="2-1" class="center">&lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6302D<br>王俊璇<br>16-17周<br>课程学时</td>
                  <td id="3-1" class="center">
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>8208D<br>韦有圆<br>7-9周<br>课程学时<br>
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6301D<br>韦有圆<br>10-13周<br>课程学时<br>
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6502D<br>韦有圆<br>第14周<br>课程学时<br>
                    &lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6304D<br>王俊璇<br>16-17周<br>课程学时
                  </td>
                  <td id="4-1" class="center">&lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6310D<br>王俊璇<br>16-17周<br>课程学时</td>
                  <td id="5-1" class="center">&nbsp;</td><td id="6-1" class="center">&nbsp;</td><td id="7-1" class="center">&nbsp;</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第2节<br>09:25<br>┆<br>10:05</th>
                  <td id="1-2" class="center">&lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6310D<br>王俊璇<br>16-17周<br>课程学时</td>
                  <td id="2-2" class="center">&lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6302D<br>王俊璇<br>16-17周<br>课程学时</td>
                  <td id="3-2" class="center">
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>8208D<br>韦有圆<br>7-9周<br>课程学时<br>
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6301D<br>韦有圆<br>10-13周<br>课程学时<br>
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6502D<br>韦有圆<br>第14周<br>课程学时<br>
                    &lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6304D<br>王俊璇<br>16-17周<br>课程学时
                  </td>
                  <td id="4-2" class="center">&lt;&lt;基础工程实训&gt;&gt;<wbr><wbr>;3<br>6310D<br>王俊璇<br>16-17周<br>课程学时</td>
                  <td id="5-2" class="center">&nbsp;</td><td id="6-2" class="center">&nbsp;</td><td id="7-2" class="center">&nbsp;</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)

        // 安全生产管理：3个不同教室 → 3门独立课程
        val anquan8208 = courses.single {
            it.title == "安全生产管理" && it.room == "8208D"
        }
        val anquan6301 = courses.single {
            it.title == "安全生产管理" && it.room == "6301D"
        }
        val anquan6502 = courses.single {
            it.title == "安全生产管理" && it.room == "6502D"
        }
        assertEquals("韦有圆", anquan8208.teacher)
        assertEquals("7-9周", anquan8208.occurrences.single().weekText)
        assertEquals(1, anquan8208.occurrences.single().startSection)
        assertEquals(2, anquan8208.occurrences.single().endSection)
        assertEquals("韦有圆", anquan6301.teacher)
        assertEquals("10-13周", anquan6301.occurrences.single().weekText)
        assertEquals("韦有圆", anquan6502.teacher)
        assertEquals("第14周", anquan6502.occurrences.single().weekText)
        assertEquals("6502D", anquan6502.room)

        // 基础工程实训：3个不同教室 → 3门独立课程
        val shixun6310 = courses.single {
            it.title == "基础工程实训" && it.room == "6310D"
        }
        val shixun6302 = courses.single {
            it.title == "基础工程实训" && it.room == "6302D"
        }
        val shixun6304 = courses.single {
            it.title == "基础工程实训" && it.room == "6304D"
        }
        // 6310D: 周一+周四的第1-2节
        assertEquals(2, shixun6310.occurrences.size)
        assertTrue(shixun6310.occurrences.any {
            it.dayOfWeek == 1 && it.startSection == 1 && it.endSection == 2
        })
        assertTrue(shixun6310.occurrences.any {
            it.dayOfWeek == 4 && it.startSection == 1 && it.endSection == 2
        })
        // 6302D: 周二第1-2节
        assertEquals(1, shixun6302.occurrences.size)
        assertEquals(2, shixun6302.occurrences[0].dayOfWeek)
        // 6304D: 周三第1-2节
        assertEquals(1, shixun6304.occurrences.size)
        assertEquals(3, shixun6304.occurrences[0].dayOfWeek)
        assertEquals("6304D", shixun6304.room)
    }

    @Test
    fun nanningCrossDaySameTitleDifferentRoomsStaySeparate() {
        // 安全生产管理: 周二第7-8节@6304D + 周三第1-2节@6502D(第14周)
        // 两者虽然同名同师但教室不同，应保持独立
        val html = """
            <html><body>
              <table id="timetable" class="infolist_hr">
                <tr><th>&nbsp;</th><th>周一</th><th>周二</th><th>周三</th><th>周四</th><th>周五</th><th>周六</th><th>周日</th></tr>
                <tr class="infolist_hr_common">
                  <th>第1节<br>08:40<br>┆<br>09:20</th>
                  <td id="1-1">&nbsp;</td><td id="2-1">&nbsp;</td>
                  <td id="3-1" class="center">
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6502D<br>韦有圆<br>第14周<br>课程学时
                  </td>
                  <td id="4-1">&nbsp;</td><td id="5-1">&nbsp;</td><td id="6-1">&nbsp;</td><td id="7-1">&nbsp;</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第2节<br>09:25<br>┆<br>10:05</th>
                  <td id="1-2">&nbsp;</td><td id="2-2">&nbsp;</td>
                  <td id="3-2" class="center">
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6502D<br>韦有圆<br>第14周<br>课程学时
                  </td>
                  <td id="4-2">&nbsp;</td><td id="5-2">&nbsp;</td><td id="6-2">&nbsp;</td><td id="7-2">&nbsp;</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第7节<br>16:05<br>┆<br>16:45</th>
                  <td id="1-7">&nbsp;</td>
                  <td id="2-7" class="center">
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6304D<br>韦有圆<br>7-14周<br>课程学时
                  </td>
                  <td id="3-7">&nbsp;</td><td id="4-7">&nbsp;</td><td id="5-7">&nbsp;</td><td id="6-7">&nbsp;</td><td id="7-7">&nbsp;</td>
                </tr>
                <tr class="infolist_hr_common">
                  <th>第8节<br>16:50<br>┆<br>17:30</th>
                  <td id="1-8">&nbsp;</td>
                  <td id="2-8" class="center">
                    &lt;&lt;安全生产管理&gt;&gt;<wbr><wbr>;2<br>6304D<br>韦有圆<br>7-14周<br>课程学时
                  </td>
                  <td id="3-8">&nbsp;</td><td id="4-8">&nbsp;</td><td id="5-8">&nbsp;</td><td id="6-8">&nbsp;</td><td id="7-8">&nbsp;</td>
                </tr>
              </table>
            </body></html>
        """.trimIndent()

        val courses = parser.parsePersonalSchedule(html)

        // 两个不同教室 → 两门独立课程
        val wedCourse = courses.single {
            it.title == "安全生产管理" && it.room == "6502D"
        }
        val tueCourse = courses.single {
            it.title == "安全生产管理" && it.room == "6304D"
        }

        // 周三的课在第1-2节
        assertEquals(3, wedCourse.occurrences.single().dayOfWeek)
        assertEquals(1, wedCourse.occurrences.single().startSection)
        assertEquals(2, wedCourse.occurrences.single().endSection)
        assertEquals("第14周", wedCourse.occurrences.single().weekText)

        // 周二的课在第7-8节
        assertEquals(2, tueCourse.occurrences.single().dayOfWeek)
        assertEquals(7, tueCourse.occurrences.single().startSection)
        assertEquals(8, tueCourse.occurrences.single().endSection)
        assertEquals("7-14周", tueCourse.occurrences.single().weekText)

        // 确认没有6304D出现在周三
        assertTrue(wedCourse.occurrences.none { it.dayOfWeek == 2 })
        assertTrue(tueCourse.occurrences.none { it.dayOfWeek == 3 })
    }

    @Test
    fun multiRowAdjustmentsKeepDistinctIdsWhenWeeksAndWeekdaysMatch() {
        val html = """
            <html><body><table>
              <tr>
                <th>类型</th><th>课程号</th><th>课程名</th><th>课序号</th><th>教师姓名</th><th>代课人</th><th>学时</th>
                <th>日期</th><th>周</th><th>星期</th><th>节次</th><th>教室</th>
                <th>日期</th><th>周</th><th>星期</th><th>节次</th><th>教室</th>
              </tr>
              <tr>
                <td rowspan="2">调课</td><td rowspan="2">360730</td><td rowspan="2">C语言程序设计</td><td rowspan="2">5</td>
                <td rowspan="2">杨呈永</td><td rowspan="2"></td><td rowspan="2">4.0</td>
                <td>12-25</td><td>17</td><td>周三</td><td>第7、8节</td><td>014104J</td>
                <td>12-27</td><td>17</td><td>周五</td><td>第7、8节</td><td>014104J</td>
              </tr>
              <tr>
                <td>12-25</td><td>17</td><td>周三</td><td>第5、6节</td><td>014202J</td>
                <td>12-27</td><td>17</td><td>周五</td><td>第5、6节</td><td>014202J</td>
              </tr>
            </table></body></html>
        """.trimIndent()

        val adjustments = parser.parseAdjustments(html)

        assertEquals(2, adjustments.size)
        assertEquals(2, adjustments.map { it.id }.distinct().size)
        assertEquals(setOf(5, 7), adjustments.map { it.originalStartSection }.toSet())
        assertEquals(setOf(5, 7), adjustments.map { it.makeupStartSection }.toSet())
    }
}

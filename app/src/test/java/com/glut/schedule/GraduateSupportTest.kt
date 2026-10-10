package com.glut.schedule

import com.glut.schedule.data.model.GRADUATE_SECTION_COUNT
import com.glut.schedule.data.model.HiddenCardScope
import com.glut.schedule.data.model.HiddenCourseRule
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicWeeksForText
import com.glut.schedule.data.model.buildScheduleRefreshDiff
import com.glut.schedule.data.model.defaultClassPeriods
import com.glut.schedule.data.model.graduateClassPeriods
import com.glut.schedule.data.model.hasConfiguredClassTimes
import com.glut.schedule.data.model.hiddenCourseKey
import com.glut.schedule.data.model.hiddenRuleScopeText
import com.glut.schedule.data.model.isReadOnly
import com.glut.schedule.data.model.sectionSpanLabel
import com.glut.schedule.data.model.validateClassPeriods
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ClassPeriodProfile
import com.glut.schedule.data.settings.GuilinSubCampus
import com.glut.schedule.data.settings.ImportSource
import com.glut.schedule.data.settings.MenuCapability
import com.glut.schedule.data.settings.capabilitiesFor
import com.glut.schedule.data.settings.classPeriodProfile
import com.glut.schedule.data.settings.decodeClassPeriodOverrides
import com.glut.schedule.service.graduate.GraduateParser
import com.glut.schedule.service.graduate.GraduatePortalContext
import com.glut.schedule.service.graduate.GraduateProtocol
import com.glut.schedule.service.graduate.GraduateSessionExpiredException
import com.glut.schedule.ui.navigation.DrawerItem
import com.glut.schedule.ui.navigation.isEnabledFor
import com.glut.schedule.ui.navigation.unsupportedHintFor
import com.glut.schedule.ui.pages.graduateImportCompletionMessage
import com.glut.schedule.ui.pages.shouldClearAcademicData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 研究生来源的单元测试。
 *
 * 全部输入都是**合成数据**（课程名、教师、教室一律是「合成/测试」字样），
 * 不含任何真实账号、Cookie、课程或个人响应——调研产物不入库是硬性约束。
 *
 * 课表夹具与小程序端共用同一份合成输入（`tests/fixtures/graduate-schedule.synthetic.json`），
 * 两端各自实现解析器后比对结果，防止一侧悄悄跑偏。
 */
class GraduateSupportTest {

    private val syntheticRows = """
        [
          {"JieCi":"1,2","Time":"","Monday":"\r\n合成课程甲\r\n测试教师甲\r\n节次:1,2\r\n周次:1-4;6-8\r\n地点:测试教室A\r\n开课院系:测试院系\r\n电话:\r\n\r\n合成课程乙\r\n测试教师乙\r\n节次:1,2\r\n周次:10-14(双)\r\n地点:测试教室B\r\n","Tuesday":"","Wednesday":"","Thursday":"","Friday":"","Saturday":"","Sunday":""},
          {"JieCi":"3,4","Time":"","Monday":"","Tuesday":"合成跨节课程\r\n测试教师丙\r\n节次:3,4,5,6\r\n周次:2,5,8\r\n地点:测试教室C","Wednesday":"","Thursday":"","Friday":"","Saturday":"","Sunday":""},
          {"JieCi":"5,6","Time":"","Monday":"","Tuesday":"合成跨节课程\r\n测试教师丙\r\n节次:3,4,5,6\r\n周次:2,5,8\r\n地点:测试教室C","Wednesday":"","Thursday":"","Friday":"","Saturday":"","Sunday":""},
          {"JieCi":"11,12","Time":"","Monday":"","Tuesday":"","Wednesday":"","Thursday":"","Friday":"","Saturday":"","Sunday":"合成晚间课程\r\n\r\n节次:11,12\r\n周次:3-9(单)\r\n地点:"}
        ]
    """.trimIndent()

    // ---- 课表解析 ----

    @Test
    fun parsesSharedSyntheticFixtureWithoutAnyNoonOffset() {
        val courses = GraduateParser.schedule(syntheticRows).associateBy { it.title }

        assertEquals(4, courses.size)

        val first = courses.getValue("合成课程甲")
        assertEquals("测试教师甲", first.teacher)
        assertEquals("测试教室A", first.room)
        val firstOccurrence = first.occurrences.single()
        assertEquals(1, firstOccurrence.dayOfWeek)
        assertEquals(1, firstOccurrence.startSection)
        assertEquals(2, firstOccurrence.endSection)
        assertEquals(listOf(1, 2, 3, 4, 6, 7, 8), academicWeeksForText(firstOccurrence.weekText))

        // 括号必须被去掉：课时统计的周次校验不接受「(双)」，留着会让整学期统计不可用。
        val second = courses.getValue("合成课程乙")
        assertEquals("10-14双", second.occurrences.single().weekText)
        assertEquals(listOf(10, 12, 14), academicWeeksForText(second.occurrences.single().weekText))

        // 跨大节：同一门课分别写在「3,4」「5,6」两行，标签写的是 3,4,5,6，必须合并成一个区间，
        // 而且重复的那一份要被去重，不能变成两个课次。
        val cross = courses.getValue("合成跨节课程")
        assertEquals(1, cross.occurrences.size)
        val crossOccurrence = cross.occurrences.single()
        assertEquals(2, crossOccurrence.dayOfWeek)
        assertEquals(3, crossOccurrence.startSection)
        assertEquals(6, crossOccurrence.endSection)
        assertEquals(listOf(2, 5, 8), academicWeeksForText(crossOccurrence.weekText))

        // 缺字段：没有教师行、地点为空，都要允许，而不是整条丢弃。
        val evening = courses.getValue("合成晚间课程")
        assertEquals("", evening.teacher)
        assertEquals("", evening.room)
        assertEquals(7, evening.occurrences.single().dayOfWeek)
        assertEquals(11, evening.occurrences.single().startSection)
        assertEquals(12, evening.occurrences.single().endSection)
        assertEquals(listOf(3, 5, 7, 9), academicWeeksForText(evening.occurrences.single().weekText))
    }

    @Test
    fun nonContiguousSectionsBecomeSeparateOccurrences() {
        val courses = GraduateParser.schedule(
            """[{"JieCi":"1,2","Monday":"合成课程\r\n测试教师\r\n节次:1,7\r\n周次:1-4\r\n地点:测试教室"}]"""
        )
        val spans = courses.single().occurrences.map { it.startSection to it.endSection }
        assertEquals(listOf(1 to 1, 7 to 7), spans)
    }

    /**
     * 单条记录读不懂时节次/周次**跳过该条并计数**，绝不静默当成「全周都有课」，
     * 也绝不因为它中止整次导入——一个坏单元格不该让首次导入的用户拿到空 App。
     */
    @Test
    fun unreadableRecordsAreSkippedAndCountedWhileTheRestStillImports() {
        val result = GraduateParser.scheduleWithDiagnostics(
            """[
              {"JieCi":"1,2","Monday":"合成课程甲\r\n测试教师甲\r\n节次:1,2\r\n周次:1-4\r\n地点:测试教室A",
                          "Tuesday":"合成待定课程\r\n测试教师乙\r\n节次:1,2\r\n周次:待定\r\n地点:测试教室B",
                          "Wednesday":"合成越界课程\r\n测试教师丙\r\n节次:13,14\r\n周次:1-4\r\n地点:测试教室C"}
            ]"""
        )
        // 好的那条照常导入，坏的两条被计数跳过。
        assertEquals(listOf("合成课程甲"), result.courses.map { it.title })
        assertEquals(2, result.skippedCount)
    }

    /** 门户给了课程块却一条都读不出来 ⇒ 格式变了，必须中止，而不是写一张空课表覆盖缓存。 */
    @Test
    fun abortOnlyWhenNotASingleRecordCanBeParsed() {
        val error = assertThrows(IllegalStateException::class.java) {
            GraduateParser.schedule("""[{"Monday":"课程\n教师\n节次:1,2\n周次:待定"}]""")
        }
        assertTrue("错误信息应带出跳过条数：${error.message}", error.message!!.contains("1 条"))
    }

    /** 门户真的没有排课单元格时不算「解析失败」，交给上层按「未获取到课程」处理。 */
    @Test
    fun emptyCellsProduceAnEmptyResultWithoutFailing() {
        val result = GraduateParser.scheduleWithDiagnostics("""[{"JieCi":"1,2","Monday":"","Tuesday":""}]""")
        assertTrue(result.courses.isEmpty())
        assertEquals(0, result.skippedCount)
    }

    @Test
    fun fallsBackToRowJieCiWhenBlockHasNoSectionLabel() {
        val courses = GraduateParser.schedule(
            """[{"JieCi":"5,6","Monday":"合成课程\r\n测试教师\r\n周次:1-4\r\n地点:测试教室"}]"""
        )
        val occurrence = courses.single().occurrences.single()
        assertEquals(5, occurrence.startSection)
        assertEquals(6, occurrence.endSection)
    }

    /**
     * 「标题独占一行、空行之后才跟标签」的写法必须解析成一门课。
     *
     * 只按空行切分时这里会拆成「只有标题」和「只有标签」两条废记录，整门课消失；
     * 合成夹具里的晚间课程就是这个形态。
     */
    @Test
    fun titleOnItsOwnLineFollowedByBlankLineStillYieldsOneCourse() {
        val courses = GraduateParser.schedule(
            """[{"JieCi":"11,12","Monday":"合成课程\r\n\r\n节次:11,12\r\n周次:3-9(单)\r\n地点:测试教室"}]"""
        )
        assertEquals(1, courses.size)
        val course = courses.single()
        assertEquals("合成课程", course.title)
        assertEquals("", course.teacher)
        assertEquals("测试教室", course.room)
        assertEquals(listOf(3, 5, 7, 9), academicWeeksForText(course.occurrences.single().weekText))
    }

    /**
     * 两门课之间**没有空行**时也必须切开。
     *
     * 按空行切分会把第二门课整门并进第一门（标签只取第一次出现），静默丢课；
     * 按行扫描时「周次」之后遇到的第一个非标签行就是下一门课的开头。
     */
    @Test
    fun recordsAreSplitEvenWithoutBlankLineBetweenCourses() {
        val courses = GraduateParser.schedule(
            """[{"JieCi":"1,2","Monday":"合成课程甲\r\n测试教师甲\r\n节次:1,2\r\n周次:1-4\r\n地点:测试教室A\r\n合成课程乙\r\n测试教师乙\r\n节次:3,4\r\n周次:5-8\r\n地点:测试教室B"}]"""
        )
        assertEquals(setOf("合成课程甲", "合成课程乙"), courses.map { it.title }.toSet())
        val first = courses.first { it.title == "合成课程甲" }.occurrences.single()
        val second = courses.first { it.title == "合成课程乙" }.occurrences.single()
        assertEquals(1 to 2, first.startSection to first.endSection)
        assertEquals(3 to 4, second.startSection to second.endSection)
        assertEquals(listOf(1, 2, 3, 4), academicWeeksForText(first.weekText))
        assertEquals(listOf(5, 6, 7, 8), academicWeeksForText(second.weekText))
    }

    /** 教师既可能单占一行，也可能写成 `教师: 张三`；两种都要认。 */
    @Test
    fun teacherIsReadFromEitherAPlainLineOrATeacherLabel() {
        val plain = GraduateParser.schedule(
            """[{"Monday":"合成课程\r\n测试教师\r\n节次:1,2\r\n周次:1-4"}]"""
        ).single()
        assertEquals("测试教师", plain.teacher)

        val labelled = GraduateParser.schedule(
            """[{"Monday":"合成课程\r\n教师:测试教师\r\n节次:1,2\r\n周次:1-4"}]"""
        ).single()
        assertEquals("测试教师", labelled.teacher)
    }

    /**
     * 周次无法识别必须明确报错，绝不能静默当作「全周都有课」。
     *
     * 单条坏记录会被跳过并计数；一条都解析不出来时就中止（这里是「一条都读不出」的情形）。
     */
    @Test
    fun rejectsUnrecognizedWeeksInsteadOfDefaultingToAllWeeks() {
        assertThrows(IllegalStateException::class.java) {
            GraduateParser.schedule("""[{"Monday":"课程\n教师\n节次:1,2\n周次:待定"}]""")
        }
        assertThrows(IllegalStateException::class.java) {
            GraduateParser.schedule("""[{"Monday":"课程\n教师\n节次:1,2"}]""")
        }
    }

    @Test
    fun rejectsSectionsOutsideTheTwelveGraduateSlots() {
        assertThrows(IllegalStateException::class.java) {
            GraduateParser.schedule("""[{"Monday":"课程\n教师\n节次:13,14\n周次:1-4"}]""")
        }
    }

    @Test
    fun sectionParserAcceptsCommonPortalWritings() {
        assertEquals(listOf(5, 6), GraduateParser.parseSections("5,6"))
        assertEquals(listOf(3, 4, 5, 6), GraduateParser.parseSections("3-6"))
        assertEquals(listOf(1, 2, 3), GraduateParser.parseSections("第1,2、3节"))
        assertEquals(emptyList<Int>(), GraduateParser.parseSections(""))
        assertEquals(emptyList<Int>(), GraduateParser.parseSections("待定"))
        // 真实门户带「节」后缀与尾随空格。
        assertEquals(listOf(1, 2), GraduateParser.parseSections("1,2节 "))
    }

    /**
     * 真实门户的周次会在括号里挂任课教师名。
     *
     * 括号对周次无语义，必须整块剥掉；不剥的后果不是少算几周，而是正则整体失配、
     * **这条课程被当成异常整条丢弃**。`(单)` / `(双)` 是语义，必须保留。
     * 期望值与小程序端实测输出一致。
     */
    @Test
    fun weekTextStripsTeacherNamesButKeepsOddEvenSemantics() {
        assertEquals("2-3", GraduateParser.normalizeWeeks("2-3"))
        assertEquals(listOf(2, 3), academicWeeksForText(GraduateParser.normalizeWeeks("2-3")))

        val twoTeachers = GraduateParser.normalizeWeeks("4-6(黄发国);7-11(潘家芳)")
        assertEquals("4-6,7-11", twoTeachers)
        assertEquals(
            listOf(4, 5, 6, 7, 8, 9, 10, 11),
            academicWeeksForText(twoTeachers)
        )

        assertEquals("9-9", GraduateParser.normalizeWeeks("9-9(陈良骥)"))
        assertEquals(listOf(9), academicWeeksForText(GraduateParser.normalizeWeeks("9-9(陈良骥)")))

        // (单)/(双) 是语义：保留内容、去掉括号（课时统计的周次校验不接受括号）。
        assertEquals("10-14双", GraduateParser.normalizeWeeks("10-14(双)"))
        assertEquals(listOf(10, 12, 14), academicWeeksForText(GraduateParser.normalizeWeeks("10-14(双)")))
        assertEquals(listOf(3, 5, 7, 9), academicWeeksForText(GraduateParser.normalizeWeeks("3-9(单)")))

        // 全角括号同样处理。
        assertEquals("5-8", GraduateParser.normalizeWeeks("5-8（张三）"))
        // 读不懂的仍然读不懂，保持严格。
        assertEquals("待定", GraduateParser.normalizeWeeks("待定"))
        assertTrue(academicWeeksForText(GraduateParser.normalizeWeeks("待定")).isEmpty())
    }

    /** 抓包原文的整格文本必须完整解析出来，不能被教师名连累丢掉。 */
    @Test
    fun realCapturedCellWithTeacherNamesParsesIntoOneCourse() {
        val raw = """[{"JieCi":"1,2","Monday":"工程实例与分析\r\n2026机械工程、能源动力专硕班(一班多师)\r\n节次:1,2节 \r\n周次:4-6(黄发国);7-11(潘家芳)\r\n地点:018A305D\r\n开课院系:机械与控制工程学院\r\n电话:"}]"""
        val result = GraduateParser.scheduleWithDiagnostics(raw)
        assertEquals(0, result.skippedCount)
        val course = result.courses.single()
        assertEquals("工程实例与分析", course.title)
        assertEquals("018A305D", course.room)
        val occurrence = course.occurrences.single()
        assertEquals(1, occurrence.startSection)
        assertEquals(2, occurrence.endSection)
        assertEquals(listOf(4, 5, 6, 7, 8, 9, 10, 11), academicWeeksForText(occurrence.weekText))
    }

    // ---- 学期映射 ----

    @Test
    fun autumnTermMapsToStartYearAndSpringTermToTheNextYear() {
        val autumn = GraduateParser.catalog("2026-2027", "1", "2025", LocalDate.of(2026, 9, 7))
        val current = autumn.first { it.isCurrent }
        assertEquals("graduate:2026:autumn", current.id)
        assertEquals("2026-2027", current.portalYearId)
        assertEquals("1", current.portalTermId)
        assertEquals(SemesterSeason.AUTUMN, current.season)
        assertEquals(LocalDate.of(2026, 9, 7), current.semesterStartDate)

        val spring = GraduateParser.catalog("2026-2027", "2", "2025", LocalDate.of(2027, 3, 1))
        val springCurrent = spring.first { it.isCurrent }
        assertEquals("graduate:2027:spring", springCurrent.id)
        assertEquals("2026-2027", springCurrent.portalYearId)
        assertEquals("2", springCurrent.portalTermId)
        assertEquals(SemesterSeason.SPRING, springCurrent.season)
        assertEquals(LocalDate.of(2027, 3, 1), springCurrent.semesterStartDate)
    }

    /** 历史学期只有门户原始参数，不得臆造开学日期。 */
    @Test
    fun historicalSemestersCarryNoInventedCalendar() {
        val catalog = GraduateParser.catalog("2026-2027", "1", "2025", LocalDate.of(2026, 9, 7))
        val historical = catalog.filter { !it.isCurrent }
        assertTrue(historical.isNotEmpty())
        assertTrue(historical.all { it.semesterStartDate == null && it.semesterEndDate == null })
        // 门户学年参数必须原样保留，不能被本专科的编号规则改写。
        assertTrue(historical.all { Regex("""20\d{2}-20\d{2}""").matches(it.portalYearId) })
        assertTrue(historical.all { it.portalTermId in setOf("1", "2") })
    }

    /**
     * I1：目录**不得**凭空多出未来学年。
     *
     * 门户当前学年就是最新的一年；从 `start + 1` 起会多造出 2027-2028 与 2028-2029，
     * 它们既不存在于门户，又会被当成「历史学期」渲染出来，点下载必然失败。
     * 这里断言上界，而不只是格式——只校验 `20\d{2}-20\d{2}` 抓不到这个 bug。
     */
    @Test
    fun catalogNeverInventsFutureAcademicYears() {
        listOf("1" to "2026-2027", "2" to "2026-2027").forEach { (term, year) ->
            val catalog = GraduateParser.catalog(year, term, "2025", LocalDate.of(2026, 9, 7))
            val starts = catalog.map { it.portalYearId.substringBefore('-').toInt() }
            assertTrue(
                "term=$term 的目录起始年不得超过门户起始年 2026：$starts",
                starts.all { it <= 2026 }
            )
            // 当前学期必须落在门户给出的学年内。
            val current = catalog.first { it.isCurrent }
            assertEquals(year, current.portalYearId)
        }
        // 学年上界就是门户学年：2026-2027 必须是目录里最新的一年。
        val autumn = GraduateParser.catalog("2026-2027", "1", "2025", LocalDate.of(2026, 9, 7))
        assertEquals("2026-2027", autumn.first().portalYearId)
    }

    /** 完全识别不了的课程名也算「这条读不懂」：跳过并计数，一条都读不出时才中止。 */
    @Test
    fun blockWithoutAnyPlainLineIsSkippedAndCounted() {
        val result = GraduateParser.scheduleWithDiagnostics(
            """[{"Monday":"节次:1,2\r\n周次:1-4","Tuesday":"合成课程\r\n测试教师\r\n节次:1,2\r\n周次:1-4"}]"""
        )
        assertEquals(listOf("合成课程"), result.courses.map { it.title })
        assertEquals(1, result.skippedCount)
    }

    /**
     * M2：`节次:` 标签存在但读不懂时必须算作「读不懂」。
     *
     * 退回行级 `JieCi` 只允许发生在**标签缺失**时——标签存在却读不懂意味着门户改了格式，
     * 静默退回会把「解析失败」伪装成「解析成功」。
     */
    @Test
    fun unreadableSectionLabelIsNotRescuedByRowJieCi() {
        val result = GraduateParser.scheduleWithDiagnostics(
            """[{"JieCi":"1,2","Monday":"合成课程\r\n测试教师\r\n节次:待定\r\n周次:1-4",
                          "Tuesday":"合成课程乙\r\n测试教师乙\r\n节次:3,4\r\n周次:1-4"}]"""
        )
        // 若静默退回行 JieCi，第一条会被当成 1-2 节混进结果里。
        assertEquals(listOf("合成课程乙"), result.courses.map { it.title })
        assertEquals(1, result.skippedCount)
    }

    @Test
    fun catalogRejectsGarbagePortalSemester() {
        assertThrows(IllegalArgumentException::class.java) {
            GraduateParser.catalog("not-a-year", "1", "", null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            GraduateParser.catalog("2026-2027", "3", "", null)
        }
    }

    @Test
    fun portalDateParsesAspNetDateLiteralInChinaTime() {
        assertEquals(LocalDate.of(2027, 1, 19), GraduateParser.portalDate("/Date(1800288000000+0800)/"))
        assertNull(GraduateParser.portalDate(""))
        assertNull(GraduateParser.portalDate("2026-09-07"))
    }

    /**
     * 门户真实校历（抓包实测）：`CurrentTermFrom=/Date(1788710400000+0800)/` → 2026-09-07（周一），
     * `CurrentTermEnd=/Date(1799942400000+0800)/` → 2027-01-15（周五）。
     *
     * 关键：结束日**不做周一对齐**——对齐会把学期提前到 2027-01-11。
     * 教学周上界由 `TeachWeekEnd` 给出（19），与日历推算一致。
     */
    @Test
    fun realPortalCalendarIsParsedWithoutMondayAlignment() {
        val context = GraduatePortalContext(
            year = "2026-2027",
            term = "1",
            currentTermFrom = "/Date(1788710400000+0800)/",
            currentTermEnd = "/Date(1799942400000+0800)/",
            teachWeekEnd = "19",
            studentId = "synthetic",
            grade = "2025",
            name = "合成用户"
        )
        val start = context.currentSemesterStartMonday
        val end = context.currentSemesterEndDate
        assertEquals(LocalDate.of(2026, 9, 7), start)
        assertEquals(LocalDate.of(2027, 1, 15), end)
        // 2026-09-07 本身就是周一；2027-01-15 是周五，绝不能被对齐成周一。
        assertEquals(java.time.DayOfWeek.MONDAY, start!!.dayOfWeek)
        assertEquals(java.time.DayOfWeek.FRIDAY, end!!.dayOfWeek)
        assertEquals(19, context.teachWeekEndValue)
        // 日历推算的周数与门户 TeachWeekEnd 一致。
        assertEquals(19, academicMaxWeekForCalendar(start, end))
    }

    /** 门户没给校历时两个字段都为 null，调用方据此「一个日期都不写」。 */
    @Test
    fun missingPortalCalendarYieldsNullsInsteadOfGuesses() {
        val context = GraduatePortalContext(
            year = "2026-2027",
            term = "1",
            currentTermFrom = "",
            currentTermEnd = "",
            teachWeekEnd = "",
            studentId = "synthetic",
            grade = "2025",
            name = "合成用户"
        )
        assertNull(context.currentSemesterStartMonday)
        assertNull(context.currentSemesterEndDate)
        assertNull(context.teachWeekEndValue)
    }

    // ---- 协议 ----

    /**
     * `Y-sign` 的黄金样本：**4 组真实抓包值**（由负责人从真实会话读取；
     * `cft` 是会话级 hash，仅作测试向量，不涉及凭据）。
     *
     * 签名输入 = 整条请求 URL 的查询参数（含 4 个 `Y-*`）叠加 payload。
     * 旧实现只签「模板自己的参数」，四组全部对不上，服务端会拒绝每一个网关请求——
     * 这正是研究生功能一步都走不通的根因。
     */
    @Test
    fun signatureMatchesRealCapturedGoldenVectors() {
        val cft = "5c4be457329650f7d15c27a994ace816"
        fun urlFor(template: String, method: String, menu: String) =
            "https://gsems.glut.edu.cn/Ajax.ashx?Y-UriTemplate=$template" +
                "&Y-Method=$method&Y-menuid=$menu&Y-cft=$cft"

        assertEquals(
            "a332e9690776c3df1245dbc72dc76677",
            GraduateProtocol.sign(urlFor("UserService/GetUniversityInfo", "GET", ""), emptyMap())
        )
        assertEquals(
            "ca592199692612c72b63ffbcd2d41411",
            GraduateProtocol.sign(urlFor("RedisService/IsRedis", "GET", ""), emptyMap())
        )
        assertEquals(
            "c29dc06a16786efcf56fd3fa9722e030",
            GraduateProtocol.sign(urlFor("UserService/getLoggedInUser/", "POST", ""), emptyMap())
        )
        assertEquals(
            "c3f57b4b4b49e77838151e2640727fc9",
            GraduateProtocol.sign(urlFor("UserService/getEmailAndTel", "GET", ""), emptyMap())
        )
    }

    /**
     * 带业务参数的场景。这条不是抓包值，而是按门户 `DataSign` 的算法独立推导出来的，
     * 用来钉住「payload 也参与签名」以及「旧实现的错值不会回来」。
     */
    @Test
    fun signatureIncludesBusinessPayloadAndIsNotTheOldWrongValue() {
        val url = "https://gsems.glut.edu.cn/Ajax.ashx" +
            "?Y-UriTemplate=TimeTableNewService/GetTimeTableByStudent" +
            "/%7BYear%7D/%7BTerm%7D/%7BXH%7D%3FIsConti%3D%7BIsConti%7D" +
            "&Y-Method=GET&Y-menuid=6224&Y-cft=5c4be457329650f7d15c27a994ace816"
        val payload = mapOf(
            "Year" to GraduateProtocol.encode("2026-2027"),
            "Term" to GraduateProtocol.encode("1"),
            "XH" to GraduateProtocol.encode("20251234"),
            "IsConti" to GraduateProtocol.encode("0")
        )
        assertEquals("0bf9eb55e900f4f259b724425fb7a8dc", GraduateProtocol.sign(url, payload))
        // 旧实现（只签模板自身参数、不含 Y-*）算出的错值，必须不再出现。
        assertFalse(
            "签名退回了旧的错误口径",
            GraduateProtocol.sign(url, payload) == "3346696c1ce2902f29496a34875dcab3"
        )
        // 业务参数确实参与签名：换一个值签名就变。
        assertFalse(
            GraduateProtocol.sign(url, payload) ==
                GraduateProtocol.sign(url, payload + ("IsConti" to GraduateProtocol.encode("1")))
        )
    }

    /** `Y-UriTemplate` 走门户的 `escape()`：保留 `/`，编码 `?` `=` `{` `}`。 */
    @Test
    fun uriTemplateUsesEscapeSemanticsNotUrlEncoder() {
        assertEquals(
            "TimeTableNewService/GetTimeTableByStudent/%7BYear%7D/%7BTerm%7D/%7BXH%7D%3FIsConti%3D%7BIsConti%7D",
            GraduateProtocol.escapeTemplate(
                "TimeTableNewService/GetTimeTableByStudent/{Year}/{Term}/{XH}?IsConti={IsConti}"
            )
        )
        // `URLEncoder` 会把 `/` 也编码成 %2F，那是错的。
        assertTrue(GraduateProtocol.escapeTemplate("UserService/GetUniversityInfo").contains("/"))
        assertEquals("GET", GraduateProtocol.escapeTemplate("GET"))
    }

    @Test
    fun gatewayValuesAreBase64EncodedOnceAndAlreadyEncodedValuesPassThrough() {
        assertEquals("encode_MjAyNi0yMDI3", GraduateProtocol.encode("2026-2027"))
        assertEquals("encode_JUU3JUFDJUFDMSVFNSVBRCVBNiVFNiU5QyU5Rg==", GraduateProtocol.encode("第1学期"))
        assertEquals("encode_YWJj", GraduateProtocol.encode("encode_YWJj"))
        assertEquals("", GraduateProtocol.encode(""))
    }

    @Test
    fun rsaEncryptionHandlesMultiBlockChinesePasswords() {
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
        val publicKey = java.util.Base64.getEncoder().encodeToString(keyPair.public.encoded)
        val password = "研究生a".repeat(40)
        val encrypted = java.util.Base64.getDecoder().decode(GraduateProtocol.encryptPassword(password, publicKey))
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        val decrypted = StringBuilder()
        var offset = 0
        while (offset < encrypted.size) {
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keyPair.private)
            decrypted.append(String(cipher.doFinal(encrypted.copyOfRange(offset, offset + 128)), Charsets.UTF_8))
            offset += 128
        }
        // 按字符切块，中文不会被劈成半个 UTF-8 字符。
        assertEquals(password, decrypted.toString())
    }

    // ---- 成绩与培养计划 ----

    @Test
    fun scoresKeepRealMarksAndNeverInventGpa() {
        val scores = GraduateParser.scores(
            """{"items":[{"CourseName":"合成课程","Year":"2026-2027","Term":"1","TotalMark":"85","Credit":"2","CourseTypeName":"学位课"}]}"""
        )
        val score = scores.single()
        assertEquals("合成课程", score.courseName)
        assertEquals("85", score.score)
        assertEquals(2.0, score.credit, 0.0001)
        // 门户不给绩点，不得套用本科公式推算。
        assertEquals(0.0, score.gpa, 0.0001)
        assertEquals("学位课", score.category)
        assertEquals(1, score.term)
    }

    @Test
    fun scoresAcceptTheEmptySampleWithoutFailing() {
        assertTrue(GraduateParser.scores("""{"items":[]}""").isEmpty())
        assertTrue(GraduateParser.scores("[]").isEmpty())
    }

    @Test
    fun studyPlanIsAReadOnlyCourseListWithoutPassJudgement() {
        val (groups, courses) = GraduateParser.studyPlan(
            """{"items":[{"CourseName":"合成课程","CourseID":"X1","CourseTypeName":"学位课","Credit":"2","StudyHour":"32","TestSty":"考试","Year":"2026","Term":"1"}]}"""
        )
        assertEquals(1, groups.size)
        val group = groups.single()
        assertEquals("学位课", group.groupName)
        // 门户不给培养方案学分要求，因此不做任何达标判断。
        assertTrue(group.isReadOnly)
        assertEquals(0, group.countRequired)
        val course = courses.single()
        assertEquals("合成课程", course.courseName)
        assertEquals("32", course.hours)
        assertEquals("考试", course.assessment)
        assertEquals("2026 第1学期", course.semester)
    }

    /**
     * 门户对这些接口有四种外层包装，`rowsOf` 必须全部认下来。
     *
     * 只认其中一种（尤其是对裸数组直接 `JSONObject(raw)`）就会把「接口返回了数据」
     * 误判成「无法识别的数据」——真机上研究生的课表导入正是这么失败的。
     */
    @Test
    fun rowsOfAcceptsEveryPortalResponseWrapper() {
        val row = """{"JieCi":"1,2","Monday":"合成课程"}"""
        val cases = linkedMapOf(
            "裸数组" to """[$row]""",
            "{items:[…]}" to """{"items":[$row]}""",
            "{data:{items:[…]}}" to """{"data":{"items":[$row]}}""",
            "{data:[…]}" to """{"data":[$row]}"""
        )
        cases.forEach { (label, raw) ->
            assertEquals("$label 未被识别", 1, GraduateParser.rowsOf(raw)?.length())
        }
        // 带空白/换行的裸数组同样要认（真实响应常带换行）。
        assertEquals(1, GraduateParser.rowsOf("\n  [$row]\n")?.length())
        // 认不出来时返回 null 而不是抛错——调用方自行决定是报错还是当作 0 条。
        assertNull(GraduateParser.rowsOf("""{"nope":1}"""))
        assertNull(GraduateParser.rowsOf("not json"))
        assertNull(GraduateParser.rowsOf(""))
    }

    /** 课表解析走的是「不认识就报错」那一支：接口变了不能显示成「本学期没课」。 */
    @Test
    fun scheduleAcceptsBareArrayAndObjectWrappersAlike() {
        val row = """{"JieCi":"1,2","Monday":"合成课程\r\n测试教师\r\n节次:1,2\r\n周次:2-3\r\n地点:测试教室"}"""
        listOf(
            """[$row]""",
            """{"items":[$row]}""",
            """{"data":{"items":[$row]}}""",
            """{"data":[$row]}"""
        ).forEach { raw ->
            assertEquals(listOf("合成课程"), GraduateParser.schedule(raw).map { it.title })
        }
        assertThrows(IllegalStateException::class.java) { GraduateParser.schedule("""{"nope":1}""") }
    }

    @Test
    fun malformedListPayloadFailsLoudlyInsteadOfLookingEmpty() {
        assertThrows(IllegalStateException::class.java) { GraduateParser.scores("""{"nope":1}""") }
        assertThrows(IllegalStateException::class.java) { GraduateParser.studyPlan("not json") }
    }

    // ---- 来源与能力 ----

    @Test
    fun importSourceOrderAndDefaultMatchTheDropdownContract() {
        assertEquals(
            listOf("南宁分校", "桂林本部", "研究生"),
            ImportSource.entries.map { it.label }
        )
        // 首次进入（没有历史记录）默认桂林本部。
        assertEquals(ImportSource.GUILIN, ImportSource.fromStorageValue(null))
        assertEquals(ImportSource.GUILIN, ImportSource.fromStorageValue("unknown"))
        assertEquals(ImportSource.GRADUATE, ImportSource.fromStorageValue("graduate"))
        assertEquals(CampusType.GRADUATE, ImportSource.GRADUATE.campus)
    }

    @Test
    fun graduateCapabilitiesDisableEveryModuleThePortalDoesNotOffer() {
        val graduate = capabilitiesFor(CampusType.GRADUATE)
        assertTrue(graduate.requiresCaptcha)
        assertTrue(graduate.supportsScore)
        assertTrue(graduate.supportsStudyPlan)
        assertFalse(graduate.supportsGpa)
        assertFalse(graduate.hasNoonSections)
        // 统计页的每个数字都由作息钟点算出，而研究生门户不提供任何上课时间，用户也没有权威钟点可填。
        assertFalse(graduate.supportsCourseTimeStats)
        assertFalse(graduate.supportsClassPeriodSettings)
        listOf(
            MenuCapability.EXAM,
            MenuCapability.GRADE_EXAM,
            MenuCapability.PROFESSIONAL_SCORE,
            MenuCapability.FITNESS_SCORE,
            MenuCapability.FINANCE
        ).forEach { capability ->
            assertFalse("$capability 应对研究生禁用", graduate.menuEnabled(capability))
        }
        assertTrue(graduate.menuEnabled(MenuCapability.SCORE))
        assertTrue(graduate.menuEnabled(MenuCapability.STUDY_PLAN))
        // 校园信息与情侣/基友课表都对研究生可用：前者与教务模块无关，
        // 后者此前是缺陷（本地 campus key 缺 graduate 分支，下午第一节整张卡片消失）。
        assertTrue(graduate.supportsCampusInfo)
        assertTrue(graduate.menuEnabled(MenuCapability.CAMPUS_INFO))
        assertTrue(graduate.supportsPartnerSchedule)
        assertTrue(graduate.menuEnabled(MenuCapability.PARTNER_SCHEDULE))
    }

    @Test
    fun guilinAndNanningCapabilitiesAreUnchanged() {
        val guilin = capabilitiesFor(CampusType.GUILIN)
        assertTrue(guilin.hasNoonSections)
        assertTrue(guilin.supportsCourseTimeStats)
        assertTrue(guilin.supportsClassPeriodSettings)
        assertTrue(guilin.supportsGpa)
        assertFalse(guilin.requiresCaptcha)
        assertTrue(guilin.menuEnabled(MenuCapability.CAMPUS_INFO))

        val nanning = capabilitiesFor(CampusType.NANNING)
        assertTrue(nanning.requiresCaptcha)
        assertFalse(nanning.hasNoonSections)
        assertTrue(nanning.supportsCourseTimeStats)
        assertTrue(nanning.supportsClassPeriodSettings)
        // 三类来源的校园信息口径现在一致：南宁不再整项隐藏，菜单里正常出现且可用。
        assertTrue(nanning.supportsCampusInfo)
        assertTrue(nanning.menuEnabled(MenuCapability.CAMPUS_INFO))
    }

    // ---- 菜单禁用 ----

    @Test
    fun graduateDrawerKeepsUnsupportedEntriesVisibleButDisabled() {
        listOf(DrawerItem.Exam, DrawerItem.GradeExam, DrawerItem.ProfessionalScore, DrawerItem.FitnessScore)
            .forEach { item ->
                assertFalse("$item 应对研究生禁用", item.isEnabledFor(CampusType.GRADUATE))
                assertEquals("研究生暂不支持", item.unsupportedHintFor(CampusType.GRADUATE))
            }
        // 课表、导入、设置等与教务模块无关的入口必须照常可用。
        listOf(
            DrawerItem.Schedule, DrawerItem.Import, DrawerItem.Settings,
            DrawerItem.Notice, DrawerItem.SemesterOverview, DrawerItem.FAQ, DrawerItem.About
        ).forEach { item ->
            assertTrue("$item 不应被禁用", item.isEnabledFor(CampusType.GRADUATE))
            assertNull(item.unsupportedHintFor(CampusType.GRADUATE))
        }
        // 研究生门户支持成绩、培养计划、校园信息与情侣/基友课表。
        listOf(
            DrawerItem.Score,
            DrawerItem.StudyPlan,
            DrawerItem.CampusInfo,
            DrawerItem.PartnerSchedule
        ).forEach { item ->
            assertTrue("$item 应对研究生可用", item.isEnabledFor(CampusType.GRADUATE))
            assertNull(item.unsupportedHintFor(CampusType.GRADUATE))
        }
        // 统计页依赖上课钟点，研究生门户不提供，也没有权威钟点可填，因此入口保留但置灰。
        assertFalse(DrawerItem.CourseTimeStats.isEnabledFor(CampusType.GRADUATE))
        assertEquals("研究生暂不支持", DrawerItem.CourseTimeStats.unsupportedHintFor(CampusType.GRADUATE))
    }

    @Test
    fun guilinAndNanningMenusStayEnabled() {
        listOf(CampusType.GUILIN, CampusType.NANNING).forEach { campus ->
            listOf(DrawerItem.Score, DrawerItem.Exam, DrawerItem.StudyPlan, DrawerItem.CourseTimeStats)
                .forEach { item ->
                    assertTrue("$item 在 $campus 应可用", item.isEnabledFor(campus))
                    assertNull(item.unsupportedHintFor(campus))
                }
        }
    }

    // ---- 作息 ----

    @Test
    fun graduateProfileHasTwelveSectionsWithNoTimesByDefault() {
        val periods = graduateClassPeriods()
        assertEquals(GRADUATE_SECTION_COUNT, periods.size)
        assertEquals((1..12).toList(), periods.map { it.section })
        assertTrue(periods.all { it.startsAt.isEmpty() && it.endsAt.isEmpty() })
        assertFalse(hasConfiguredClassTimes(periods))
        assertTrue(validateClassPeriods(CampusType.GRADUATE, periods))
        assertEquals(periods, defaultClassPeriods(CampusType.GRADUATE))
        assertEquals(periods, defaultClassPeriods(ClassPeriodProfile.GRADUATE))
        assertEquals(ClassPeriodProfile.GRADUATE, classPeriodProfile(CampusType.GRADUATE, "yanshan"))
    }

    @Test
    fun graduateProfileAcceptsFullyFilledTimesAndRejectsHalfFilledOnes() {
        val filled = graduateClassPeriods().mapIndexed { index, period ->
            val hour = 8 + index
            period.copy(startsAt = "%02d:00".format(hour), endsAt = "%02d:45".format(hour))
        }
        assertTrue(validateClassPeriods(CampusType.GRADUATE, filled))
        assertTrue(hasConfiguredClassTimes(filled))

        val halfFilled = filled.mapIndexed { index, period ->
            if (index < 6) period else period.copy(startsAt = "", endsAt = "")
        }
        assertFalse(validateClassPeriods(CampusType.GRADUATE, halfFilled))

        val overlapping = filled.mapIndexed { index, period ->
            if (index == 1) period.copy(startsAt = "08:30", endsAt = "09:30") else period
        }
        assertFalse(validateClassPeriods(CampusType.GRADUATE, overlapping))
    }

    @Test
    fun graduateProfileRejectsForeignSectionCounts() {
        // 本科 14 节 / 南宁 11 节都不能冒充研究生作息。
        assertFalse(validateClassPeriods(CampusType.GRADUATE, defaultClassPeriods(CampusType.GUILIN)))
        assertFalse(validateClassPeriods(CampusType.GRADUATE, defaultClassPeriods(CampusType.NANNING)))
    }

    @Test
    fun classPeriodOverridesRestoreGraduateEntries() {
        val entries = graduateClassPeriods()
            .map { "${it.section}\u0000${it.startsAt}\u0000${it.endsAt}" }
            .toSet()
        val overrides = decodeClassPeriodOverrides(
            selectedSubCampus = GuilinSubCampus.YANSHAN,
            legacyGuilinEntries = emptySet(),
            yanshanEntries = emptySet(),
            pingfengEntries = emptySet(),
            nanningEntries = emptySet(),
            graduateEntries = entries
        )
        assertEquals(graduateClassPeriods(), overrides[ClassPeriodProfile.GRADUATE])

        // 半套时间（只给前半段填了时刻）不得被视为有效覆盖。
        val halfFilled = graduateClassPeriods().mapIndexed { index, period ->
            if (index < 6) "${period.section}\u000008:00\u000008:45" else "${period.section}\u0000\u0000"
        }.toSet()
        val broken = decodeClassPeriodOverrides(
            selectedSubCampus = GuilinSubCampus.YANSHAN,
            legacyGuilinEntries = emptySet(),
            yanshanEntries = emptySet(),
            pingfengEntries = emptySet(),
            nanningEntries = emptySet(),
            graduateEntries = halfFilled
        )
        assertNull(broken[ClassPeriodProfile.GRADUATE])
    }

    // ---- 节次显示 ----

    @Test
    fun sectionLabelsApplyTheNoonOffsetOnlyForGuilin() {
        // 桂林：内部 5、6 是中午，7 以上整体减 2。
        assertEquals("午1-午2", sectionSpanLabel(5, 6, hasNoon = true))
        assertEquals("5-6", sectionSpanLabel(7, 8, hasNoon = true))
        assertEquals("7-8", sectionSpanLabel(9, 10, hasNoon = true))
        assertEquals("5", sectionSpanLabel(7, 7, hasNoon = true))
        // 南宁与研究生：原始编号直排，不偏移。
        assertEquals("5-6", sectionSpanLabel(5, 6, hasNoon = false))
        assertEquals("11-12", sectionSpanLabel(11, 12, hasNoon = false))
    }

    /**
     * I5a/I5b：长按弹层的范围文案与刷新变化明细必须与课表网格同口径。
     *
     * 这两处原先直接拼内部节次号，桂林下午课（内部 7、8）会显示成「7-8 节」。
     * 规则匹配键与写库路径仍用内部编号，所以这里只断言文案。
     */
    @Test
    fun cardScopeTextAndRefreshDiffUseTheSameSectionMapping() {
        val rule = HiddenCourseRule(
            scope = HiddenCardScope.OCCURRENCE,
            courseKey = hiddenCourseKey("c1", "合成课程"),
            dayOfWeek = 2,
            startSection = 7,
            endSection = 8
        )
        val course = com.glut.schedule.data.model.ScheduleCourse(
            id = "c1", title = "合成课程", room = "04105", teacher = "张老师", colorHex = "",
            occurrences = listOf(
                com.glut.schedule.data.model.CourseOccurrence(
                    id = "o1", courseId = "c1", dayOfWeek = 2,
                    startSection = 7, endSection = 8, weekText = "1-16", note = ""
                )
            )
        )
        // 桂林：内部 7-8 → 显示「5-6」。
        assertTrue(hiddenRuleScopeText(rule, listOf(course), hasNoonSections = true).startsWith("周二 5-6 节"))
        // 研究生：原样。
        assertTrue(hiddenRuleScopeText(rule, listOf(course), hasNoonSections = false).startsWith("周二 7-8 节"))

        val diff = buildScheduleRefreshDiff(emptyList(), listOf(course), hasNoonSections = true)
        assertEquals("周二 第5-6节 · 04105", diff.items.single().afterText)
        val graduateDiff = buildScheduleRefreshDiff(emptyList(), listOf(course), hasNoonSections = false)
        assertEquals("周二 第7-8节 · 04105", graduateDiff.items.single().afterText)
    }

    /** 研究生没有中午节次，因此 12 节必须全部可见，不能被「显示中午」开关裁掉两行。 */
    @Test
    fun graduateSectionsAreNeverTreatedAsNoonSlots() {
        assertFalse(capabilitiesFor(CampusType.GRADUATE).hasNoonSections)
        // 节次号 5、6 在研究生课表里就是普通第 5、6 节，不代表中午；只有 hasNoonSections=false
        // 这一件事在起决定作用，网格据此不做折叠、也不做偏移。
        assertEquals("5-6", sectionSpanLabel(5, 6, hasNoon = false))
        assertFalse(validateClassPeriods(CampusType.GUILIN, graduateClassPeriods()))
    }

    // ---- 账号隔离与提示文案 ----

    @Test
    fun switchingBetweenSourcesWithDifferentStudentNumbersClearsAcademicCache() {
        // 本科学号 → 研究生学号：必须清空上一账号的缓存，避免串数据。
        assertTrue(shouldClearAcademicData("20240001", "20250001"))
        // 同一账号重新登录不得清空。
        assertFalse(shouldClearAcademicData("20250001", "20250001"))
        assertFalse(shouldClearAcademicData("", "20250001"))
    }

    @Test
    fun graduateCompletionMessageReportsFailuresWithoutClaimingCacheIsKept() {
        assertEquals("导入完成", graduateImportCompletionMessage(emptySet()))
        val message = graduateImportCompletionMessage(setOf("成绩"), unparsedWeekTextCount = 2)
        assertTrue(message.contains("成绩"))
        assertTrue(message.contains("2 条"))
        assertFalse("研究生课表失败时整体不写入，不应笼统说保留原缓存", message.contains("已保留原缓存"))
    }

    /** 被跳过的坏记录条数必须出现在状态文案里，否则课程会「莫名少几门」。 */
    @Test
    fun graduateCompletionMessageCarriesTheSkippedRowCount() {
        val message = graduateImportCompletionMessage(emptySet(), skippedRowCount = 3)
        assertEquals("导入完成；已跳过 3 条异常课程记录", message)
        val combined = graduateImportCompletionMessage(
            setOf("成绩"),
            skippedRowCount = 3,
            unparsedWeekTextCount = 1
        )
        assertTrue(combined.contains("部分导入失败：成绩"))
        assertTrue(combined.contains("已跳过 3 条异常课程记录"))
        assertTrue(combined.contains("1 条上课时间的周次无法识别"))
    }

    @Test
    fun graduateSessionExpiryIsDistinguishableForReloginPrompts() {
        val expired = GraduateSessionExpiredException("请重新登录")
        assertTrue(expired.message!!.contains("重新登录"))
    }
}

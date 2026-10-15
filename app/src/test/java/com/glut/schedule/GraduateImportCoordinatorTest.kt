package com.glut.schedule

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.SemesterCacheStatus
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.graduateClassPeriods
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.service.graduate.GraduateClient
import com.glut.schedule.service.graduate.GraduateImportCoordinator
import com.glut.schedule.service.graduate.GraduateImportEffects
import com.glut.schedule.service.graduate.GraduateLoginSession
import com.glut.schedule.service.graduate.GraduatePortalContext
import com.glut.schedule.service.graduate.GraduateScheduleWrite
import com.glut.schedule.service.graduate.GraduateSemesterPayload
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 研究生导入编排的**行为**测试。
 *
 * 存在的理由：C1 缺陷（导入成功后从不调用 `setCampusType(GRADUATE)` / `setLastImportSource`）
 * 曾经在 787 条全绿测试下存活——因为没有任何用例覆盖这条编排路径。
 * 这里用假实现记录调用顺序，把「先写库、再切来源」这条不变量钉住。
 *
 * 全程合成数据：不触碰网络、Room、DataStore。
 */
class GraduateImportCoordinatorTest {

    private val currentSemester = AcademicSemester.create(
        campus = CampusType.GRADUATE,
        portalYear = 2026,
        portalYearId = "2026-2027",
        season = SemesterSeason.AUTUMN,
        portalTermId = "1",
        isCurrent = true,
        cacheStatus = SemesterCacheStatus.NOT_CACHED,
        semesterStartDate = LocalDate.of(2026, 9, 7)
    )

    private val historicalSemester = AcademicSemester.create(
        campus = CampusType.GRADUATE,
        portalYear = 2026,
        portalYearId = "2025-2026",
        season = SemesterSeason.SPRING,
        portalTermId = "2",
        isCurrent = false
    )

    private fun course(title: String = "合成课程") = ScheduleCourse(
        id = "c-$title",
        title = title,
        room = "测试教室",
        teacher = "测试教师",
        colorHex = "",
        occurrences = listOf(
            CourseOccurrence(
                id = "o-$title",
                courseId = "c-$title",
                dayOfWeek = 1,
                startSection = 1,
                endSection = 2,
                weekText = "1-4",
                note = ""
            )
        )
    )

    /** 记录调用顺序的假实现；`log` 就是断言对象。 */
    private class Recorder {
        val log = mutableListOf<String>()
        fun add(step: String) {
            log += step
        }
    }

    private fun effects(
        recorder: Recorder,
        payload: Result<GraduateSemesterPayload>,
        scores: Result<List<ScoreInfo>> = Result.success(emptyList()),
        studyPlan: Result<Pair<List<StudyPlanGroup>, List<StudyPlanCourse>>> =
            Result.success(emptyList<StudyPlanGroup>() to emptyList()),
        catalog: List<AcademicSemester> = listOf(currentSemester, historicalSemester),
        onWrite: (GraduateScheduleWrite) -> Unit = {}
    ) = GraduateImportEffects(
        fetchCatalog = { catalog },
        fetchSemester = { _, _ -> payload },
        fetchScores = { scores },
        fetchStudyPlan = { studyPlan },
        saveCatalog = { recorder.add("saveCatalog") },
        selectCurrentSemester = { recorder.add("selectCurrentSemester") },
        saveCalendar = { _, _ -> recorder.add("saveCalendar") },
        readClassPeriods = {
            recorder.add("readClassPeriods")
            graduateClassPeriods()
        },
        writeSchedule = { write ->
            recorder.add("writeSchedule")
            // 写入的必须是**研究生**档案的作息，而不是「当前校区」的（那会拿到上一个来源的）。
            assertEquals(graduateClassPeriods(), write.classPeriods)
            onWrite(write)
        },
        writeScores = { recorder.add("writeScores") },
        writeStudyPlan = { _, _ -> recorder.add("writeStudyPlan") },
        switchSource = { recorder.add("switchSource") }
    )

    private fun session() = GraduateLoginSession(
        client = GraduateClient(),
        context = GraduatePortalContext(
            year = "2026-2027",
            term = "1",
            currentTermFrom = "/Date(1788710400000+0800)/",
            currentTermEnd = "/Date(1799942400000+0800)/",
            teachWeekEnd = "19",
            studentId = "synthetic",
            grade = "2025",
            name = "合成用户"
        )
    )

    private fun payload(
        courses: List<ScheduleCourse> = listOf(course()),
        startMonday: LocalDate? = LocalDate.of(2026, 9, 7),
        endDate: LocalDate? = LocalDate.of(2027, 1, 15),
        teachWeekEnd: Int? = 19,
        skippedRowCount: Int = 0,
        adjustments: List<SemesterAdjustment> = emptyList()
    ) = GraduateSemesterPayload(
        courses = courses,
        unparsedWeekTextCount = 0,
        semesterStartMonday = startMonday,
        semesterEndDate = endDate,
        teachWeekEnd = teachWeekEnd,
        adjustments = adjustments,
        adjustmentWarning = "",
        skippedRowCount = skippedRowCount
    )

    /** 调课记录必须随课表一起交给落库，否则「调休调课」页与课时统计永远是空的。 */
    @Test
    fun adjustmentsReachTheScheduleWrite() = runTest {
        val recorder = Recorder()
        val adjustments = listOf(
            SemesterAdjustment(
                id = "synthetic-adjustment",
                type = "调课",
                title = "合成课程甲",
                teacher = "测试教师甲",
                originalWeek = 6,
                originalDay = 2,
                originalStartSection = 3,
                originalEndSection = 4,
                originalRoom = "测试教室A",
                makeupWeek = 6,
                makeupDay = 4,
                makeupStartSection = 11,
                makeupEndSection = 12,
                makeupRoom = "测试教室B"
            )
        )
        var written: GraduateScheduleWrite? = null

        GraduateImportCoordinator(
            effects(
                recorder,
                Result.success(payload(adjustments = adjustments)),
                onWrite = { written = it }
            )
        ).run(session()).getOrThrow()

        assertEquals(adjustments, written?.adjustments)
    }

    /** 被跳过的坏记录条数必须原样带回结果，供界面如实提示。 */
    @Test
    fun skippedRecordCountReachesTheOutcome() = runTest {
        val recorder = Recorder()
        val outcome = GraduateImportCoordinator(
            effects(recorder, Result.success(payload(skippedRowCount = 2)))
        ).run(session()).getOrThrow()

        assertEquals(2, outcome.skippedRowCount)
        // 跳过条数不影响导入成功与来源切换——一个坏单元格不该让整张课表进不来。
        assertTrue(recorder.log.contains("switchSource"))
        assertEquals(1, outcome.courseCount)
    }

    @Test
    fun successfulImportWritesScheduleThenSwitchesSource() = runTest {
        val recorder = Recorder()
        val outcome = GraduateImportCoordinator(effects(recorder, Result.success(payload())))
            .run(session())
            .getOrThrow()

        // C1 的核心不变量：switchSource 必须**存在**，且排在 writeSchedule 之后。
        assertTrue("导入成功后必须切换来源，实际顺序=${recorder.log}", recorder.log.contains("switchSource"))
        assertTrue(
            "切换来源必须发生在课表写入之后，实际顺序=${recorder.log}",
            recorder.log.indexOf("writeSchedule") < recorder.log.indexOf("switchSource")
        )
        // 目录/学期/校历这些落库动作也要在切换之前，避免切到「新来源 + 没有数据」。
        listOf("saveCatalog", "selectCurrentSemester", "saveCalendar").forEach { step ->
            assertTrue("缺少 $step：${recorder.log}", step in recorder.log)
            assertTrue(
                "$step 必须在 switchSource 之前：${recorder.log}",
                recorder.log.indexOf(step) < recorder.log.indexOf("switchSource")
            )
        }
        assertEquals(1, outcome.courseCount)
    }

    /** 课表下载失败时绝不能切换来源——否则界面会停在「研究生菜单 + 旧来源数据」。 */
    @Test
    fun failedDownloadNeverSwitchesSource() = runTest {
        val recorder = Recorder()
        val result = GraduateImportCoordinator(
            effects(recorder, Result.failure(IllegalStateException("合成网络错误")))
        ).run(session())

        assertTrue(result.isFailure)
        assertFalse("下载失败不得切换来源：${recorder.log}", recorder.log.contains("switchSource"))
        assertFalse("下载失败不得写课表：${recorder.log}", recorder.log.contains("writeSchedule"))
    }

    /** 空课表按失败处理，同样不得切换来源，也不得覆盖已有缓存。 */
    @Test
    fun emptyScheduleNeverSwitchesSourceNorOverwritesCache() = runTest {
        val recorder = Recorder()
        val result = GraduateImportCoordinator(
            effects(recorder, Result.success(payload(courses = emptyList())))
        ).run(session())

        assertTrue(result.isFailure)
        assertFalse(recorder.log.contains("switchSource"))
        assertFalse(recorder.log.contains("writeSchedule"))
        assertFalse(recorder.log.contains("saveCatalog"))
    }

    /** 校历缺失只记一条提示，导入仍然成功、来源仍然切换。 */
    @Test
    fun missingPortalCalendarIsReportedButStillSwitchesSource() = runTest {
        val recorder = Recorder()
        val outcome = GraduateImportCoordinator(
            effects(recorder, Result.success(payload(startMonday = null, endDate = null, teachWeekEnd = null)))
        ).run(session()).getOrThrow()

        assertTrue(outcome.failedModules.any { it.contains("研究生校历") })
        assertFalse("没有校历就不写学期起止日期", recorder.log.contains("saveCalendar"))
        assertTrue(recorder.log.contains("switchSource"))
    }

    /** 成绩/培养计划各自失败互不牵连，也不影响已经切换好的来源。 */
    @Test
    fun scoresAndStudyPlanFailIndependentlyWithoutBlockingTheImport() = runTest {
        val recorder = Recorder()
        val outcome = GraduateImportCoordinator(
            effects(
                recorder,
                Result.success(payload()),
                scores = Result.failure(IllegalStateException("合成成绩失败")),
                studyPlan = Result.failure(IllegalStateException("合成培养计划失败"))
            )
        ).run(session()).getOrThrow()

        assertEquals(1, outcome.courseCount)
        assertEquals(0, outcome.scoreCount)
        assertEquals(0, outcome.studyPlanCount)
        assertTrue(outcome.failedModules.contains("成绩"))
        assertTrue(outcome.failedModules.contains("培养计划"))
        assertTrue(recorder.log.contains("switchSource"))
    }

    /** 培养计划返回空组时按「门户未返回课程」提示，不写空缓存。 */
    @Test
    fun emptyStudyPlanIsReportedInsteadOfClearingTheCache() = runTest {
        val recorder = Recorder()
        val outcome = GraduateImportCoordinator(effects(recorder, Result.success(payload())))
            .run(session()).getOrThrow()

        assertTrue(outcome.failedModules.any { it.contains("培养计划") })
        assertFalse(recorder.log.contains("writeStudyPlan"))
    }

    /**
     * 学期校历必须用**门户的值**，不得推算。
     *
     * 结束日只能来自 `CurrentTermEnd`（实测 2027-01-15）。曾经用「开学日 + 19 周」估，
     * 得到 2027-01-17 且不带真实学期结构；更糟的是用「课表里出现过的最大周次」反推，
     * 会算出 2026-12-20（早 26 天），并直接污染学期概览的寒暑假展示。
     */
    @Test
    fun calendarComesFromThePortalNotFromEstimation() = runTest {
        val writes = mutableListOf<GraduateScheduleWrite>()
        val base = effects(Recorder(), Result.success(payload()))
        GraduateImportCoordinator(base.copy(writeSchedule = { writes += it }))
            .run(session()).getOrThrow()

        val write = writes.single()
        assertEquals(LocalDate.of(2026, 9, 7), write.semesterStartDate)
        assertEquals(LocalDate.of(2027, 1, 15), write.semesterEndDate)
        // 周数优先用门户 TeachWeekEnd。
        assertEquals(19, write.portalMaxWeek)
    }

    /**
     * 校历缺失时（`semesterEndDate == null`）不写任何日期，且周数退回按课次反推但绝不为 null。
     */
    @Test
    fun nullEndDateSkipsCalendarWriteAndFallsBackToDerivedWeeks() = runTest {
        val recorder = Recorder()
        val writes = mutableListOf<GraduateScheduleWrite>()
        val base = effects(recorder, Result.success(payload(endDate = null, teachWeekEnd = null)))
        val outcome = GraduateImportCoordinator(base.copy(writeSchedule = { writes += it }))
            .run(session()).getOrThrow()

        assertFalse("没有结束日就不写学期起止日期", recorder.log.contains("saveCalendar"))
        assertTrue(outcome.failedModules.any { it.contains("研究生校历") })
        assertNull(writes.single().semesterEndDate)
        // 课次周次是 1-4 ⇒ 反推为 4；绝不为 null（CourseTimeStats 会把 null 判为整学期不可统计）。
        assertEquals(4, writes.single().portalMaxWeek)
    }

    /** 当前周数按校历推算。 */
    @Test
    fun currentWeekUsesTheResolvedCalendar() {
        val coordinator = GraduateImportCoordinator(
            effects(Recorder(), Result.success(payload())),
            today = { LocalDate.of(2026, 9, 21) }
        )
        assertEquals(
            3,
            coordinator.currentWeekFor(LocalDate.of(2026, 9, 7), LocalDate.of(2027, 1, 31))
        )
    }

    /** 目录里没有当前学期时明确报错，不猜、不写库。 */
    @Test
    fun catalogWithoutCurrentSemesterFailsLoudly() = runTest {
        val recorder = Recorder()
        val result = GraduateImportCoordinator(
            effects(recorder, Result.success(payload()), catalog = listOf(historicalSemester))
        ).run(session())

        assertTrue(result.isFailure)
        assertFalse(recorder.log.contains("switchSource"))
        assertFalse(recorder.log.contains("writeSchedule"))
    }
}

package com.glut.schedule.service.graduate

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.academicMaxWeekForCalendar
import com.glut.schedule.data.model.academicWeekForDate
import com.glut.schedule.data.model.countDistinctCourseTitles
import com.glut.schedule.data.model.historicalAcademicMaxWeek
import java.time.LocalDate

/** 一次课表落库所需的全部内容。 */
data class GraduateScheduleWrite(
    val semester: AcademicSemester,
    val courses: List<ScheduleCourse>,
    val classPeriods: List<ClassPeriod>,
    val semesterStartDate: LocalDate?,
    val semesterEndDate: LocalDate?,
    val portalMaxWeek: Int
)

/** 导入结果，供界面渲染；失败模块逐项列出，互不牵连。 */
data class GraduateImportOutcome(
    val courseCount: Int,
    val scoreCount: Int,
    val studyPlanCount: Int,
    val unparsedWeekTextCount: Int,
    /** 因无法识别而被跳过的课程条数；必须带进状态文案，否则课程会「莫名少几门」。 */
    val skippedRowCount: Int,
    val adjustmentWarning: String,
    val failedModules: List<String>
)

/**
 * 研究生导入所需的外部副作用。
 *
 * 生产实现由 `DirectLoginViewModel` 提供真实仓库/设置；测试提供假实现并记录调用顺序。
 * 这样「先写库、再切来源」这条不变量才能被真正测到——只测 getter 抓不到它。
 */
data class GraduateImportEffects(
    val fetchCatalog: suspend (GraduateLoginSession) -> List<AcademicSemester>,
    val fetchSemester: suspend (
        GraduateLoginSession,
        AcademicSemester
    ) -> Result<GraduateSemesterPayload>,
    val fetchScores: suspend (GraduateLoginSession) -> Result<List<ScoreInfo>>,
    val fetchStudyPlan: suspend (
        GraduateLoginSession
    ) -> Result<Pair<List<StudyPlanGroup>, List<StudyPlanCourse>>>,
    val saveCatalog: suspend (List<AcademicSemester>) -> Unit,
    val selectCurrentSemester: suspend (AcademicSemester) -> Unit,
    val saveCalendar: suspend (LocalDate, LocalDate) -> Unit,
    /** 当前来源的作息档案；必须在切换来源之前就能取到（见 [GraduateImportCoordinator.run]）。 */
    val readClassPeriods: suspend () -> List<ClassPeriod>,
    val writeSchedule: suspend (GraduateScheduleWrite) -> Unit,
    val writeScores: suspend (List<ScoreInfo>) -> Unit,
    val writeStudyPlan: suspend (List<StudyPlanGroup>, List<StudyPlanCourse>) -> Unit,
    /**
     * 把应用切到研究生来源（`CampusType` + 「上次成功导入的来源」）。
     *
     * **只允许在课表写入成功之后调用。** 这个回调曾经整段缺失，导致研究生数据配上桂林的
     * 展示口径：12 节被当成 14 节（第 5、6 节被折叠、下午整体错位 2 节）、菜单不置灰、
     * 成绩页按本科公式算绩点。任何绕过这里的切换都会让那个 bug 复活。
     */
    val switchSource: suspend () -> Unit
)

/**
 * 与 `runCatching` 相同，但**不吞协程取消**。
 *
 * 页面销毁、切换账号都会取消 `viewModelScope`，此时抛的是 `CancellationException`。
 * 用 `runCatching` 会把它降级成一次普通的失败：`run()` 于是返回 Success，
 * ViewModel 接着写 UI 状态并提示「导入完成 / 部分导入失败」——**一次取消被显示成一次导入**。
 * 取消必须原样上抛，让协程按取消处理。
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (cancellation: kotlinx.coroutines.CancellationException) {
    throw cancellation
} catch (failure: Throwable) {
    Result.failure(failure)
}

/**
 * 研究生导入编排。
 *
 * 顺序是有讲究的，`GraduateImportCoordinatorTest` 把它钉住了：
 * 1. 取学期目录与当前学期课表，**课表为空即中止**（不覆盖已有缓存）；
 * 2. 取研究生档案的作息（按档案取，不能读「当前校区」的，否则拿到的是上一个来源的）；
 * 3. 写课表 → 存目录 → 选中当前学期 → 写校历；
 * 4. 以上都成功之后，才切换来源；
 * 5. 成绩与培养计划各自失败互不牵连。
 */
class GraduateImportCoordinator(
    private val effects: GraduateImportEffects,
    private val today: () -> LocalDate = LocalDate::now
) {
    suspend fun run(session: GraduateLoginSession): Result<GraduateImportOutcome> = runCatchingCancellable {
        val catalog = effects.fetchCatalog(session)
        val current = catalog.firstOrNull { it.isCurrent }
            ?: error("研究生门户未返回当前学期，请稍后重试")
        val payload = effects.fetchSemester(session, current).getOrElse { error ->
            throw IllegalStateException(
                "${current.displayName}课表导入失败：${error.message.orEmpty()}",
                error
            )
        }
        require(payload.courses.isNotEmpty()) {
            "${current.displayName}未获取到课程，已保留现有缓存"
        }

        val start = payload.semesterStartMonday
        // 学期结束日**只能**来自门户 `CurrentTermEnd`：这里不做任何推算（推算会把学期缩短）。
        val end = payload.semesterEndDate
        val failedModules = linkedSetOf<String>()

        effects.writeSchedule(
            GraduateScheduleWrite(
                semester = current,
                courses = payload.courses,
                // 按「研究生」档案取，避免在切换来源前读到上一个来源的作息。
                classPeriods = effects.readClassPeriods(),
                semesterStartDate = start,
                semesterEndDate = end,
                // 周数优先用门户 `TeachWeekEnd`；门户没给才按课次反推（反推只是下界）。
                // **绝不为 null**：CourseTimeStats 会把 portalMaxWeek 为 null 的学期整学期判为不可统计。
                portalMaxWeek = payload.teachWeekEnd
                    ?: historicalAcademicMaxWeek(current.portalMaxWeek, payload.courses)
            )
        )
        effects.saveCatalog(catalog)
        effects.selectCurrentSemester(current)

        if (start != null && end != null) {
            effects.saveCalendar(start, end)
        } else {
            // 门户没有给出完整校历：一个日期都不写——既不推算结束日，也不套用本专科规则，
            // 页面按周次显示。如实提示，用户可在门户确认后重试。
            failedModules += "研究生校历（请稍后在门户补全）"
        }

        // —— 到这里课表已经落库，现在才允许切换来源。 ——
        effects.switchSource()

        var scoreCount = 0
        effects.fetchScores(session)
            .onSuccess { scores ->
                effects.writeScores(scores)
                scoreCount = scores.size
            }
            .onFailure { failedModules += "成绩" }

        var studyPlanCount = 0
        effects.fetchStudyPlan(session)
            .onSuccess { (groups, courses) ->
                if (groups.isEmpty()) {
                    failedModules += "培养计划（门户未返回课程）"
                } else {
                    effects.writeStudyPlan(groups, courses)
                    studyPlanCount = groups.size
                }
            }
            .onFailure { failedModules += "培养计划" }

        GraduateImportOutcome(
            courseCount = payload.courses.countDistinctCourseTitles(),
            scoreCount = scoreCount,
            studyPlanCount = studyPlanCount,
            unparsedWeekTextCount = payload.unparsedWeekTextCount,
            skippedRowCount = payload.skippedRowCount,
            adjustmentWarning = payload.adjustmentWarning,
            failedModules = failedModules.toList()
        )
    }

    /** 当前周数；有校历时按校历算，供调用方写 `currentWeek`。 */
    fun currentWeekFor(start: LocalDate, end: LocalDate): Int =
        academicWeekForDate(today(), start, academicMaxWeekForCalendar(start, end))
}

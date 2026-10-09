package com.glut.schedule.service.graduate

import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.model.StudyPlanCourse
import com.glut.schedule.data.model.StudyPlanGroup
import com.glut.schedule.data.model.countUnparsedWeekTexts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDate

/** 单个研究生学期课表的导入结果。 */
data class GraduateSemesterPayload(
    val courses: List<ScheduleCourse>,
    val unparsedWeekTextCount: Int,
    /** 门户 `CurrentTermFrom`（本身已是周一）；缺失即 null。 */
    val semesterStartMonday: LocalDate?,
    /**
     * 门户 `CurrentTermEnd`（学期最后一天，**不做周一对齐**）；缺失即 null。
     *
     * **绝不用课次反推学期结束日**：反推得到的结果比真实结束日早很多
     * （实测反推 2026-12-20 vs 门户真实 2027-01-15，差 26 天），并会直接污染
     * 学期概览里的寒暑假展示。
     */
    val semesterEndDate: LocalDate? = null,
    /** 门户 `TeachWeekEnd`：教学周上界。缺失时才退回按课次反推。 */
    val teachWeekEnd: Int? = null,
    /** 非空时只作提示展示，绝不据此改动主课表。 */
    val adjustmentWarning: String = "",
    /**
     * 无法识别而被**跳过**的课程条数。
     *
     * 与 [unparsedWeekTextCount] 不同：后者是「已经存进来、但周次读不懂」的课次，
     * 前者是「整条都没能读出来、因此没有入库」。两者都必须回传给用户，
     * 否则表现就是「课程莫名其妙少了几门」。
     */
    val skippedRowCount: Int = 0
)

/** 研究生登录结果：会话客户端 + 白名单投影后的门户上下文。 */
data class GraduateLoginSession(
    val client: GraduateClient,
    val context: GraduatePortalContext
)

/**
 * 研究生登录与取数编排。
 *
 * 只做「调用客户端 → 交给解析器 → 组装模型」，本身不持有会话状态：
 * 会话由 [GraduateClient] 的 CookieJar 承载，上下文由调用方（ViewModel）保存。
 * 每个模块（课表 / 成绩 / 培养计划）都能用 `Result` 单独失败，
 * 因为计划明确要求「各模块独立反馈失败，不影响已成功导入的课表」。
 */
object GraduateImportService {

    /** 登录并取回门户上下文。失败时抛出带中文说明的异常。 */
    suspend fun login(
        username: String,
        password: String,
        captcha: String,
        client: GraduateClient = GraduateClient()
    ): GraduateLoginSession = withContext(Dispatchers.IO) {
        client.login(username, password, captcha)
        GraduateLoginSession(client, client.bootstrap())
    }

    suspend fun catalog(session: GraduateLoginSession): List<AcademicSemester> =
        withContext(Dispatchers.IO) {
            GraduateParser.catalog(
                year = session.context.year,
                term = session.context.term,
                grade = session.context.grade,
                currentStartMonday = session.context.currentSemesterStartMonday
            )
        }

    /**
     * 下载一个学期的课表。
     *
     * 学期参数直接取 [AcademicSemester] 里保存的**门户原始学年/学期**，不重新推算——
     * 历史学期在门户里同样是 `2025-2026` + `1/2` 这套参数，与本专科的编号规则无关。
     */
    suspend fun importSemester(
        session: GraduateLoginSession,
        semester: AcademicSemester
    ): Result<GraduateSemesterPayload> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val rows = session.client.schedule(
                year = semester.portalYearId,
                term = semester.portalTermId,
                studentId = session.context.studentId
            )
            val parsed = GraduateParser.scheduleWithDiagnostics(rows)
            val isCurrent = semester.isCurrent ||
                (semester.portalYearId == session.context.year &&
                    semester.portalTermId == session.context.term)
            GraduateSemesterPayload(
                courses = parsed.courses,
                unparsedWeekTextCount = countUnparsedWeekTexts(parsed.courses),
                // 校历只对当前学期有意义；历史学期一律留空，绝不臆造。
                semesterStartMonday = session.context.currentSemesterStartMonday.takeIf { isCurrent },
                semesterEndDate = session.context.currentSemesterEndDate.takeIf { isCurrent },
                teachWeekEnd = session.context.teachWeekEndValue.takeIf { isCurrent },
                adjustmentWarning = adjustmentWarning(session, semester),
                skippedRowCount = parsed.skippedCount
            )
        }
    }

    /**
     * 调课查询：**只作提示**。
     *
     * 当前样本为空，尚未确认「有调课时主课表是否已反映变更」，因此既不套用本科的调课算法，
     * 也不二次修改主课表。查询失败同样不阻断导入。
     */
    private fun adjustmentWarning(
        session: GraduateLoginSession,
        semester: AcademicSemester
    ): String = runCatchingCancellable {
        val raw = session.client.adjustments(
            year = semester.portalYearId,
            term = semester.portalTermId,
            studentId = session.context.studentId
        )
        // 用与课表同一份包装识别逻辑：`GetCheckedAdjustCourseList` 同属 TimeTableNewService，
        // 返回裸数组的可能性很高。自己再写一份 `JSONObject(raw)` 会在裸数组上抛异常、
        // 被外层 runCatching 吃掉，于是**明明取到了数据却报「调课信息未能获取」**。
        val count = GraduateParser.rowsOf(raw)?.length() ?: 0
        if (count > 0) "门户有 $count 条调课记录，暂未自动并入课表，请以研究生门户为准" else ""
    }.getOrElse { "调课信息未能获取，请在研究生门户核对" }

    /** 成绩：门户不给绩点，界面不得推算，因此只返回真实分数与学分。 */
    suspend fun scores(session: GraduateLoginSession): Result<List<ScoreInfo>> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable { GraduateParser.scores(session.client.scores(session.context.studentId)) }
        }

    /** 培养计划：只读课程列表。 */
    suspend fun studyPlan(
        session: GraduateLoginSession
    ): Result<Pair<List<StudyPlanGroup>, List<StudyPlanCourse>>> = withContext(Dispatchers.IO) {
        runCatchingCancellable { GraduateParser.studyPlan(session.client.studyPlan(session.context.studentId)) }
    }

    /**
     * 仅凭已保存的会话下载某个学期的课表（首页刷新与历史学期批量下载共用）。
     *
     * 与 [importSemester] 的差别是它没有 [GraduatePortalContext]——刷新或批量下载时应用可能
     * 已经重启，只剩 Cookie 与学号。学年/学期参数从学期自身取，因此不需要重新登录拿上下文；
     * 这也正是历史学期能下载的原因：门户对历史学期用的是同一套 `学年 + 1/2` 参数。
     */
    suspend fun downloadSemester(
        cookie: String,
        semester: AcademicSemester,
        studentId: String
    ): Result<GraduateSemesterPayload> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val client = GraduateClient(cookie)
            val rows = client.schedule(
                year = semester.portalYearId,
                term = semester.portalTermId,
                studentId = studentId
            )
            val parsed = GraduateParser.scheduleWithDiagnostics(rows)
            GraduateSemesterPayload(
                courses = parsed.courses,
                unparsedWeekTextCount = countUnparsedWeekTexts(parsed.courses),
                // 历史学期没有权威校历，一律留空，由调用方沿用学期自身已有的日期。
                semesterStartMonday = null,
                skippedRowCount = parsed.skippedCount
            )
        }
    }
}

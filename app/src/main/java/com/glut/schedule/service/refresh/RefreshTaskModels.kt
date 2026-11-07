package com.glut.schedule.service.refresh

import com.glut.schedule.data.settings.CampusType

/** 目录对应用户能主动刷新的页面，派生页面会在执行时展开并合并其数据依赖。 */
enum class RefreshTaskId(val title: String, val group: String, val academic: Boolean = false) {
    SCHEDULE("课表安排", "教务", true), EXAMS("考试安排", "教务", true),
    SCORES("考试成绩", "教务", true), GRADE_EXAMS("等级考试成绩", "教务", true),
    STUDY_PLAN("教学计划", "教务", true), PROFESSIONAL_SCORE("专业成绩", "教务", true),
    SEMESTER_OVERVIEW("学期概览", "教务", true), HOLIDAY_ADJUSTMENTS("调休调课", "教务", true), COURSE_TIME_STATS("课时统计", "教务", true),
    CHAOXING("学习通任务", "学习通"),
    FINANCE_OVERVIEW("财务概览", "财务"), FINANCE_PENDING("待缴项目", "财务"),
    FINANCE_OTHER_PAYMENTS("其他缴费", "财务"), FINANCE_FEE_PROJECTS("收费项目", "财务"),
    FINANCE_TRANSACTIONS("交易记录", "财务"), FINANCE_PAYMENT_DETAILS("缴费明细", "财务"),
    FINANCE_COURSE_RECORDS("选课记录", "财务"), FINANCE_ELECTRONIC_TICKETS("电子票据", "财务"),
    FINANCE_CREDIT_SETTLEMENT("学分结算", "财务"),
    FITNESS_LATEST("最新体测成绩", "体测"), FITNESS_HISTORY("历年体测成绩", "体测"),
    FITNESS_STANDARD("体测评分标准", "体测"), CAMPUS_INFO("校园信息", "公共数据"),
    NOTICES("通知公告", "公共数据"), BACKGROUND_GALLERY("背景画廊", "公共数据"),
    UPDATE_CHECK("版本检查", "公共数据");

    // 旧枚举仅用于读取历史记录，定时执行入口只开放教务九项与学习通。
    val schedulable: Boolean get() = academic || this == CHAOXING
}

fun refreshTaskSupported(task: RefreshTaskId, campus: CampusType): Boolean = when {
    !task.schedulable -> false
    task.academic -> campus == CampusType.GUILIN
    else -> true
}

data class ScheduledRefreshPlan(val id: String, val minuteOfDay: Int, val taskIds: Set<RefreshTaskId>)
enum class RefreshTaskStatus { SUCCESS, FAILED, LOGIN_REQUIRED, UNSUPPORTED, CANCELLED }
data class ScheduledRefreshTaskResult(val taskId: RefreshTaskId, val status: RefreshTaskStatus, val message: String)
enum class ScheduledRefreshRunState { RUNNING, FINISHED, INTERRUPTED }
data class ScheduledRefreshRun(val id: String, val planId: String, val startedAt: Long, val finishedAt: Long,
    val results: List<ScheduledRefreshTaskResult>,
    val minuteOfDay: Int? = null,
    val taskIds: Set<RefreshTaskId> = results.map { it.taskId }.toSet(),
    val state: ScheduledRefreshRunState = ScheduledRefreshRunState.FINISHED,
    val workId: String? = null,
    val activeTask: RefreshTaskId? = null)

enum class ScheduledRefreshRunStatus { RUNNING, FULL_SUCCESS, PARTIAL_SUCCESS, LOGIN_REQUIRED, FAILED, INTERRUPTED }
data class ScheduledRefreshRunSummary(val status: ScheduledRefreshRunStatus, val title: String,
    val successCount: Int, val totalCount: Int)

/** 首页和记录页共用同一结论；未实际执行的项目不生成虚假的失败结果。 */
fun scheduledRefreshRunSummary(run: ScheduledRefreshRun): ScheduledRefreshRunSummary {
    val count = run.results.count { it.status == RefreshTaskStatus.SUCCESS }
    val total = run.taskIds.size
    val status = when {
        run.state == ScheduledRefreshRunState.RUNNING -> ScheduledRefreshRunStatus.RUNNING
        run.state == ScheduledRefreshRunState.INTERRUPTED -> ScheduledRefreshRunStatus.INTERRUPTED
        total > 0 && count == total -> ScheduledRefreshRunStatus.FULL_SUCCESS
        count > 0 -> ScheduledRefreshRunStatus.PARTIAL_SUCCESS
        run.results.any { it.status == RefreshTaskStatus.LOGIN_REQUIRED } -> ScheduledRefreshRunStatus.LOGIN_REQUIRED
        else -> ScheduledRefreshRunStatus.FAILED
    }
    val title = when (status) {
        ScheduledRefreshRunStatus.RUNNING -> "正在刷新"
        ScheduledRefreshRunStatus.FULL_SUCCESS -> "全部成功"
        ScheduledRefreshRunStatus.PARTIAL_SUCCESS -> "部分成功"
        ScheduledRefreshRunStatus.LOGIN_REQUIRED -> "需要登录"
        ScheduledRefreshRunStatus.FAILED -> "刷新失败"
        ScheduledRefreshRunStatus.INTERRUPTED -> "刷新中断"
    }
    return ScheduledRefreshRunSummary(status, title, count, total)
}

/** 数据依赖只执行一次；用户选中的是页面，后台执行的是其真正的数据来源。 */
fun refreshTaskDependencies(task: RefreshTaskId): Set<RefreshTaskId> = when (task) {
    RefreshTaskId.PROFESSIONAL_SCORE -> setOf(RefreshTaskId.SCORES, RefreshTaskId.STUDY_PLAN)
    RefreshTaskId.HOLIDAY_ADJUSTMENTS, RefreshTaskId.SEMESTER_OVERVIEW -> setOf(RefreshTaskId.SCHEDULE, RefreshTaskId.SEMESTER_OVERVIEW)
    RefreshTaskId.COURSE_TIME_STATS -> setOf(RefreshTaskId.SCHEDULE)
    else -> setOf(task)
}

fun validateRefreshPlans(plans: List<ScheduledRefreshPlan>): Boolean =
    plans.isNotEmpty() && plans.map { it.id }.distinct().size == plans.size &&
        plans.all { it.id.isNotBlank() && it.taskIds.isNotEmpty() && it.taskIds.all { task -> task.schedulable } } &&
        validateRefreshTimes(plans.map { it.minuteOfDay })

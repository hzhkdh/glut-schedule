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
    UPDATE_CHECK("版本检查", "公共数据")
}

fun refreshTaskSupported(task: RefreshTaskId, campus: CampusType): Boolean = when {
    task.academic -> campus == CampusType.GUILIN
    task.group == "财务" -> campus == CampusType.GUILIN
    task.group == "体测" -> com.glut.schedule.data.settings.capabilitiesFor(campus).supportsFitnessScore
    else -> true
}

data class ScheduledRefreshPlan(val id: String, val minuteOfDay: Int, val taskIds: Set<RefreshTaskId>)
enum class RefreshTaskStatus { SUCCESS, FAILED, LOGIN_REQUIRED, UNSUPPORTED, CANCELLED }
data class ScheduledRefreshTaskResult(val taskId: RefreshTaskId, val status: RefreshTaskStatus, val message: String)
data class ScheduledRefreshRun(val id: String, val planId: String, val startedAt: Long, val finishedAt: Long,
    val results: List<ScheduledRefreshTaskResult>)

/** 数据依赖只执行一次；用户选中的是页面，后台执行的是其真正的数据来源。 */
fun refreshTaskDependencies(task: RefreshTaskId): Set<RefreshTaskId> = when (task) {
    RefreshTaskId.PROFESSIONAL_SCORE -> setOf(RefreshTaskId.SCORES, RefreshTaskId.STUDY_PLAN)
    RefreshTaskId.HOLIDAY_ADJUSTMENTS, RefreshTaskId.SEMESTER_OVERVIEW -> setOf(RefreshTaskId.SCHEDULE, RefreshTaskId.SEMESTER_OVERVIEW)
    RefreshTaskId.COURSE_TIME_STATS -> setOf(RefreshTaskId.SCHEDULE)
    else -> setOf(task)
}

fun validateRefreshPlans(plans: List<ScheduledRefreshPlan>): Boolean =
    plans.isNotEmpty() && plans.map { it.id }.distinct().size == plans.size &&
        plans.all { it.id.isNotBlank() && it.taskIds.isNotEmpty() } && validateRefreshTimes(plans.map { it.minuteOfDay })

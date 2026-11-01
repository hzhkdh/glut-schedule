package com.glut.schedule.service.chaoxing

// 使用课程与班级组合键区分同名课程，避免筛选串班。
fun CachedHomework.subjectKey(): String = "$courseId:$classId"

fun filterHomework(rows: List<CachedHomework>, status: String, subject: String): List<CachedHomework> =
    rows.filter { (status.isEmpty() || it.status == status) && (subject.isEmpty() || it.subjectKey() == subject) }

fun remainingHomeworkTime(deadline: Long?, status: String, now: Long): String? {
    if (deadline == null || status != "未交") return null
    val minutes = (deadline - now) / 60_000
    return when {
        deadline <= now -> "已截止"
        minutes < 1 -> "即将截止"
        minutes >= 1440 -> "剩余 ${minutes / 1440} 天 ${minutes % 1440 / 60} 小时"
        minutes >= 60 -> "剩余 ${minutes / 60} 小时 ${minutes % 60} 分钟"
        else -> "剩余 $minutes 分钟"
    }
}

package com.glut.schedule.service.chaoxing

// 使用课程与班级组合键区分同名课程，避免筛选串班。
fun CachedHomework.subjectKey(): String = "$courseId:$classId"

/** 本地时钟驱动紧迫程度；与账号同步无关。 */
data class HomeworkCountdown(val text: String, val tone: String, val label: String)
fun homeworkCountdown(deadline: Long?, status: String, now: Long): HomeworkCountdown? {
    if (deadline == null || status != "未交") return null
    val diff = deadline - now
    val tone = if (diff > 72*3600000L) "normal" else if (diff > 24*3600000L) "warning" else "urgent"
    val hours = diff / 3600000L
    val minutes = diff / 60000L
    val text = when {
        diff <= 0 -> "已截止"
        diff < 60000 -> "不足1分钟"
        diff > 72*3600000L -> "剩余 ${diff/86400000L}天"
        diff > 24*3600000L -> "${hours/24}天" + (if (hours%24>0) "${hours%24}小时" else "")
        else -> (if (minutes>=60) "${minutes/60}小时" else "") + (if (minutes%60>0) "${minutes%60}分" else "")
    }
    return HomeworkCountdown(text, tone, if (diff>0 && tone!="normal") "即将截止" else "")
}

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

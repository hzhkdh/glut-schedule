package com.glut.schedule.service.chaoxing

enum class HomeworkState { TODO, DONE, UNKNOWN }

// 无法确认的状态单独保留，避免错误取消截止提醒或误报已完成。
fun homeworkState(status: String): HomeworkState = when (status.trim()) {
    "未交", "未开始", "待互评", "未讨论" -> HomeworkState.TODO
    "已完成", "待批阅", "已批阅", "已讨论", "已提交" -> HomeworkState.DONE
    else -> HomeworkState.UNKNOWN
}

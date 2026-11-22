package com.glut.schedule.ui.navigation

import androidx.compose.runtime.saveable.listSaver
import com.glut.schedule.service.campus.SchoolOverviewTopic

internal enum class SchoolOverviewDestination(val topic: SchoolOverviewTopic?) {
    LEADERS(SchoolOverviewTopic.LEADERS),
    HISTORY(SchoolOverviewTopic.HISTORY),
    SPIRIT(SchoolOverviewTopic.SPIRIT),
    CAMPUS_INFO(null);

    val label: String
        get() = topic?.title ?: "校园信息"
}

internal data class SchoolOverviewNavigation(
    val destination: SchoolOverviewDestination = SchoolOverviewDestination.LEADERS,
    val lastDocument: SchoolOverviewDestination = SchoolOverviewDestination.LEADERS
) {
    // 底栏记住离开前的资料主题，返回学校资料时恢复对应标签。
    fun open(target: SchoolOverviewDestination): SchoolOverviewNavigation =
        copy(destination = target, lastDocument = if (target.topic != null) target else lastDocument)

    fun showDocuments(): SchoolOverviewNavigation = open(lastDocument)

    companion object {
        val Saver = listSaver<SchoolOverviewNavigation, String>(
            save = { listOf(it.destination.name, it.lastDocument.name) },
            restore = { SchoolOverviewNavigation(SchoolOverviewDestination.valueOf(it[0]), SchoolOverviewDestination.valueOf(it[1])) }
        )
    }
}

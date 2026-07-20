package com.glut.schedule.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.glut.schedule.MainActivity
import java.time.format.DateTimeFormatter

private val WidgetCard = ColorProvider(Color(0xFFFFFEFB))
private val WidgetPrimary = ColorProvider(Color(0xFF141821))
private val WidgetSecondary = ColorProvider(Color(0xFF667085))
private val WidgetAccent = ColorProvider(Color(0xFF3F7DF6))
private val WidgetSoft = ColorProvider(Color(0xFFEAF1FF))
private val WidgetDivider = ColorProvider(Color(0xFFE2E7EC))
private val WidgetFallbackCourse = ColorProvider(Color(0xFF3F7DF6))

private val TitleStyle = TextStyle(color = WidgetPrimary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
private val BodyStyle = TextStyle(color = WidgetSecondary, fontSize = 11.sp)
private val SmallStyle = TextStyle(color = WidgetSecondary, fontSize = 10.sp)

class CompactTodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = ScheduleWidgetDataSource(context).load()
        provideContent { WidgetSurface(context) { CompactTodayContent(snapshot) } }
    }
}

class TodayTomorrowWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = ScheduleWidgetDataSource(context).load()
        provideContent { WidgetSurface(context) { TodayTomorrowContent(snapshot) } }
    }
}

class ColorTimelineWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = ScheduleWidgetDataSource(context).load()
        provideContent { WidgetSurface(context) { ColorTimelineContent(snapshot) } }
    }
}

class RefreshScheduleWidgetsAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        ScheduleWidgetUpdater.updateAll(context)
    }
}

@Composable
private fun WidgetSurface(context: Context, content: @Composable () -> Unit) {
    Box(
        modifier = GlanceModifier.fillMaxSize()
            .background(WidgetCard)
            .cornerRadius(20.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java)))
            .padding(16.dp)
    ) {
        content()
    }
}

@Composable
private fun CompactTodayContent(snapshot: WidgetScheduleSnapshot) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        WidgetHeader(snapshot, "今日课程")
        Spacer(GlanceModifier.height(10.dp))
        when (snapshot.status) {
            WidgetScheduleStatus.READY -> CourseList(snapshot.todayCourses, limit = 3)
            WidgetScheduleStatus.NO_COURSES -> NoCourseContent(snapshot.nextCourse)
            else -> StatusContent(snapshot.status)
        }
    }
}

@Composable
private fun TodayTomorrowContent(snapshot: WidgetScheduleSnapshot) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        WidgetHeader(snapshot, "近期课程")
        Spacer(GlanceModifier.height(10.dp))
        if (snapshot.status == WidgetScheduleStatus.NO_DATA ||
            snapshot.status == WidgetScheduleStatus.BEFORE_SEMESTER ||
            snapshot.status == WidgetScheduleStatus.OUTSIDE_SEMESTER ||
            snapshot.status == WidgetScheduleStatus.READ_ERROR
        ) {
            StatusContent(snapshot.status)
        } else {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                DayColumn("今天", snapshot.todayCourses, GlanceModifier.defaultWeight())
                Spacer(GlanceModifier.width(8.dp))
                Spacer(GlanceModifier.width(1.dp).height(92.dp).background(WidgetDivider))
                Spacer(GlanceModifier.width(8.dp))
                DayColumn("明天", snapshot.tomorrowCourses, GlanceModifier.defaultWeight())
            }
        }
    }
}

@Composable
private fun ColorTimelineContent(snapshot: WidgetScheduleSnapshot) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        WidgetHeader(snapshot, "日视图")
        Spacer(GlanceModifier.height(9.dp))
        when (snapshot.status) {
            WidgetScheduleStatus.READY -> snapshot.todayCourses.take(3).forEachIndexed { index, course ->
                TimelineCourse(course)
                if (index != snapshot.todayCourses.take(3).lastIndex) Spacer(GlanceModifier.height(6.dp))
            }
            WidgetScheduleStatus.NO_COURSES -> NoCourseContent(snapshot.nextCourse)
            else -> StatusContent(snapshot.status)
        }
    }
}

@Composable
private fun WidgetHeader(snapshot: WidgetScheduleSnapshot, label: String) {
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(label, style = TitleStyle, maxLines = 1)
            Text(snapshot.today.format(DateTimeFormatter.ofPattern("M月d日")), style = SmallStyle, maxLines = 1)
        }
        Text("第 ${snapshot.currentWeek} 周 · ${snapshot.today.chineseDayOfWeek()}", style = BodyStyle, maxLines = 1)
        Spacer(GlanceModifier.width(8.dp))
        Text(
            "刷新",
            modifier = GlanceModifier.clickable(actionRunCallback<RefreshScheduleWidgetsAction>()),
            style = TextStyle(color = WidgetAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold),
            maxLines = 1
        )
    }
}

@Composable
private fun DayColumn(label: String, courses: List<WidgetCourseItem>, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(label, style = BodyStyle, maxLines = 1)
        Spacer(GlanceModifier.height(7.dp))
        if (courses.isEmpty()) {
            Text("没有课", style = SmallStyle, maxLines = 1)
        } else {
            CourseList(courses, limit = 2)
        }
    }
}

@Composable
private fun CourseList(courses: List<WidgetCourseItem>, limit: Int) {
    Column {
        courses.take(limit).forEachIndexed { index, course ->
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = GlanceModifier.width(5.dp).height(34.dp)
                        .background(course.colorProvider())
                        .cornerRadius(3.dp)
                ) {}
                Spacer(GlanceModifier.width(8.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(course.title, style = TitleStyle, maxLines = 1)
                    Text(course.courseMeta(), style = SmallStyle, maxLines = 1)
                }
            }
            if (index != courses.take(limit).lastIndex) Spacer(GlanceModifier.height(7.dp))
        }
    }
}

@Composable
private fun TimelineCourse(course: WidgetCourseItem) {
    Column(
        modifier = GlanceModifier.fillMaxWidth()
            .background(course.colorProvider())
            .cornerRadius(12.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth()) {
            Text(
                course.title,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1
            )
            Text(
                course.timeLabel(),
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 10.sp),
                maxLines = 1
            )
        }
        if (course.room.isNotBlank()) {
            Text(
                course.room,
                style = TextStyle(color = ColorProvider(Color(0xE6FFFFFF)), fontSize = 10.sp),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun NoCourseContent(nextCourse: WidgetCourseItem?) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("今天没有课", style = TitleStyle, maxLines = 1)
        Text("好好安排自己的时间吧", style = BodyStyle, maxLines = 1)
        if (nextCourse != null) {
            Spacer(GlanceModifier.height(10.dp))
            Row(
                modifier = GlanceModifier.fillMaxWidth().background(WidgetSoft).cornerRadius(10.dp).padding(9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text("下一节", style = SmallStyle, maxLines = 1)
                    Text(nextCourse.title, style = TitleStyle, maxLines = 1)
                }
                Text("${nextCourse.date.chineseDayOfWeek()} ${nextCourse.timeLabel()}", style = TextStyle(color = WidgetAccent, fontSize = 10.sp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun StatusContent(status: WidgetScheduleStatus) {
    val title: String
    val detail: String
    when (status) {
        WidgetScheduleStatus.NO_DATA -> {
            title = "还没有课表"
            detail = "点击打开 App 导入课表"
        }
        WidgetScheduleStatus.OUTSIDE_SEMESTER -> {
            title = "假期中"
            detail = "点击打开 App 查看学期设置"
        }
        WidgetScheduleStatus.BEFORE_SEMESTER -> {
            title = "学期尚未开始"
            detail = "点击打开 App 查看开学日期"
        }
        WidgetScheduleStatus.READ_ERROR -> {
            title = "暂时无法读取课表"
            detail = "点击打开 App 重试"
        }
        else -> {
            title = "今天没有课"
            detail = "好好安排自己的时间吧"
        }
    }
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = TitleStyle, maxLines = 1)
        Spacer(GlanceModifier.height(5.dp))
        Text(detail, style = BodyStyle, maxLines = 2)
    }
}

private fun WidgetCourseItem.courseMeta(): String {
    return listOf(timeLabel(), room).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun WidgetCourseItem.timeLabel(): String {
    return if (startTime.isNotBlank() && endTime.isNotBlank()) "$startTime–$endTime"
    else "第 $startSection–$endSection 节"
}

private fun WidgetCourseItem.colorProvider(): ColorProvider {
    val color = runCatching { Color(colorHex.toColorInt()) }.getOrNull()
    return color?.let(::ColorProvider) ?: WidgetFallbackCourse
}

private fun java.time.LocalDate.chineseDayOfWeek(): String {
    return listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[dayOfWeek.value - 1]
}

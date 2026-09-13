package com.glut.schedule.service.greeting

import com.glut.schedule.data.model.ExamInfo
import com.glut.schedule.service.holiday.CalendarDayInfo
import com.glut.schedule.service.holiday.CalendarDayKind
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.random.Random

data class DrawerGreetingContext(
    val studentName: String,
    val exams: List<ExamInfo>,
    val now: LocalDateTime,
    val semesterStart: LocalDate?,
    val semesterEnd: LocalDate?,
    val calendarDay: CalendarDayInfo = CalendarDayInfo(CalendarDayKind.UNKNOWN)
)

data class DrawerGreeting(
    val text: String,
    val category: GreetingCategory?,
    val animate: Boolean
)

class DrawerGreetingPlanner(
    private val random: Random = Random.Default,
    // 分类抽签独立注入，便于两端用精确边界验证同一套概率规则。
    private val categoryRoll: () -> Double = { random.nextDouble() }
) {
    fun next(
        context: DrawerGreetingContext,
        templates: GreetingTemplateSet,
        previousText: String = ""
    ): DrawerGreeting {
        val categories = eligibleCategories(context, templates)
        if (categories.isEmpty()) {
            return DrawerGreeting(STATIC_SLOGAN, null, false)
        }

        val renderedByCategory = categories.associateWith { category ->
            templates.forCategory(category)
                .map { render(it, category, context) }
                .filter { it.isNotBlank() }
        }
        val candidates = renderedByCategory.filterValues { it.isNotEmpty() }
        if (candidates.isEmpty()) return DrawerGreeting(STATIC_SLOGAN, null, false)

        val greeting = GreetingCategory.GREETING.takeIf { it in candidates }
        val contextual = candidates.keys.firstOrNull { it != GreetingCategory.GREETING }
        val category = when {
            greeting != null && contextual != null -> {
                if (categoryRoll().coerceIn(0.0, 1.0) < contextualProbability(contextual)) {
                    contextual
                } else {
                    greeting
                }
            }
            contextual != null -> contextual
            else -> greeting ?: return DrawerGreeting(STATIC_SLOGAN, null, false)
        }
        val allTexts = candidates.getValue(category)
        // 防重复只在已经选中的分类内部生效，不能反向改变 20%/30%/50% 的分类权重。
        val texts = allTexts.filterNot { it == previousText }.ifEmpty { allTexts }
        return DrawerGreeting(
            text = texts[random.nextInt(texts.size)],
            category = category,
            // 动态问候在每次打开侧边栏时都重新播放，运行 ID 负责重启动画协程。
            animate = true
        )
    }

    private fun contextualProbability(category: GreetingCategory): Double = when (category) {
        GreetingCategory.EXAM_TODAY -> 0.30
        GreetingCategory.EXAM_TOMORROW -> 0.50
        else -> 0.20
    }

    fun eligibleCategories(
        context: DrawerGreetingContext,
        templates: GreetingTemplateSet
    ): List<GreetingCategory> {
        val today = context.now.toLocalDate()
        val unfinished = context.exams
            .asSequence()
            .filter { !it.examDate.isBefore(today) }
            .filter { exam ->
                exam.examDate != today || exam.endTime.toLocalTimeOrNull()?.let {
                    context.now.toLocalTime().isBefore(it)
                } != false
            }
            .sortedWith(compareBy<ExamInfo> { it.examDate }.thenBy { it.startTime })
            .toList()

        val upcomingDays = unfinished.firstOrNull()?.let {
            ChronoUnit.DAYS.between(today, it.examDate)
        }
        val start = context.semesterStart
        val end = context.semesterEnd
        val semesterCategory = if (
            start != null && end != null && !today.isBefore(start) && !today.isAfter(end)
        ) {
            if (ChronoUnit.DAYS.between(today, end) <= 30L) {
                GreetingCategory.SEMESTER_ENDING
            } else {
                GreetingCategory.SEMESTER_WEEK
            }
        } else {
            null
        }
        // 同时命中多个节点时只保留最相关的一项，避免 20% 节点池再次稀释提醒。
        val contextual = when {
            unfinished.any { it.examDate == today } -> GreetingCategory.EXAM_TODAY
            unfinished.any { it.examDate == today.plusDays(1) } -> GreetingCategory.EXAM_TOMORROW
            context.now.hour in 0..4 -> GreetingCategory.LATE_NIGHT
            context.calendarDay.kind == CalendarDayKind.HOLIDAY &&
                context.calendarDay.holidayName.isNotBlank() -> GreetingCategory.HOLIDAY
            today.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) &&
                context.calendarDay.kind != CalendarDayKind.ADJUSTED_WORKDAY -> GreetingCategory.WEEKEND
            upcomingDays in 2L..7L -> GreetingCategory.EXAM_UPCOMING
            else -> semesterCategory
        }?.takeIf { templates.forCategory(it).isNotEmpty() }

        return buildList {
            if (
                context.studentName.isNotBlank() &&
                templates.forCategory(GreetingCategory.GREETING).isNotEmpty()
            ) {
                add(GreetingCategory.GREETING)
            }
            contextual?.let(::add)
        }
    }

    fun periodFor(now: LocalDateTime): String = when (now.hour) {
        in 5..7 -> "清晨"
        in 8..10 -> "早上"
        in 11..13 -> "中午"
        in 14..17 -> "下午"
        else -> "晚上"
    }

    private fun render(
        template: String,
        category: GreetingCategory,
        context: DrawerGreetingContext
    ): String {
        val exam = relevantExam(category, context)
        val today = context.now.toLocalDate()
        val days = when (category) {
            GreetingCategory.EXAM_UPCOMING -> exam?.let { ChronoUnit.DAYS.between(today, it.examDate) }
            GreetingCategory.SEMESTER_ENDING -> context.semesterEnd?.let { ChronoUnit.DAYS.between(today, it) }
            else -> null
        }
        val week = context.semesterStart?.let {
            ChronoUnit.DAYS.between(it, today).coerceAtLeast(0) / 7 + 1
        }
        return template
            .replace("{name}", context.studentName.abbreviate(10))
            .replace("{period}", periodFor(context.now))
            .replace("{course}", exam?.courseName.orEmpty().abbreviate(14))
            .replace("{days}", days?.toString().orEmpty())
            .replace("{week}", week?.toString().orEmpty())
            .replace("{holiday}", context.calendarDay.holidayName.abbreviate(10))
            .trim()
    }

    private fun relevantExam(
        category: GreetingCategory,
        context: DrawerGreetingContext
    ): ExamInfo? {
        val today = context.now.toLocalDate()
        return context.exams
            .asSequence()
            .filter { exam ->
                when (category) {
                    GreetingCategory.EXAM_TODAY ->
                        exam.examDate == today && isUnfinishedToday(exam, context.now.toLocalTime())
                    GreetingCategory.EXAM_TOMORROW -> exam.examDate == today.plusDays(1)
                    GreetingCategory.EXAM_UPCOMING ->
                        ChronoUnit.DAYS.between(today, exam.examDate) in 2L..7L
                    else -> false
                }
            }
            .sortedWith(compareBy<ExamInfo> { it.examDate }.thenBy { it.startTime })
            .firstOrNull()
    }

    private fun isUnfinishedToday(exam: ExamInfo, now: LocalTime): Boolean =
        exam.endTime.toLocalTimeOrNull()?.let(now::isBefore) ?: true

    private fun String.toLocalTimeOrNull(): LocalTime? =
        runCatching { LocalTime.parse(trim()) }.getOrNull()

    private fun String.abbreviate(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength - 1) + "…"

    companion object {
        const val STATIC_SLOGAN = "简单 高效 纯粹"
    }
}

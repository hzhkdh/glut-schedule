package com.glut.schedule.service.greeting

import com.glut.schedule.data.model.ExamInfo
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
    val semesterEnd: LocalDate?
)

data class DrawerGreeting(
    val text: String,
    val category: GreetingCategory?,
    val animate: Boolean
)

class DrawerGreetingPlanner(
    private val random: Random = Random.Default
) {
    fun next(
        context: DrawerGreetingContext,
        templates: GreetingTemplateSet,
        previousText: String = "",
        lastAnimatedAtEpochMillis: Long? = null,
        nowEpochMillis: Long = System.currentTimeMillis()
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
        val withoutPrevious = renderedByCategory.mapValues { (_, values) ->
            values.filterNot { it == previousText }
        }.filterValues { it.isNotEmpty() }
        val candidates = withoutPrevious.ifEmpty {
            renderedByCategory.filterValues { it.isNotEmpty() }
        }
        if (candidates.isEmpty()) return DrawerGreeting(STATIC_SLOGAN, null, false)

        // 先等概率选择分类、再选择分类内模板，避免模板条数较多的分类天然占优。
        val category = candidates.keys.elementAt(random.nextInt(candidates.size))
        val texts = candidates.getValue(category)
        return DrawerGreeting(
            text = texts[random.nextInt(texts.size)],
            category = category,
            animate = shouldAnimate(nowEpochMillis, lastAnimatedAtEpochMillis)
        )
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

        if (unfinished.any { it.examDate == today }) {
            return categoryIfAvailable(GreetingCategory.EXAM_TODAY, templates)
        }
        if (unfinished.any { it.examDate == today.plusDays(1) }) {
            return categoryIfAvailable(GreetingCategory.EXAM_TOMORROW, templates)
        }

        return buildList {
            if (context.studentName.isNotBlank()) {
                addIfAvailable(GreetingCategory.GREETING, templates)
            }
            val upcomingDays = unfinished.firstOrNull()?.let {
                ChronoUnit.DAYS.between(today, it.examDate)
            }
            if (upcomingDays in 2L..7L) {
                addIfAvailable(GreetingCategory.EXAM_UPCOMING, templates)
            }
            val start = context.semesterStart
            val end = context.semesterEnd
            if (start != null && end != null && !today.isBefore(start) && !today.isAfter(end)) {
                val remainingDays = ChronoUnit.DAYS.between(today, end)
                addIfAvailable(
                    if (remainingDays <= 30L) GreetingCategory.SEMESTER_ENDING
                    else GreetingCategory.SEMESTER_WEEK,
                    templates
                )
            }
        }
    }

    fun shouldAnimate(nowEpochMillis: Long, lastAnimatedAtEpochMillis: Long?): Boolean =
        lastAnimatedAtEpochMillis == null ||
            nowEpochMillis - lastAnimatedAtEpochMillis >= ANIMATION_COOLDOWN_MILLIS

    fun periodFor(now: LocalDateTime): String = when (now.hour) {
        in 5..10 -> "早上"
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

    private fun MutableList<GreetingCategory>.addIfAvailable(
        category: GreetingCategory,
        templates: GreetingTemplateSet
    ) {
        if (templates.forCategory(category).isNotEmpty()) add(category)
    }

    private fun categoryIfAvailable(
        category: GreetingCategory,
        templates: GreetingTemplateSet
    ): List<GreetingCategory> =
        if (templates.forCategory(category).isEmpty()) emptyList() else listOf(category)

    private fun String.toLocalTimeOrNull(): LocalTime? =
        runCatching { LocalTime.parse(trim()) }.getOrNull()

    private fun String.abbreviate(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength - 1) + "…"

    companion object {
        const val STATIC_SLOGAN = "简单 高效 纯粹"
        const val ANIMATION_COOLDOWN_MILLIS = 15_000L
    }
}

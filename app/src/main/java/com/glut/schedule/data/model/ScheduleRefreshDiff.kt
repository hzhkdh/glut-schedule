package com.glut.schedule.data.model

/**
 * 刷新课表前后的差异。
 *
 * 与小程序 `pages/schedule/schedule.js` 的 `buildRefreshDiff` **逐条同口径**：
 * 分组键、签名、文案模板、摘要格式、条目顺序都刻意保持一致，两端刷新后看到的变化说明
 * 才是同一件事。改这里时请同步改小程序那边。
 */

/** 一条变化。 */
data class ScheduleRefreshDiffItem(
    val id: String,
    val kind: Kind,
    val title: String,
    /** 变化前的时段文案；新增时为空。 */
    val beforeText: String,
    /** 变化后的时段文案；移除时为空。 */
    val afterText: String
) {
    enum class Kind(val label: String) {
        ADDED("新增"),
        REMOVED("移除"),
        CHANGED("调整")
    }
}

/** 一次刷新的差异结果。 */
data class ScheduleRefreshDiff(
    val summary: String,
    val items: List<ScheduleRefreshDiffItem>
) {
    val hasChanges: Boolean get() = items.isNotEmpty()
}

/**
 * 比对两份课表。
 *
 * 分组用「课程名 + 教师」（不含教室与时段），所以同一门课换教室/改时间会被判成**调整**而不是
 * 「删了一门又加了一门」——这正是用户想看到的效果。
 */
fun buildScheduleRefreshDiff(
    oldCourses: List<ScheduleCourse>,
    newCourses: List<ScheduleCourse>
): ScheduleRefreshDiff {
    val oldGroups = oldCourses.groupByIdentity()
    val newGroups = newCourses.groupByIdentity()
    val identities = (oldGroups.keys + newGroups.keys).sorted()

    val added = mutableListOf<ScheduleRefreshDiffItem>()
    val removed = mutableListOf<ScheduleRefreshDiffItem>()
    val changed = mutableListOf<ScheduleRefreshDiffItem>()

    identities.forEach { identity ->
        val before = oldGroups[identity].orEmpty()
        val after = newGroups[identity].orEmpty()
        val title = (after.firstOrNull() ?: before.firstOrNull())?.title.orEmpty().ifBlank { "未命名课程" }
        when {
            before.isEmpty() -> added += diffItem(
                ScheduleRefreshDiffItem.Kind.ADDED, title, "", courseScheduleText(after), identity
            )

            after.isEmpty() -> removed += diffItem(
                ScheduleRefreshDiffItem.Kind.REMOVED, title, courseScheduleText(before), "", identity
            )

            courseScheduleSignature(before) != courseScheduleSignature(after) -> changed += diffItem(
                ScheduleRefreshDiffItem.Kind.CHANGED,
                title,
                courseScheduleText(before),
                courseScheduleText(after),
                identity
            )
        }
    }

    val items = added + removed + changed
    val summary = if (items.isEmpty()) {
        "课表已刷新，无变化"
    } else {
        "新增 ${added.size} 项 · 移除 ${removed.size} 项 · 调整 ${changed.size} 项"
    }
    return ScheduleRefreshDiff(summary = summary, items = items)
}

private fun List<ScheduleCourse>.groupByIdentity(): Map<String, List<ScheduleCourse>> =
    groupBy { "${it.title.trim()}|${it.teacher.trim()}" }

private fun diffItem(
    kind: ScheduleRefreshDiffItem.Kind,
    title: String,
    beforeText: String,
    afterText: String,
    identity: String
) = ScheduleRefreshDiffItem(
    id = "${kind.name.lowercase()}|$identity",
    kind = kind,
    title = title,
    beforeText = beforeText,
    afterText = afterText
)

/**
 * 一门课全部时段的指纹。
 *
 * 只关心「排在什么时候、在哪儿」，所以不含课程名与教师（分组键里已经有了）。
 * 教室优先取该课次自己的 `note`（解析器把教室写在这里），回退到课程级 `room`。
 */
private fun courseScheduleSignature(courses: List<ScheduleCourse>): String =
    courses.flatMap { course ->
        course.occurrences.map { occurrence ->
            listOf(
                occurrence.weekText,
                occurrence.dayOfWeek.toString(),
                occurrence.startSection.toString(),
                occurrence.endSection.toString(),
                occurrence.note.ifBlank { course.room }.trim()
            ).joinToString("|")
        }
    }.sorted().joinToString("||")

/** 「周三 第5-6节 · 04105」；多时段只列第一个再缀以总数；没有排课时间则如实说明。 */
private fun courseScheduleText(courses: List<ScheduleCourse>): String {
    val slots = courses.flatMap { course ->
        course.occurrences.map { occurrence ->
            val day = WEEKDAY_CHARS.getOrNull(occurrence.dayOfWeek - 1) ?: '?'
            val room = occurrence.note.ifBlank { course.room }.trim()
            "周$day 第${occurrence.startSection}-${occurrence.endSection}节" +
                if (room.isNotEmpty()) " · $room" else ""
        }
    }
    return when {
        slots.isEmpty() -> "无固定排课时间"
        slots.size == 1 -> slots.first()
        else -> "${slots.first()} 等 ${slots.size} 个时段"
    }
}

private val WEEKDAY_CHARS = listOf('一', '二', '三', '四', '五', '六', '日')

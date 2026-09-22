package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.data.model.academicWeeksForText
import com.glut.schedule.data.model.normalizeRoomKey
import com.glut.schedule.data.model.normalizeTitleKey
import java.security.MessageDigest

/**
 * 用大节课表（`showTimetable.do`）的元数据把**每个课次的教师**绑定到教室。
 *
 * ## 为什么需要它
 *
 * 一门课由多位老师分担时（理论课 A、实验课 B），个人课表 `currcourse.jsdo` 的「任课教师」
 * 单元格把老师并列写成多个链接，而「上课时间、地点」是另一个独立的嵌套表
 * （周次/星期/节次/教室）——**教师与教室完全脱钩**，从这一页推不出谁教哪一节。
 * 唯一带映射的是大节课表，它的每个格是 `<<课程名>>;N 教室 教师 周次 学时类型`：
 *
 * ```
 * <<微机原理与接口技术>>;1 06104D 蒋志军 1-10周 讲课学时
 * <<微机原理与接口技术>>;1 014102S 陈守学 2-1 第11周 实验学时
 * ```
 *
 * ## 口径（必须与小程序 `utils/parser.js` 的 `bindCourseTeachers` 逐条一致）
 *
 * 1. 匹配键 `(normalizeTitleKey(课程名), normalizeRoomKey(教室))`；教室取 `occurrence.note`，
 *    为空时退回 `course.room`。**教室为空不做通配**。
 * 2. 同一键出现多个不同教师时，先用「周次交集」缩小（`occurrence.weekText` ∩ 元数据课次的
 *    weekText）。这个键偏偏不含周次，而「同一教室前半学期 A 老师、后半学期 B 老师」是常见
 *    排法——实测两个桂林账号各 5 个学期、共 22 个这样的课次，weekText 全部可解析且按周次
 *    交集 100% 唯一确定。只有**双方都给出具体周次**时才算证据：空 weekText 会被
 *    `academicWeeksForText` 展开成全部周次，拿它去求交集等于凭空替课次断言了周次。
 * 3. 周次仍定不下来时，退回「不含内部空白的教师名」（个人课表那条多链接拼接行会混进
 *    元数据，只有单名才是可用的教师标识）；仍不唯一则**判定为未解析**——宁可不动，不可猜错。
 * 4. 未解析的课次**沿用原 teacher**，绝不改成「待确认」，那会让现状变差。
 * 5. 同一门课的课次解析出不同教师时拆成多个课程实体（`CourseOccurrence` 没有 teacher
 *    字段，模型层不支持课次级教师）。
 * 6. 元数据为空、或没有任何课次需要改动时，返回**入参同一实例**——调用方与既有测试
 *    大量使用「解析结果等于输入」的断言，无条件重建列表会让它们全部失效。
 */
internal object CourseTeacherBinder {

    fun bind(
        courses: List<ScheduleCourse>,
        metadataCourses: List<ScheduleCourse>
    ): List<ScheduleCourse> {
        if (courses.isEmpty() || metadataCourses.isEmpty()) return courses

        val index = buildMetadataIndex(metadataCourses)
        if (index.isEmpty()) return courses

        var changed = false
        val bound = courses.flatMap { course ->
            val result = bindCourse(course, index)
            if (result.size != 1 || result[0] !== course) changed = true
            result
        }
        return if (changed) bound else courses
    }

    /**
     * 候选教师 + 它在元数据里出现过的周次。
     *
     * 索引项从「教师名」升级成这个结构，是为了让周次消歧能按候选分别比对周次；
     * 与小程序 `bindCourseTeachers` 里 `{ teacher, weeks }` 的索引项一一对应。
     */
    private class TeacherCandidate(val teacher: String) {
        val weeks = mutableSetOf<Int>()
    }

    /** `(课程名, 教室) -> 该键下出现过的全部候选教师`。 */
    private fun buildMetadataIndex(
        metadataCourses: List<ScheduleCourse>
    ): Map<Pair<String, String>, List<TeacherCandidate>> {
        val index = linkedMapOf<Pair<String, String>, MutableList<TeacherCandidate>>()
        metadataCourses.forEach { course ->
            val teacher = course.teacher.trim()
            if (teacher.isEmpty()) return@forEach
            // 元数据课程自身可能挂多个教室（复用了个人课表的按教室拆分结果），逐个登记；
            // 少数实习/教育课没有课次，退回课程自身的教室，周次按「未写明」处理（与小程序一致）。
            val roomWeeks = course.occurrences
                .map { it.note.ifBlank { course.room } to it.weekText }
                .ifEmpty { listOf(course.room to "") }
            roomWeeks.forEach roomLoop@{ (room, weekText) ->
                // 教室未知的块（南宁的体育课就没有教室）不登记：空教室不是可用的绑定键，
                // 让它进索引会把「同样没有教室」的课配上一个错误的教师。
                if (room.isBlank()) return@roomLoop
                val candidates = index.getOrPut(key(course.title, room)) { mutableListOf() }
                val candidate = candidates.firstOrNull { it.teacher == teacher }
                    ?: TeacherCandidate(teacher).also { candidates += it }
                candidate.weeks += academicWeeksForText(weekText)
            }
        }
        return index
    }

    private fun key(title: String, room: String): Pair<String, String> =
        normalizeTitleKey(title) to normalizeRoomKey(room)

    /** 绑定单门课；需要拆分时返回多个实体，否则返回只含原实例的列表。 */
    private fun bindCourse(
        course: ScheduleCourse,
        index: Map<Pair<String, String>, List<TeacherCandidate>>
    ): List<ScheduleCourse> {
        if (course.occurrences.isEmpty()) return listOf(course)
        val originalTeacher = course.teacher.trim()

        val resolved = course.occurrences.associateWith { occurrence ->
            resolveTeacher(course, occurrence, index)
        }
        // 一格都没解析出来：整门课原样保留。
        if (resolved.values.all { it == null }) return listOf(course)

        fun desiredTeacher(occurrence: CourseOccurrence): String =
            resolved[occurrence] ?: originalTeacher

        val groups = course.occurrences.groupBy { occurrence ->
            normalizeRoomKey(roomOf(course, occurrence)) to desiredTeacher(occurrence)
        }
        if (groups.size == 1) {
            val teacher = desiredTeacher(course.occurrences.first())
            // 教室与拆分分支保持同一口径：以课次的教室为准，缺了才退回课程自身的。
            val room = roomOf(course, course.occurrences.first()).ifBlank { course.room }
            if (teacher == originalTeacher && room == course.room) return listOf(course)
            return listOf(course.copy(room = room, teacher = teacher.ifBlank { "待确认" }))
        }

        return groups.entries.map { (groupKey, occurrences) ->
            val (_, teacher) = groupKey
            val room = roomOf(course, occurrences.first())
            val id = boundCourseId(course.title, teacher, room)
            course.copy(
                room = room.ifBlank { course.room },
                teacher = teacher.ifBlank { "待确认" },
                id = id,
                // 先排序再编号：否则导入顺序变化会让 occurrence id 漂移。
                occurrences = occurrences
                    .sortedWith(
                        compareBy({ it.dayOfWeek }, { it.startSection }, { it.endSection }, { it.weekText })
                    )
                    .mapIndexed { position, occurrence ->
                        occurrence.copy(id = "$id-occurrence-$position", courseId = id)
                    }
            )
        }
    }

    private fun roomOf(course: ScheduleCourse, occurrence: CourseOccurrence): String =
        occurrence.note.ifBlank { course.room }

    /**
     * 解析某节课的授课教师；没有把握时返回 null，由调用方沿用原值。
     *
     * 顺序是「唯一候选 → 周次交集 → 唯一单名」：周次是证据，单名只是启发式，
     * 证据要排在启发式前面。
     */
    private fun resolveTeacher(
        course: ScheduleCourse,
        occurrence: CourseOccurrence,
        index: Map<Pair<String, String>, List<TeacherCandidate>>
    ): String? {
        val room = roomOf(course, occurrence)
        // 两边都没有教室时不匹配——「都不知道教室」不构成任何证据。
        if (room.isBlank()) return null
        val candidates = index[key(course.title, room)] ?: return null
        if (candidates.size == 1) return candidates.first().teacher

        // 同一个 (课程名, 教室) 挂到多位老师时，用周次交集区分——这最常见于
        // 「前半学期 A 老师、后半学期 B 老师」共用同一间教室。
        //
        // 只有**课次自己写明了具体周次**时才算证据：academicWeeksForText("") 会展开成
        // 全部周次，拿它去求交集会把「另一个候选恰好周次解析不出来」变成一次假命中。
        //
        // 这里刻意**不**按「名字里有没有空格」过滤：周次回答的是「这一周谁上」，与名字
        // 形状无关。大节课表那一格真写着两位老师时本来就该原样呈现。
        if (hasSpecificWeeks(occurrence.weekText)) {
            val occurrenceWeeks = academicWeeksForText(occurrence.weekText).toSet()
            val byWeeks = candidates.filter { candidate ->
                candidate.weeks.isNotEmpty() && candidate.weeks.any(occurrenceWeeks::contains)
            }
            if (byWeeks.size == 1) return byWeeks.first().teacher
        }

        return candidates.filter { it.teacher.none(Char::isWhitespace) }.singleOrNull()?.teacher
    }

    /** 课次是否写明了「具体哪几周」；空 weekText 与「全周」都不构成可用来区分候选的证据。 */
    private fun hasSpecificWeeks(weekText: String): Boolean {
        val text = weekText.trim()
        return text.isNotEmpty() && text != "全周"
    }

    /** 与 `NanningCurrcourseParser` 的 `room-bound-` 同风格，避免与既有实体撞 id。 */
    private fun boundCourseId(title: String, teacher: String, room: String): String =
        "teacher-bound-" + md5("${normalizeTitleKey(title)}|$teacher|${normalizeRoomKey(room)}")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

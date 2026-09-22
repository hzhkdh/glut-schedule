package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
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
 * ## 口径（必须与小程序 `utils/courseTeacherBinder.js` 逐条一致）
 *
 * 1. 匹配键 `(normalizeTitleKey(课程名), normalizeRoomKey(教室))`；教室取 `occurrence.note`，
 *    为空时退回 `course.room`。**教室为空不做通配**。
 * 2. 同一键出现多个不同教师时，先挑「不含内部空白的教师名」（个人课表那条多链接拼接行
 *    会混进元数据，只有单名才是可用的教师标识）；仍不唯一则**判定为未解析**——
 *    宁可不动，不可猜错。
 * 3. 未解析的课次**沿用原 teacher**，绝不改成「待确认」，那会让现状变差。
 * 4. 同一门课的课次解析出不同教师时拆成多个课程实体（`CourseOccurrence` 没有 teacher
 *    字段，模型层不支持课次级教师）。
 * 5. 元数据为空、或没有任何课次需要改动时，返回**入参同一实例**——调用方与既有测试
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

    /** `(课程名, 教室) -> 该键下出现过的全部教师`。 */
    private fun buildMetadataIndex(
        metadataCourses: List<ScheduleCourse>
    ): Map<Pair<String, String>, List<String>> {
        val index = linkedMapOf<Pair<String, String>, MutableList<String>>()
        metadataCourses.forEach { course ->
            val teacher = course.teacher.trim()
            if (teacher.isEmpty()) return@forEach
            // 元数据课程自身可能挂多个教室（复用了个人课表的按教室拆分结果），逐个登记。
            val rooms = course.occurrences.map { it.note.ifBlank { course.room } }.ifEmpty { listOf(course.room) }
            rooms.forEach { room ->
                // 教室未知的块（南宁的体育课就没有教室）不登记：空教室不是可用的绑定键，
                // 让它进索引会把「同样没有教室」的课配上一个错误的教师。
                if (room.isBlank()) return@forEach
                val teachers = index.getOrPut(key(course.title, room)) { mutableListOf() }
                if (teacher !in teachers) teachers += teacher
            }
        }
        return index
    }

    private fun key(title: String, room: String): Pair<String, String> =
        normalizeTitleKey(title) to normalizeRoomKey(room)

    /** 绑定单门课；需要拆分时返回多个实体，否则返回只含原实例的列表。 */
    private fun bindCourse(
        course: ScheduleCourse,
        index: Map<Pair<String, String>, List<String>>
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
            if (teacher == originalTeacher) return listOf(course)
            return listOf(course.copy(teacher = teacher.ifBlank { "待确认" }))
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

    /** 解析某节课的授课教师；没有把握时返回 null，由调用方沿用原值。 */
    private fun resolveTeacher(
        course: ScheduleCourse,
        occurrence: CourseOccurrence,
        index: Map<Pair<String, String>, List<String>>
    ): String? {
        val room = roomOf(course, occurrence)
        // 两边都没有教室时不匹配——「都不知道教室」不构成任何证据。
        if (room.isBlank()) return null
        val candidates = index[key(course.title, room)] ?: return null
        if (candidates.size == 1) return candidates.first()
        val singleNames = candidates.filter { name -> name.none(Char::isWhitespace) }
        return singleNames.singleOrNull()
    }

    /** 与 `NanningCurrcourseParser` 的 `room-bound-` 同风格，避免与既有实体撞 id。 */
    private fun boundCourseId(title: String, teacher: String, room: String): String =
        "teacher-bound-" + md5("${normalizeTitleKey(title)}|$teacher|${normalizeRoomKey(room)}")

    private fun md5(value: String): String =
        MessageDigest.getInstance("MD5")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
}

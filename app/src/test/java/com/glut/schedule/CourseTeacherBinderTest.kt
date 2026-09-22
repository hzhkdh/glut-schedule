package com.glut.schedule

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.ScheduleCourse
import com.glut.schedule.service.parser.CourseTeacherBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按教室绑定教师（模式2 的教师归属修复）。
 *
 * 夹具用真实形态：《微机原理与接口技术》理论课在 06104D（蒋志军）、实验课在 014102S
 * （陈守学），而个人课表给整门课一个课程级教师串。
 */
class CourseTeacherBinderTest {

    @Test
    fun bindsEachRoomToItsOwnTeacherAndSplitsTheCourse() {
        val personal = course(
            id = "import-nn-1",
            title = "微机原理与接口技术",
            teacher = "蒋志军 陈守学 康燕萍",
            occurrences = listOf(
                occurrence("o1", day = 5, start = 1, end = 2, weekText = "1-10周", room = "06104D"),
                occurrence("o2", day = 5, start = 3, end = 4, weekText = "1-10周", room = "06408D"),
                occurrence("o3", day = 5, start = 1, end = 2, weekText = "11周", room = "014102S")
            )
        )
        val metadata = listOf(
            metadataCourse("微机原理与接口技术", "06104D", "蒋志军"),
            metadataCourse("微机原理与接口技术", "06408D", "蒋志军"),
            metadataCourse("微机原理与接口技术", "014102S", "陈守学")
        )

        val bound = CourseTeacherBinder.bind(listOf(personal), metadata)

        // 按 (教室, 教师) 分组：两个理论教室各自成卡，实验教室一张——与模式1 的
        // 2026春 实际输出（06104D→蒋志军、06408D→蒋志军、014102S→陈守学）完全一致。
        assertEquals(3, bound.size)
        val byRoom = bound.associateBy { it.room }
        assertEquals("蒋志军", byRoom.getValue("06104D").teacher)
        assertEquals("蒋志军", byRoom.getValue("06408D").teacher)
        assertEquals("陈守学", byRoom.getValue("014102S").teacher)
        bound.forEach { assertEquals(1, it.occurrences.size) }
        // 拆分后 id 必须与原实体不同，否则下游按 id 去重会把两张卡片并成一张
        bound.forEach { assertNotEquals(personal.id, it.id) }
        assertEquals(bound.size, bound.map { it.id }.distinct().size)
        // 拆分是"同一门课"，按课程名计门数仍是一门
        assertEquals(1, bound.map { it.title }.distinct().size)
    }

    @Test
    fun keepsTheInputInstanceWhenMetadataIsEmpty() {
        val personal = listOf(
            course("1", "微机原理与接口技术", "蒋志军 陈守学 康燕萍", listOf(
                occurrence("o1", 5, 1, 2, "1-10周", "06104D")
            ))
        )

        assertSame(personal, CourseTeacherBinder.bind(personal, emptyList()))
    }

    @Test
    fun keepsTheInputInstanceWhenNoTeacherChanges() {
        val personal = listOf(
            course("1", "数字逻辑", "卢佩", listOf(occurrence("o1", 2, 1, 2, "1-12周", "06408D")))
        )
        val metadata = listOf(metadataCourse("数字逻辑", "06408D", "卢佩"))

        assertSame(personal, CourseTeacherBinder.bind(personal, metadata))
    }

    @Test
    fun prefersTheSingleNameTeacherWhenMetadataAlsoCarriesAConcatenatedRow() {
        // 个人课表那条「蒋志军 陈守学 康燕萍」拼接行也会被解析进元数据，与真正的单名教师
        // 同名同教室；只有单名才是可用的教师标识。
        val personal = listOf(
            course("1", "微机原理与接口技术", "蒋志军 陈守学 康燕萍", listOf(
                occurrence("o1", 5, 1, 2, "11周", "014102S")
            ))
        )
        val metadata = listOf(
            metadataCourse("微机原理与接口技术", "014102S", "蒋志军 陈守学 康燕萍"),
            metadataCourse("微机原理与接口技术", "014102S", "陈守学")
        )

        assertEquals("陈守学", CourseTeacherBinder.bind(personal, metadata).single().teacher)
    }

    @Test
    fun fallsBackToTheOriginalTeacherWhenTheRoomHasTwoUnresolvableTeachers() {
        val personal = listOf(
            course("1", "微机原理与接口技术", "蒋志军 陈守学 康燕萍", listOf(
                occurrence("o1", 5, 1, 2, "11周", "014102S")
            ))
        )
        // 两个都是不含空白的单名 → 无法判断，宁可不动
        val metadata = listOf(
            metadataCourse("微机原理与接口技术", "014102S", "陈守学"),
            metadataCourse("微机原理与接口技术", "014102S", "敬超")
        )

        val bound = CourseTeacherBinder.bind(personal, metadata)

        assertSame(personal, bound)
        assertEquals("蒋志军 陈守学 康燕萍", bound.single().teacher)
    }

    @Test
    fun keepsTheOriginalTeacherWhenTheRoomIsNotInMetadata() {
        val personal = listOf(
            course("1", "微机原理与接口技术", "蒋志军 陈守学 康燕萍", listOf(
                occurrence("o1", 5, 1, 2, "1-10周", "06104D")
            ))
        )
        val metadata = listOf(metadataCourse("别的课", "06104D", "某人"))

        val bound = CourseTeacherBinder.bind(personal, metadata)

        assertSame(personal, bound)
        assertEquals("蒋志军 陈守学 康燕萍", bound.single().teacher)
        assertTrue(bound.none { it.teacher == "待确认" })
    }

    @Test
    fun matchesRoomsIgnoringWhitespaceAndCase() {
        val personal = listOf(
            course("1", "微机原理与接口技术", "蒋志军 陈守学 康燕萍", listOf(
                occurrence("o1", 5, 1, 2, "11周", " 014102s ")
            ))
        )
        val metadata = listOf(metadataCourse("微机原理与接口技术", "014102S", "陈守学"))

        assertEquals("陈守学", CourseTeacherBinder.bind(personal, metadata).single().teacher)
    }

    @Test
    fun matchesCourseTitlesIgnoringWhitespace() {
        val personal = listOf(
            course("1", "大学英语 4", "甲 乙", listOf(occurrence("o1", 2, 1, 2, "1-10周", "06403D")))
        )
        val metadata = listOf(metadataCourse("大学英语4", "06403D", "莫梓"))

        assertEquals("莫梓", CourseTeacherBinder.bind(personal, metadata).single().teacher)
    }

    @Test
    fun singleGroupBranchAlsoFillsTheRoomSoBothBranchesAgree() {
        // 课次的教室非空、课程自身的教室为空时，不拆分的路径也要补上教室，
        // 否则同一门课「拆与不拆」两种形态的卡片信息不同。
        val personal = listOf(
            course("1", "岩体力学", "甲 乙", listOf(occurrence("o1", 2, 1, 2, "1-10周", "5502D")))
                .copy(room = "")
        )
        val metadata = listOf(metadataCourse("岩体力学", "5502D", "王俊璇"))

        val bound = CourseTeacherBinder.bind(personal, metadata)

        assertEquals(1, bound.size)
        assertEquals("5502D", bound.single().room)
        assertEquals("王俊璇", bound.single().teacher)
    }

    /**
     * 南宁的体育课没有教室，大节课表的块里也就没有教室那一行。两侧教室都为空时**不得匹配**：
     * 「都不知道教室」不是任何证据，否则会给这种课配上一个来路不明的教师。
     */
    @Test
    fun neverBindsWhenBothSidesHaveNoRoom() {
        val personal = listOf(
            course("1", "体育（二）", "王行", listOf(occurrence("o1", 5, 1, 2, "1-10周", room = "")))
                .copy(room = "")
        )
        val metadata = listOf(metadataCourse("体育（二）", "", "陈守学"))

        val bound = CourseTeacherBinder.bind(personal, metadata)

        assertSame(personal, bound)
        assertEquals("王行", bound.single().teacher)
    }

    @Test
    fun fallsBackToCourseRoomWhenOccurrenceHasNoRoom() {
        val personal = listOf(
            course("1", "微机原理与接口技术", "蒋志军 陈守学 康燕萍", listOf(
                occurrence("o1", 5, 1, 2, "11周", room = "")
            )).copy(room = "014102S")
        )
        val metadata = listOf(metadataCourse("微机原理与接口技术", "014102S", "陈守学"))

        assertEquals("陈守学", CourseTeacherBinder.bind(personal, metadata).single().teacher)
    }

    // ---- 同一教室不同周次换老师：按周次消歧 ----
    //
    // 真实数据（2026-09-22 用两个桂林测试账号跑模式2 各 5 个学期）：22 个课次命中了
    // 「同一 课程名+教室 挂到多个教师」，weekText 全部可解析，且仅按周次交集就能 100%
    // 唯一确定。原先的键只到 (课程名, 教室)，遇到这种情况一律判为不确定 → 整门课沿用人
    // 课表那条多链接拼接的教师串，卡片上就出现两个名字。

    @Test
    fun bindsByWeekWhenTheSameRoomChangesTeacherMidSemester() {
        val personal = course("1", "大学英语", "王泽宇 王德成", listOf(
            occurrence("o1", 1, 3, 4, "1-8周", "04105"),
            occurrence("o2", 1, 3, 4, "9-16周", "04105")
        ))
        val metadata = listOf(
            metadataCourse("大学英语", "04105", "王泽宇", "1-8周"),
            metadataCourse("大学英语", "04105", "王德成", "9-16周")
        )

        val bound = CourseTeacherBinder.bind(listOf(personal), metadata)

        // 两个课次各归各的老师 → 拆成两张卡，谁都不再带拼接串
        assertEquals(2, bound.size)
        val byWeek = bound.associate { it.occurrences.single().weekText to it.teacher }
        assertEquals("王泽宇", byWeek.getValue("1-8周"))
        assertEquals("王德成", byWeek.getValue("9-16周"))
        bound.forEach { assertTrue("卡片上不允许出现两个名字: ${it.teacher}", it.teacher.none(Char::isWhitespace)) }
    }

    @Test
    fun usesTheWeekMatchEvenWhenThatCellNamesTwoTeachers() {
        // 用户口径：除非大节课表这一格确实是两位老师，否则同一张卡片不该出现两个名字。
        // 反过来，这一格真是「丙 丁」且只有它覆盖 1-8 周，那就该原样呈现，不能因为
        // 名字里有空格就改判成另一位只上 9-16 周的老师。
        val personal = course("1", "大学物理", "丙 丁", listOf(
            occurrence("o1", 3, 5, 6, "1-8周", "05201")
        ))
        val metadata = listOf(
            metadataCourse("大学物理", "05201", "丙 丁", "1-8周"),
            metadataCourse("大学物理", "05201", "戊己", "9-16周")
        )

        assertEquals("丙 丁", CourseTeacherBinder.bind(listOf(personal), metadata).single().teacher)
    }

    @Test
    fun keepsTheInputInstanceWhenTheWeeksCannotTellTheCandidatesApart() {
        val personal = course("1", "大学物理", "丙 丁", listOf(
            occurrence("o1", 3, 5, 6, "1-16周", "05201")
        ))
        val metadata = listOf(
            metadataCourse("大学物理", "05201", "丙", "1-16周"),
            metadataCourse("大学物理", "05201", "丁", "1-16周")
        )

        // 两位候选都覆盖 1-16 周 == 周次给不出证据；两个又都是单名 == 单名也给不出
        assertSame(personal, CourseTeacherBinder.bind(listOf(personal), metadata).single())
    }

    @Test
    fun weekDisambiguationDoesNotRunWhenTheOccurrenceHasNoWeekText() {
        // academicWeeksForText("") 会展开成全部周次（与「全周」同义），但周次消歧要的是
        // 具体证据。真实页面上「个人课表没写出周次」是有的；此时若拿「全部周次」去求交集，
        // 只要另一个候选恰好周次解析不出来，就会假命中唯一解。
        val personal = course("1", "大学物理", "丙 丁", listOf(
            occurrence("o1", 3, 5, 6, "", "05201")
        ))
        val metadata = listOf(
            metadataCourse("大学物理", "05201", "甲", "1-8周"),
            metadataCourse("大学物理", "05201", "乙", "详见通知")
        )

        assertEquals("丙 丁", CourseTeacherBinder.bind(listOf(personal), metadata).single().teacher)
    }

    // ---- 夹具 ----

    private fun course(
        id: String,
        title: String,
        teacher: String,
        occurrences: List<CourseOccurrence>
    ) = ScheduleCourse(
        id = id,
        title = title,
        room = occurrences.firstOrNull()?.note.orEmpty(),
        teacher = teacher,
        colorHex = "#3B82F6",
        occurrences = occurrences
    )

    /** 大节课表解析出的元数据：一个 (课程名, 教室, 教师) 的块。 */
    private fun metadataCourse(
        title: String,
        room: String,
        teacher: String,
        weekText: String = "1-10周"
    ) = ScheduleCourse(
        id = "meta-$title-$room-$teacher",
        title = title,
        room = room,
        teacher = teacher,
        colorHex = "#3B82F6",
        occurrences = listOf(occurrence("m1", 5, 1, 2, weekText, room))
    )

    private fun occurrence(
        id: String,
        day: Int,
        start: Int,
        end: Int,
        weekText: String,
        room: String
    ) = CourseOccurrence(
        id = id,
        courseId = "c",
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        weekText = weekText,
        note = room
    )
}

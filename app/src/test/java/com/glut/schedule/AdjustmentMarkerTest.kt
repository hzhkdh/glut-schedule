package com.glut.schedule

import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.SemesterAdjustment
import com.glut.schedule.data.model.adjustmentMarkerFor
import com.glut.schedule.data.model.readableMarkerColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 卡片左下角「调」/「补」角标的判定与字色。
 *
 * 判定这块最容易被写错的是**周次**：调整记录的目标时段可能与课程常规时段重合，
 * 那时两个课次会被合并成同一门课，只看 (课程名, 星期, 节次) 会把常规周也标上。
 */
class AdjustmentMarkerTest {

    private fun occurrence(
        dayOfWeek: Int = 2,
        startSection: Int = 3,
        endSection: Int = 4
    ) = CourseOccurrence(
        id = "o1",
        courseId = "c1",
        dayOfWeek = dayOfWeek,
        startSection = startSection,
        endSection = endSection,
        weekText = "1-16周",
        note = "04105"
    )

    private fun adjustment(
        type: String,
        title: String = "数据库系统",
        makeupWeek: Int = 5,
        makeupDay: Int = 2,
        makeupStartSection: Int = 3,
        makeupEndSection: Int = 4
    ) = SemesterAdjustment(
        id = "adj-$type-$makeupWeek",
        type = type,
        title = title,
        teacher = "张老师",
        originalWeek = 5,
        originalDay = 2,
        originalStartSection = 3,
        originalEndSection = 4,
        originalRoom = "04105",
        makeupWeek = makeupWeek,
        makeupDay = makeupDay,
        makeupStartSection = makeupStartSection,
        makeupEndSection = makeupEndSection,
        makeupRoom = "06104D"
    )

    // ---- 判定 ----

    @Test
    fun adjustedCourseIsMarkedWithDiao() {
        val marker = adjustmentMarkerFor(
            occurrence(), "数据库系统", 5, listOf(adjustment("调课"))
        )
        assertEquals("调", marker)
    }

    @Test
    fun makeupCourseIsMarkedWithBu() {
        val marker = adjustmentMarkerFor(
            occurrence(), "数据库系统", 5, listOf(adjustment("补课"))
        )
        assertEquals("补", marker)
    }

    @Test
    fun theSameSlotInAnotherWeekIsNotMarked() {
        // 目标时段与常规时段重合时，两个课次会并成同一门课；只看 (星期, 节次) 会把
        // 第 6 周的常规卡也标成「调」。周次判定是必需的，不是保险。
        val marker = adjustmentMarkerFor(
            occurrence(), "数据库系统", 6, listOf(adjustment("调课", makeupWeek = 5))
        )
        assertNull(marker)
    }

    @Test
    fun substituteTeachingIsNotMarked() {
        // 代课在 Android 侧也会生成一张补课卡，不显式滤掉就会被错标。
        assertNull(adjustmentMarkerFor(occurrence(), "数据库系统", 5, listOf(adjustment("代课"))))
    }

    @Test
    fun suspendedClassIsNotMarked() {
        // 停课的 makeupWeek = 0，天然不命中任何周。
        val suspended = adjustment("停课", makeupWeek = 0)
        assertNull(adjustmentMarkerFor(occurrence(), "数据库系统", 5, listOf(suspended)))
    }

    @Test
    fun otherCoursesAreNotMarked() {
        val marker = adjustmentMarkerFor(
            occurrence(), "高等数学", 5, listOf(adjustment("调课", title = "数据库系统"))
        )
        assertNull(marker)
    }

    @Test
    fun titleMatchingIgnoresWhitespace() {
        val marker = adjustmentMarkerFor(
            occurrence(), " 数据库 系统 ", 5, listOf(adjustment("补课", title = "数据库系统"))
        )
        assertEquals("补", marker)
    }

    @Test
    fun differentWeekdayOrSectionIsNotMarked() {
        assertNull(adjustmentMarkerFor(occurrence(dayOfWeek = 4), "数据库系统", 5, listOf(adjustment("调课"))))
        assertNull(
            adjustmentMarkerFor(
                occurrence(startSection = 5, endSection = 6), "数据库系统", 5, listOf(adjustment("调课"))
            )
        )
    }

    @Test
    fun emptyAdjustmentsProduceNoMarker() {
        assertNull(adjustmentMarkerFor(occurrence(), "数据库系统", 5, emptyList()))
        assertNull(adjustmentMarkerFor(occurrence(), "数据库系统", 0, listOf(adjustment("调课"))))
    }

    // ---- 字色 ----

    @Test
    fun darkCardsKeepWhiteMarkerText() {
        assertEquals("#FFFFFF", readableMarkerColor("#0F0FBD"))
        assertEquals("#FFFFFF", readableMarkerColor("#62206F"))
        assertEquals("#FFFFFF", readableMarkerColor("#1265D9"))
    }

    @Test
    fun lightCardsSwitchToDarkMarkerText() {
        // 纯白底是用户在高级调色里能拖出来的真实情况。
        assertEquals("#141821", readableMarkerColor("#FFFFFF"))
        assertEquals("#141821", readableMarkerColor("#B68B35"))
    }

    @Test
    fun everyLowContrastPresetColorGetsDarkMarkerText() {
        // 这几个是 20 个预设色里白字对比度低于 3.2:1 的，必须全部走深字，
        // 否则「调」「补」会跟卡片正文一样糊在底色里。
        listOf("#24A87C", "#7CA00D", "#0FA0BD", "#B68B35", "#32A00D").forEach { hex ->
            assertEquals("$hex 应当用深字", "#141821", readableMarkerColor(hex))
        }
    }

    @Test
    fun everyPresetColorGetsTheBestMarkerColorAvailable() {
        // 20 个预设色逐个过一遍。注意这里**不能**一律要求 4.5:1：像 #D95412 这种中间亮度的
        // 底色，配白字约 4.02、配深字约 4.42，两个候选都到不了 4.5——那是底色本身的限制，
        // 换任何字色都救不回来。所以要求的是「在两个候选里选中对比度更高的那个」，
        // 也就是该底色下能做到的最好结果。
        CourseColorMapper.presetPalette.forEach { hex ->
            val chosen = readableMarkerColor(hex)
            val best = maxOf(contrastRatio(hex, "#FFFFFF"), contrastRatio(hex, "#141821"))
            assertEquals(
                "$hex 没有选到最好的角标字色（选了 $chosen）",
                best,
                contrastRatio(hex, chosen),
                1e-9
            )
        }
    }

    @Test
    fun unparsableBackgroundFallsBackToWhite() {
        assertEquals("#FFFFFF", readableMarkerColor(""))
        assertEquals("#FFFFFF", readableMarkerColor("not-a-color"))
    }

    // ---- 与 WCAG 公式一致的参考实现，仅测试用 ----

    private fun contrastRatio(first: String, second: String): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val clean = hex.removePrefix("#")
        val channels = listOf(0, 2, 4).map { index ->
            val value = clean.substring(index, index + 2).toInt(16) / 255.0
            if (value <= 0.03928) value / 12.92 else Math.pow((value + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }
}

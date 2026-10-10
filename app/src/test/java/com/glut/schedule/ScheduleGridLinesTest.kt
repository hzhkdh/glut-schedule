package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.capabilitiesFor
import com.glut.schedule.ui.components.gridBoldRowIndices
import com.glut.schedule.ui.components.scheduleRowIndex
import com.glut.schedule.ui.components.scheduleTimeBlockIndex
import com.glut.schedule.ui.components.visiblePeriodRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 课表网格线的定位规则。
 *
 * 线与课程卡片必须共用同一份可见行列表：两边各自过滤一次的话，隐藏中午时线会比卡片
 * 整整错开两行，线画在课程中间。这里锁住那份列表，以及「相邻两行是否跨时段」的判定。
 */
class ScheduleGridLinesTest {

    private fun periods(sections: List<Int>) =
        sections.map { section -> ClassPeriod(section, "08:00", "08:45") }

    private fun guilinPeriods() = periods((1..14).toList())

    /**
     * 加粗线落在**哪一节之后**（用该行的内部节次号）。
     *
     * 用节次号而不是「第几行之后」：后者会随「显示中午」开关变化，读断言的人得先在脑子里换算
     * 一遍可见行 —— 而且完整 14 行时两种读法的数值恰好相同，很容易把断言写错还看不出来。
     * 节次号与可见性无关：隐藏中午后「第4节之后」与「午2之后」并成一条，答案仍是 4 和 10。
     *
     * 这里直接调生产代码 `gridBoldRowIndices`，而不是在测试里再抄一份判据 ——
     * 抄一份的话，代码若把判据写成「跟上一行比」这种整体偏移一行的错误，测试依然会通过。
     */
    private fun boldLinesAfterSection(rows: List<ClassPeriod>, blockEndSections: List<Int>): List<Int> =
        gridBoldRowIndices(rows, blockEndSections).map { rows[it].section }

    @Test
    fun blockEndsMatchEachProfilesRealTimeBlocks() {
        assertEquals(listOf(4, 6, 10), capabilitiesFor(CampusType.GUILIN).blockEndSections)
        // 南宁与研究生都没有中午节次，所以分界里不含第 6 节这一项。
        assertEquals(listOf(4, 8), capabilitiesFor(CampusType.NANNING).blockEndSections)
        assertEquals(listOf(4, 8), capabilitiesFor(CampusType.GRADUATE).blockEndSections)
    }

    @Test
    fun timeBlockIndexSplitsSectionsIntoMorningNoonAfternoonEvening() {
        val guilin = capabilitiesFor(CampusType.GUILIN).blockEndSections
        assertEquals(
            listOf(0, 0, 0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3),
            (1..14).map { scheduleTimeBlockIndex(it, guilin) }
        )

        // 研究生 12 节：上午 1-4、下午 5-8、晚上 9-12，没有中午那一段。
        val graduate = capabilitiesFor(CampusType.GRADUATE).blockEndSections
        assertEquals(
            listOf(0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2),
            (1..12).map { scheduleTimeBlockIndex(it, graduate) }
        )
    }

    /** 没有中午节次的来源（南宁、研究生）12 节全可见，一行都不能被裁掉。 */
    @Test
    fun noonlessSourcesKeepEveryRowVisible() {
        val rows = periods((1..12).toList())
        val visible = visiblePeriodRows(rows, effectiveShowNoon = true)
        assertEquals(12, visible.size)
        assertEquals(4, scheduleRowIndex(5, visible))
    }

    @Test
    fun hidingNoonShiftsEveryFollowingRowUpByTwo() {
        val periods = guilinPeriods()
        val full = visiblePeriodRows(periods, effectiveShowNoon = true)
        val compact = visiblePeriodRows(periods, effectiveShowNoon = false)

        assertEquals(14, full.size)
        assertEquals(listOf(1, 2, 3, 4, 7, 8, 9, 10, 11, 12, 13, 14), compact.map { it.section })
        // 被隐藏的午1/午2 查不到行号，也就不可能被当成可定位的课程行。
        assertNull(scheduleRowIndex(5, compact))
        assertNull(scheduleRowIndex(6, compact))
        // 隐藏前后「桂林第 5-6 节」指的都是内部第 7-8 节，行号随可见列表前移两行。
        assertEquals(6, scheduleRowIndex(7, full))
        assertEquals(4, scheduleRowIndex(7, compact))
    }

    /**
     * 隐藏中午后，内部 4 与 7 相邻，「4 之后」和「6 之后」两条分界缝并成一条。
     *
     * 这正是「按相邻行时段比较、而不是拿节次号去比对 blockEndSections」的原因：
     * 后者会在 4 和 7 之间各画一条，或者把线画到早已被隐藏的行上去。
     */
    @Test
    fun hidingNoonMergesTwoBoundariesIntoOne() {
        val blockEnds = capabilitiesFor(CampusType.GUILIN).blockEndSections
        val periods = guilinPeriods()

        // 显示中午：加粗线落在第 4、6、10 节之后（上午结束 / 午2结束 / 下午结束）。
        assertEquals(
            listOf(4, 6, 10),
            boldLinesAfterSection(visiblePeriodRows(periods, effectiveShowNoon = true), blockEnds)
        )
        // 隐藏中午：午1/午2 两行不在列表里，「第4节之后」与「午2之后」并成同一条，
        // 于是只剩「第4节之后」和「第10节之后」两条。
        assertEquals(
            listOf(4, 10),
            boldLinesAfterSection(visiblePeriodRows(periods, effectiveShowNoon = false), blockEnds)
        )
    }

    /**
     * 加粗线必须落在第 4、8 节之后 —— 也就是**第 4、8 节那一行各自的下缘**。
     *
     * 这条是给一次真实回归写的：横线画在行的下缘，判据却按「跟**上一行**比时段」写，
     * 于是加粗线整体下移一行，研究生 12 节的课表上表现为线跑在第 5 节和第 9 节后面。
     */
    @Test
    fun boldLinesSitDirectlyBelowTheBlockEndingRow() {
        val blockEnds = capabilitiesFor(CampusType.GRADUATE).blockEndSections
        val rows = periods((1..12).toList())

        assertEquals(listOf(4, 8), boldLinesAfterSection(rows, blockEnds))

        // 折算成 y：第 4 节之后 = 4 个行高，第 8 节之后 = 8 个行高（行高 74dp）。
        // 行下标 3 与 7 的下缘，正是因为线画在下缘才必须跟下一行比时段。
        val rowHeight = 74
        assertEquals(listOf(3, 7), gridBoldRowIndices(rows, blockEnds))
        assertEquals(
            listOf(4 * rowHeight, 8 * rowHeight),
            gridBoldRowIndices(rows, blockEnds).map { (it + 1) * rowHeight }
        )
    }

    /** 空 blockEndSections（默认参数）不得画出任何分界线，否则默认值会凭空造线。 */
    @Test
    fun emptyBlockEndsNeverProducesABoundary() {
        assertEquals(emptyList<Int>(), boldLinesAfterSection(guilinPeriods(), emptyList()))
    }
}

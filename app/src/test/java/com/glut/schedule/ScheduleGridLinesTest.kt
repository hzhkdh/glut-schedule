package com.glut.schedule

import com.glut.schedule.data.model.ClassPeriod
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.capabilitiesFor
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

    /** 相邻行之间需要画分界线的行下标（行 i 与行 i-1 之间跨时段）。 */
    private fun boundaryRowIndices(rows: List<ClassPeriod>, blockEndSections: List<Int>): List<Int> =
        rows.indices.filter { index ->
            index > 0 &&
                scheduleTimeBlockIndex(rows[index].section, blockEndSections) !=
                scheduleTimeBlockIndex(rows[index - 1].section, blockEndSections)
        }

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

        assertEquals(
            listOf(4, 6, 10),
            boundaryRowIndices(visiblePeriodRows(periods, effectiveShowNoon = true), blockEnds)
        )
        assertEquals(
            listOf(4, 8),
            boundaryRowIndices(visiblePeriodRows(periods, effectiveShowNoon = false), blockEnds)
        )
    }

    /** 空 blockEndSections（默认参数）不得画出任何分界线，否则默认值会凭空造线。 */
    @Test
    fun emptyBlockEndsNeverProducesABoundary() {
        assertEquals(emptyList<Int>(), boundaryRowIndices(guilinPeriods(), emptyList()))
    }
}

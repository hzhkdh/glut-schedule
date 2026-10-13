package com.glut.schedule

import com.glut.schedule.data.model.ManualDayCopyRule
import com.glut.schedule.ui.pages.AUTO_ROW_GROUP_TITLE
import com.glut.schedule.ui.pages.MANUAL_ROW_GROUP_TITLE
import com.glut.schedule.ui.pages.ManualDayCopyRow
import com.glut.schedule.ui.pages.ManualDayCopyRowOrigin
import com.glut.schedule.ui.pages.groupDayCopyRows
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则列表按来源分组的纯逻辑。
 *
 * 分组的目的是把来源从「每行一个徽标」提升成「组标题声明」，从而撤掉逐行徽标——那个徽标在
 * 长日期文本旁边会被挤成「自 / 动 / · / 教 / 务」一个字一行，并把整行撑高到日期行看着偏上。
 * 渲染层的对应契约见 [HolidayAdjustmentsUiContractTest]。
 */
class ManualDayCopyGroupTest {

    @Test
    fun autoAndManualRowsGoToSeparateGroups() {
        val groups = groupDayCopyRows(
            listOf(
                row("2026-10-06", "2026-09-20", ManualDayCopyRowOrigin.AUTO_REMOTE),
                row("2026-09-07", "2026-09-08", ManualDayCopyRowOrigin.MANUAL)
            )
        )

        assertEquals(2, groups.size)
        // 自动在前、手动在后：与列表原本的顺序一致，用户不会因为分组而找不到原来的条目。
        assertEquals(AUTO_ROW_GROUP_TITLE, groups[0].title)
        assertTrue(groups[0].isAuto)
        assertEquals(MANUAL_ROW_GROUP_TITLE, groups[1].title)
        assertFalse(groups[1].isAuto)
    }

    @Test
    fun derivedRowsShareTheAutoGroup() {
        val groups = groupDayCopyRows(
            listOf(row("2026-10-06", "2026-09-20", ManualDayCopyRowOrigin.AUTO_DERIVED))
        )

        // 远端表和推导都属「自动」一类：页面上共用同一条蓝色身份，不该分成两组。
        assertEquals(1, groups.size)
        assertTrue(groups.single().isAuto)
        assertEquals(1, groups.single().rows.size)
    }

    @Test
    fun groupWithNoRowsIsNotEmitted() {
        val groups = groupDayCopyRows(
            listOf(row("2026-09-07", "2026-09-08", ManualDayCopyRowOrigin.MANUAL))
        )

        // 只有手动规则时不该冒出一个空的「自动补的 0」标题。
        assertEquals(1, groups.size)
        assertFalse(groups.single().isAuto)
    }

    @Test
    fun noRowsYieldsNoGroups() {
        assertTrue(groupDayCopyRows(emptyList()).isEmpty())
    }

    @Test
    fun orderWithinAGroupIsKept() {
        val first = row("2026-10-06", "2026-09-20", ManualDayCopyRowOrigin.AUTO_REMOTE)
        val second = row("2026-10-07", "2026-10-10", ManualDayCopyRowOrigin.AUTO_DERIVED)

        val groups = groupDayCopyRows(listOf(first, second))

        assertEquals(listOf(first, second), groups.single().rows)
    }

    private fun row(
        source: String,
        target: String,
        origin: ManualDayCopyRowOrigin
    ) = ManualDayCopyRow(
        rule = ManualDayCopyRule(LocalDate.parse(source), LocalDate.parse(target)),
        courseCount = 2,
        origin = origin
    )
}

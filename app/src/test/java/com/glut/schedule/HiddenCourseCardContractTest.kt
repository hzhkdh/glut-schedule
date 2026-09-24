package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手动隐藏卡片这条链路上几处**跨文件**的不变量。
 *
 * 这些约定单看任何一个文件都成立，但很容易被后来的人「顺手」改坏，而且改坏之后
 * 症状很隐蔽（删一张卡却让别的卡换色、把课全隐藏后整页变成「还没有课表」），
 * 所以在这里用源码文本断言把它们钉住。
 */
class HiddenCourseCardContractTest {

    @Test
    fun colorsAreAssignedBeforeHiddenCardsAreFilteredOut() {
        val viewModel = page("ScheduleViewModel.kt")
        val assignIndex = viewModel.indexOf("CourseColorMapper.assignColors(")
        val filterIndex = viewModel.indexOf("applyHiddenCourseRules(")

        assertTrue("两处调用都应当存在", assignIndex >= 0 && filterIndex >= 0)
        // assignColors 按输入顺序占位、并做相邻颜色避让：先过滤会释放调色板索引，
        // 让其余可见课程跟着换色 —— 用户删一张卡却看到别的卡变色。
        assertTrue("必须先配色、后过滤", assignIndex < filterIndex)
    }

    @Test
    fun maxWeekAndHiddenCountUseTheCompleteCourseList() {
        val viewModel = page("ScheduleViewModel.kt")

        // 周次推导若用过滤后的列表，历史学期会被截短。
        assertTrue(viewModel.contains("courses = coloredState.allCourses,"))
        // 计数若用过滤后的列表，命中的规则在列表里已经不存在，数出来永远是 0。
        assertTrue(viewModel.contains("hiddenCardCount(coloredState.allCourses, coloredState.hiddenRules)"))
    }

    @Test
    fun emptyStateJudgesWithTheCompleteCourseList() {
        val screen = page("ScheduleScreen.kt")

        // 把某周或某课次的卡片全隐藏后，展示列表会空，但课表本身还在，
        // 不该整页退化成「还没有课表 / 去导入课表」。
        assertTrue(screen.contains("if (uiState.allCourses.isEmpty())"))
        assertFalse(screen.contains("if (uiState.courses.isEmpty())"))
    }

    @Test
    fun refreshAndImportPathsDoNotFilterCourses() {
        // 刷新走整表替换，写进去的必须是教务给的完整数据；一旦掺进过滤，
        // 隐藏过的课会被当成「教务删掉了」，刷新结果的 diff 也会跟着报错。
        assertFalse(repository("ScheduleRepository.kt").contains("applyHiddenCourseRules("))
        assertFalse(service("AcademicSemesterImportService.kt").contains("applyHiddenCourseRules("))
    }

    @Test
    fun refreshAsksBeforeDiscardingTheHiddenRecords() {
        val viewModel = page("ScheduleViewModel.kt")

        // 有隐藏记录时必须先问一句；没有记录时直接走原路径，不给没用过删除功能的人加步骤。
        assertTrue(viewModel.contains("fun requestRefresh()"))
        assertTrue(viewModel.contains("state.hiddenCardCount <= 0"))
        assertTrue(viewModel.contains("settingsStore.clearHiddenCourseRules(semesterId)"))
    }

    @Test
    fun longPressIsWiredButManualCopyCardsAreExcluded() {
        val screen = page("ScheduleScreen.kt")
        val sheet = component("CourseCardManageSheet.kt")

        assertTrue(screen.contains("onCourseLongClick ="))
        // 调休副本是渲染期派生对象，对它做「本周这次」在语义上没法与隐藏源周原课次区分，
        // 所以这类卡片直接不弹管理弹层。
        assertTrue(screen.contains("isManualCopyBlock()"))
        assertTrue(sheet.contains("fun CourseBlock.isManualCopyBlock()"))
        // 三档删除都要在弹层里出现，且范围文案复用同一份生成函数。
        assertTrue(sheet.contains("HiddenCardScope.WEEK"))
        assertTrue(sheet.contains("HiddenCardScope.OCCURRENCE"))
        assertTrue(sheet.contains("HiddenCardScope.COURSE"))
        assertTrue(sheet.contains("hiddenRuleScopeText("))
    }

    @Test
    fun manageSheetReusesTheSharedPaletteInsteadOfCopyingIt() {
        val sheet = component("CourseCardManageSheet.kt")
        val activity = read("MainActivity.kt")

        // 调色能力只有一份实现：设置页与长按弹层都走 CourseColorPalette.kt。
        // 若哪天有人把色板抄回弹层里，这条会红。
        assertTrue(sheet.contains("CourseColorPaletteSection("))
        assertFalse(sheet.contains("presetPalette"))
        assertTrue(activity.contains("CourseColorPaletteSection("))
        assertFalse(activity.contains("CourseColorMapper.presetPalette"))
    }

    private fun page(name: String) = read("ui/pages/$name")

    private fun component(name: String) = read("ui/components/$name")

    private fun repository(name: String) = read("data/repository/$name")

    private fun service(name: String) = read("service/academic/$name")

    /**
     * 兼容从仓库根目录与从 `app` 目录两种工作目录运行——这个仓库里两种都出现过。
     * 找不到时返回空串，让断言以一个明确的失败暴露出来，而不是抛异常。
     */
    private fun read(relative: String): String =
        source("app/src/main/java/com/glut/schedule/$relative")
            .ifBlank { source("src/main/java/com/glut/schedule/$relative") }

    private fun source(relative: String): String {
        val candidates = listOf(File("./$relative"), File("../$relative"))
        return candidates.firstOrNull { it.exists() }?.readText().orEmpty()
    }
}

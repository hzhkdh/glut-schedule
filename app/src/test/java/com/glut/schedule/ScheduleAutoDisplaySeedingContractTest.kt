package com.glut.schedule

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动显示播种的**接线契约**：判据本身由 [ScheduleAutoDisplaySeedTest] 用纯函数护住，
 * 这里护的是「没有任何调用点绕过那道闸门」。
 *
 * 三种坏法都会让「用户关掉周末列，刷新一次又自己打开」复活：
 * 1. 播种函数不查签名就写 `show_weekend`；
 * 2. 留一个旧的无条件入口（`setAutomaticScheduleDisplay`）在别处还能被调到；
 * 3. 切到历史学期时也播种——`show_weekend`/`show_noon` 是全局唯一的键，
 *    而历史学期是只读快照，拿它的课次改写首页显示偏好属于数据越界。
 */
class ScheduleAutoDisplaySeedingContractTest {

    @Test
    fun storeSeedsOnlyWhenTheContentSignatureChanged() {
        val store = source("data/settings/ScheduleSettingsStore.kt")
        val body = store.substringAfter("suspend fun seedAutomaticScheduleDisplay(")
            .substringBefore("val showGridLines: Flow<Boolean>")

        assertTrue("播种必须先过签名判据", body.contains("planAutomaticScheduleSeed("))
        // 没有这一行闸门就形同虚设：签名没变时仍会把 show_weekend 写回去。
        assertTrue("签名一致必须整个跳过写入", body.contains("?: return@edit"))
        // 三个键一次写完：只写签名不写显示值（或反过来）会让下一次判断基于半套状态。
        assertTrue(body.contains("preferences[showWeekendKey] = seed.display.showWeekend"))
        assertTrue(body.contains("preferences[showNoonKey] = seed.display.showNoon"))
        assertTrue(body.contains("preferences[automaticDisplaySignatureKey] = seed.signature"))
        // 判据要在 edit 内部读，避免读—判—写之间被另一次写入插队。
        assertTrue(
            "签名必须在 edit 事务内读取",
            body.contains("storedSignature = preferences[automaticDisplaySignatureKey]")
        )
    }

    @Test
    fun noCallSiteBypassesTheSignatureGate() {
        val store = source("data/settings/ScheduleSettingsStore.kt")
        val viewModel = page("ScheduleViewModel.kt")
        val application = source("ScheduleApplication.kt")

        assertTrue(
            "Repository 回调必须指向带闸门的新入口",
            application.contains("settingsStore::seedAutomaticScheduleDisplay")
        )
        // 旧的无条件入口必须彻底消失：留着它等于留了一条绕过签名的写路径。
        assertFalse(store.contains("fun setAutomaticScheduleDisplay"))
        assertFalse(viewModel.contains("setAutomaticScheduleDisplay"))
    }

    @Test
    fun selectingAHistoricalSemesterDoesNotReseedAutomaticDisplay() {
        val viewModel = page("ScheduleViewModel.kt")
        val selectBody = viewModel.substringAfter("fun selectSemester(")
            .substringBefore("fun returnToCurrentSemester(")

        assertTrue(
            "切学期播种必须收敛到当前学期",
            selectBody.contains("if (semester.isCurrent)")
        )
        val gate = selectBody.indexOf("if (semester.isCurrent)")
        val seed = selectBody.indexOf("seedAutomaticScheduleDisplay(")
        assertTrue("播种调用必须落在 isCurrent 闸门之内", seed > gate)
    }

    private fun source(name: String): String {
        val module = File("src/main/java/com/glut/schedule/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$name")).readText()
    }

    private fun page(name: String): String {
        val module = File("src/main/java/com/glut/schedule/ui/pages/$name")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/ui/pages/$name")).readText()
    }
}

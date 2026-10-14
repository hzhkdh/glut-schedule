package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 续期成功后重放，必须**重新读一遍会话**——cookie 与主机地址都要。
 *
 * 协调器在用户输对验证码后会把新的 cookie 与 `campusBaseUrl` 写回 DataStore，
 * 但**写在协调器里、不是写在调用方的局部变量里**。若菜单在门卫之前就把
 * `campusBaseUrl` 读进局部变量、续期后只重读 cookie，重放仍会把请求发到**旧主机**上：
 * 南宁换到研究生、或研究生换到南宁时，新 cookie 配旧主机，取数必然失败，
 * 而我们刚刚才让用户认认真真输了一遍验证码。
 *
 * 为什么用源码断言：这三个 ViewModel 依赖的 `AcademicSessionStore` 要 `Context`，
 * JVM 单测里造不出来（协调器为此才抽了 `RenewalSessionWriter` 接口）。本仓库对
 * 这类「跑起来也未必看得出」的约束一贯用源码契约钉（见 `SessionRenewalUiContractTest`）。
 */
class RenewalReplayRereadsSessionTest {

    private val gatedMenus = listOf(
        "GradeExamViewModel.kt",
        "StudyPlanViewModel.kt",
        "SemesterOverviewViewModel.kt"
    )

    @Test
    fun replayAfterRenewalRereadsBothCookieAndBaseUrl() {
        gatedMenus.forEach { file ->
            val source = page(file)
            val gate = source.indexOf("ensureFreshSession()")
            assertTrue("$file 里找不到续期门卫", gate >= 0)

            // 门卫之后的这一小段就是「重放」发生的地方。窗口给得宽一些，
            // 免得日后有人在中间插几行日志就让断言误红。
            val replay = source.substring(gate, minOf(source.length, gate + 900))

            assertTrue(
                "$file 续期后没有重读 cookie",
                replay.contains("academicCookie.first()")
            )
            assertTrue(
                "$file 续期后没有重读主机地址——协调器刚写进去的新值会被忽略",
                replay.contains("campusBaseUrl.first()")
            )
        }
    }
}

/** 与 `SessionRenewalUiContractTest` 同样的路径兼容写法：测试可能从模块根或仓库根运行。 */
private fun page(name: String): String =
    File("src/main/java/com/glut/schedule/ui/pages/$name")
        .let { if (it.exists()) it else File("app/src/main/java/com/glut/schedule/ui/pages/$name") }
        .readText()

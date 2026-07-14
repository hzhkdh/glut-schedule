package com.glut.schedule

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceUiContractTest {
    @Test
    fun screenKeepsApprovedA1Contract() {
        val source = source("ui/pages/FinanceScreen.kt") + source("data/model/FinanceModels.kt")

        listOf("概览", "缴费", "记录", "学分").forEach { assertTrue(source.contains(it)) }
        listOf("0xFF244F46", "0xFF397267", "0xFFBD573E", "0xFFF5F1E9").forEach { assertTrue(source.contains(it)) }
        assertTrue(source.contains("https://cwjf.glut.edu.cn/home/login"))
        assertTrue(source.contains("https://cwjf.glut.edu.cn/home/mmcz"))
        assertTrue(source.contains("fun FinanceScreen("))
    }

    private fun source(path: String): String {
        val module = File("src/main/java/com/glut/schedule/$path")
        return (if (module.exists()) module else File("app/src/main/java/com/glut/schedule/$path")).readText()
    }
}

package com.glut.schedule

import com.glut.schedule.service.parser.validateAdjustmentRefresh
import org.junit.Assert.assertThrows
import org.junit.Test

/** 正常课表网格不能成为清空调课的依据。 */
class AdjustmentRefreshValidationTest {
    // 按真实页面结构手工构造匿名样本，不保存账号、课程或页面原文。
    private fun timetableWithoutAdjustments(): String = """
        <html><body><h3>学生课表（大节课表）</h3>
        <table id="timetable">
        <tr><td></td><td>周一</td><td>周二</td><td>周三</td><td>周四</td><td>周五</td><td>周六</td><td>周日</td></tr>
        <tr><td>第1节</td><td></td><td></td><td></td><td></td><td></td><td></td><td></td></tr>
        </table><table id="noArrangement"><tr><th>课程名称</th></tr></table>
        </body></html>
    """.trimIndent()

    @Test fun completeTimetableMayOmitEntireAdjustmentSection() {
        validateAdjustmentRefresh(timetableWithoutAdjustments(), 0)
    }

    @Test fun omittedSectionStillRejectsIncompleteTimetableAndLoginPage() {
        assertThrows(IllegalArgumentException::class.java) {
            validateAdjustmentRefresh(timetableWithoutAdjustments().substringBefore("</body>"), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdjustmentRefresh(timetableWithoutAdjustments().replace("周日", ""), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdjustmentRefresh(timetableWithoutAdjustments().replace("<body>", "<body><input type='password'>"), 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdjustmentRefresh(timetableWithoutAdjustments().replace("</body>", "<table><tr><td>调课</td><td>损坏</td></tr></table></body>"), 0)
        }
    }

    @Test fun missingOrMalformedSectionPreservesExistingRecords() {
        assertThrows(IllegalArgumentException::class.java) {
            validateAdjustmentRefresh("<table id='timetable'></table>", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateAdjustmentRefresh("<table><tr><td>调课</td><td>无法识别的行</td></tr></table>", 0)
        }
    }
    @Test fun explicitEmptyAndParsedRecordsAreAccepted() {
        validateAdjustmentRefresh("<p>暂无调课记录</p>", 0)
        validateAdjustmentRefresh("<table><tr><th>类型</th><th>补课时间</th></tr></table>", 0)
        validateAdjustmentRefresh("有效记录", 2)
    }
}

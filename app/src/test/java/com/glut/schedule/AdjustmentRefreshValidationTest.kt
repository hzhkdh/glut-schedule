package com.glut.schedule

import com.glut.schedule.service.parser.validateAdjustmentRefresh
import org.junit.Assert.assertThrows
import org.junit.Test

/** 正常课表网格不能成为清空调课的依据。 */
class AdjustmentRefreshValidationTest {
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

package com.glut.schedule

import com.glut.schedule.data.model.FinanceField
import com.glut.schedule.data.model.FinanceItem
import com.glut.schedule.data.model.FinanceOverview
import com.glut.schedule.data.model.FinancePayload
import com.glut.schedule.data.model.FinanceSummary
import com.glut.schedule.data.model.FinanceTableSection
import com.glut.schedule.service.finance.FinanceCacheCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceCacheCodecTest {
    @Test
    fun everyPayloadTypeRoundTripsWithoutLosingFields() {
        val payloads = listOf<FinancePayload>(
            FinancePayload.Overview(FinanceOverview(FinanceSummary(outstandingTotal = "1280"), listOf(FinanceItem("1", "住宿费", amount = "1200")))),
            FinancePayload.Items(listOf(FinanceItem("2", "交易", details = listOf(FinanceField("订单号", "O1")), receiptNumbers = listOf("R1"), canPreview = true)), 2, 21, true),
            FinancePayload.Tables(listOf(FinanceTableSection("学分专业标准", listOf("类别", "要求"), listOf(listOf("必修", "42"))))),
            FinancePayload.TicketImage("data:image/jpeg;base64,abc")
        )

        payloads.forEach { payload ->
            assertEquals(payload, FinanceCacheCodec.decode(FinanceCacheCodec.encode(payload)))
        }
    }
}

package com.glut.schedule

import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.data.model.FinancePayload
import com.glut.schedule.service.finance.FinanceParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FinanceParserTest {
    private val parser = FinanceParser()

    @Test
    fun overviewKeepsSummaryAndPendingFields() {
        val value = JSONObject()
            .put("personal", JSONObject("""{"ReceivableMoney":"5600","PayMoney":"4320","SlurMoney":"1280","LimitCost":"3500","UnLimitCost":"2100"}"""))
            .put("pending", JSONObject("""{"items":[{"xm":"住宿费","xq":"2026 学年","qianfei":"1200"}]}"""))

        val payload = parser.parse(FinanceModule.OVERVIEW, value) as FinancePayload.Overview

        assertEquals("1280", payload.value.summary.outstandingTotal)
        assertEquals("3500", payload.value.summary.requiredFee)
        assertEquals("住宿费", payload.value.pendingItems.single().name)
        assertEquals("1200", payload.value.pendingItems.single().outstanding)
    }

    @Test
    fun transactionAliasesAndTicketMetadataAreNormalized() {
        val transactions = JSONObject("""{"items":[{"riqi":"2026-07-01","shuoming":"住宿费","jiner":"1200","dingdanhao":"O1","is_show_wzyz_ticket":"true"}]}""")
        val payload = parser.parse(FinanceModule.TRANSACTIONS, transactions) as FinancePayload.Items

        assertEquals("住宿费", payload.values.single().name)
        assertEquals("O1", payload.values.single().details.first { it.label == "订单号" }.value)
        assertTrue(payload.values.single().hasTicket)
    }

    @Test
    fun electronicTicketKeepsOnlyPreviewIdentifiers() {
        val value = JSONObject("""{"items":[{"ChargeID":"C1","ChargeDate":"2026-07-01","Amount":"88","ReceiptNum":"R1","payUrl":"https://unsafe.example"}]}""")
        val payload = parser.parse(FinanceModule.ELECTRONIC_TICKETS, value) as FinancePayload.Items

        val ticket = payload.values.single()
        assertEquals("C1", ticket.chargeId)
        assertEquals(listOf("R1"), ticket.receiptNumbers)
        assertTrue(ticket.canPreview)
        assertTrue(ticket.details.none { it.value.contains("unsafe") })
    }

    @Test
    fun creditTablesKeepDynamicColumnOrder() {
        val value = JSONObject("""{"Title_1":"学分专业标准","Data_1":[{"类别":"必修","要求":"42"},{"类别":"选修","要求":"12"}]}""")

        val payload = parser.parse(FinanceModule.CREDIT_SETTLEMENT, value) as FinancePayload.Tables

        assertEquals(listOf("类别", "要求"), payload.sections.single().columns)
        assertEquals(listOf("必修", "42"), payload.sections.single().rows.first())
    }

    @Test
    fun courseRecordsKeepCurrentTotalsAndSummary() {
        val value = JSONObject("""{"takeCourseList":[{"kcmc":"高数","xf":"4"}],"totalList":[{"kcmc":"已修合计","xf":"80"}],"totalMoney":"1200","totalCredit":"84"}""")
        val payload = parser.parse(FinanceModule.COURSE_RECORDS, value) as FinancePayload.Items

        assertEquals(listOf("高数", "已修合计", "选课汇总"), payload.values.map { it.name })
        assertEquals("84", payload.values.last().details.first { it.label == "总学分" }.value)
    }
}

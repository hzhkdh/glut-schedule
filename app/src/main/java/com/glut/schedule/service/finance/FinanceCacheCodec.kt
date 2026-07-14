package com.glut.schedule.service.finance

import com.glut.schedule.data.model.FinanceField
import com.glut.schedule.data.model.FinanceItem
import com.glut.schedule.data.model.FinanceOverview
import com.glut.schedule.data.model.FinancePayload
import com.glut.schedule.data.model.FinanceSummary
import com.glut.schedule.data.model.FinanceTableSection
import org.json.JSONArray
import org.json.JSONObject

object FinanceCacheCodec {
    fun encode(payload: FinancePayload): String = when (payload) {
        is FinancePayload.Overview -> JSONObject()
            .put("type", "overview")
            .put("summary", summaryJson(payload.value.summary))
            .put("items", itemsJson(payload.value.pendingItems))
        is FinancePayload.Items -> JSONObject()
            .put("type", "items")
            .put("items", itemsJson(payload.values))
            .put("page", payload.page)
            .put("total", payload.total)
            .put("hasMore", payload.hasMore)
        is FinancePayload.Tables -> JSONObject()
            .put("type", "tables")
            .put("sections", JSONArray(payload.sections.map(::sectionJson)))
        is FinancePayload.TicketImage -> JSONObject()
            .put("type", "ticketImage")
            .put("dataUrl", payload.dataUrl)
    }.toString()

    fun decode(value: String): FinancePayload {
        val root = JSONObject(value)
        return when (root.getString("type")) {
            "overview" -> FinancePayload.Overview(
                FinanceOverview(
                    summary = summary(root.getJSONObject("summary")),
                    pendingItems = items(root.optJSONArray("items") ?: JSONArray())
                )
            )
            "items" -> FinancePayload.Items(
                values = items(root.optJSONArray("items") ?: JSONArray()),
                page = root.optInt("page", 1),
                total = root.optInt("total", 0),
                hasMore = root.optBoolean("hasMore", false)
            )
            "tables" -> FinancePayload.Tables(
                (root.optJSONArray("sections") ?: JSONArray()).objects().map { section ->
                    FinanceTableSection(
                        title = section.optString("title"),
                        columns = section.optJSONArray("columns").strings(),
                        rows = (section.optJSONArray("rows") ?: JSONArray()).arrays().map { it.strings() }
                    )
                }
            )
            "ticketImage" -> FinancePayload.TicketImage(root.optString("dataUrl"))
            else -> error("Unsupported finance cache payload")
        }
    }

    private fun summaryJson(value: FinanceSummary) = JSONObject()
        .put("receivableTotal", value.receivableTotal)
        .put("paidTotal", value.paidTotal)
        .put("outstandingTotal", value.outstandingTotal)
        .put("electiveFee", value.electiveFee)
        .put("requiredFee", value.requiredFee)
        .put("deferredAmount", value.deferredAmount)
        .put("deferredUntil", value.deferredUntil)

    private fun summary(value: JSONObject) = FinanceSummary(
        receivableTotal = value.optString("receivableTotal"),
        paidTotal = value.optString("paidTotal"),
        outstandingTotal = value.optString("outstandingTotal"),
        electiveFee = value.optString("electiveFee"),
        requiredFee = value.optString("requiredFee"),
        deferredAmount = value.optString("deferredAmount"),
        deferredUntil = value.optString("deferredUntil")
    )

    private fun itemsJson(values: List<FinanceItem>) = JSONArray(values.map { item ->
        JSONObject()
            .put("id", item.id).put("name", item.name).put("secondary", item.secondary)
            .put("amount", item.amount).put("status", item.status).put("term", item.term)
            .put("outstanding", item.outstanding).put("chargeId", item.chargeId)
            .put("receiptNumbers", JSONArray(item.receiptNumbers)).put("canPreview", item.canPreview)
            .put("hasTicket", item.hasTicket)
            .put("details", JSONArray(item.details.map { JSONObject().put("label", it.label).put("value", it.value).put("highlight", it.highlight) }))
    })

    private fun items(values: JSONArray): List<FinanceItem> = values.objects().map { item ->
        FinanceItem(
            id = item.optString("id"), name = item.optString("name"), secondary = item.optString("secondary"),
            amount = item.optString("amount"), status = item.optString("status"), term = item.optString("term"),
            outstanding = item.optString("outstanding"), chargeId = item.optString("chargeId"),
            receiptNumbers = item.optJSONArray("receiptNumbers").strings(), canPreview = item.optBoolean("canPreview"),
            hasTicket = item.optBoolean("hasTicket"),
            details = (item.optJSONArray("details") ?: JSONArray()).objects().map {
                FinanceField(it.optString("label"), it.optString("value"), it.optBoolean("highlight"))
            }
        )
    }

    private fun sectionJson(value: FinanceTableSection) = JSONObject()
        .put("title", value.title)
        .put("columns", JSONArray(value.columns))
        .put("rows", JSONArray(value.rows.map(::JSONArray)))

    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    private fun JSONArray.arrays(): List<JSONArray> = (0 until length()).mapNotNull(::optJSONArray)
}

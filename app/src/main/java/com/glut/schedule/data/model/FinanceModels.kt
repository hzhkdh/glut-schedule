package com.glut.schedule.data.model

enum class FinanceGroup(val label: String) {
    OVERVIEW("概览"), PAYMENT("缴费"), RECORDS("记录"), CREDIT("学分")
}

enum class FinanceModule(val key: String, val group: FinanceGroup, val label: String) {
    OVERVIEW("overview", FinanceGroup.OVERVIEW, "概览"),
    PENDING("pending", FinanceGroup.PAYMENT, "待缴项目"),
    OTHER_PAYMENTS("otherPayments", FinanceGroup.PAYMENT, "其他缴费"),
    FEE_PROJECTS("feeProjects", FinanceGroup.PAYMENT, "收费项目"),
    TRANSACTIONS("transactions", FinanceGroup.RECORDS, "交易记录"),
    PAYMENT_DETAILS("paymentDetails", FinanceGroup.RECORDS, "缴费明细"),
    COURSE_RECORDS("courseRecords", FinanceGroup.RECORDS, "选课记录"),
    ELECTRONIC_TICKETS("electronicTickets", FinanceGroup.RECORDS, "电子票据"),
    CREDIT_SETTLEMENT("creditSettlement", FinanceGroup.CREDIT, "学分结算");

    companion object {
        fun fromKey(key: String): FinanceModule? = entries.firstOrNull { it.key == key }
        fun defaultFor(group: FinanceGroup): FinanceModule = when (group) {
            FinanceGroup.OVERVIEW -> OVERVIEW
            FinanceGroup.PAYMENT -> PENDING
            FinanceGroup.RECORDS -> TRANSACTIONS
            FinanceGroup.CREDIT -> CREDIT_SETTLEMENT
        }
    }
}

data class FinanceField(val label: String, val value: String, val highlight: Boolean = false)

data class FinanceSummary(
    val receivableTotal: String = "",
    val paidTotal: String = "",
    val outstandingTotal: String = "",
    val electiveFee: String = "",
    val requiredFee: String = "",
    val deferredAmount: String = "",
    val deferredUntil: String = ""
)

data class FinanceItem(
    val id: String,
    val name: String,
    val secondary: String = "",
    val amount: String = "",
    val status: String = "",
    val term: String = "",
    val outstanding: String = "",
    val details: List<FinanceField> = emptyList(),
    val chargeId: String = "",
    val receiptNumbers: List<String> = emptyList(),
    val canPreview: Boolean = false,
    val hasTicket: Boolean = false
)

data class FinanceOverview(
    val summary: FinanceSummary = FinanceSummary(),
    val pendingItems: List<FinanceItem> = emptyList()
)

data class FinanceTableSection(
    val title: String,
    val columns: List<String>,
    val rows: List<List<String>>
)

sealed interface FinancePayload {
    data class Overview(val value: FinanceOverview) : FinancePayload
    data class Items(
        val values: List<FinanceItem>,
        val page: Int = 1,
        val total: Int = values.size,
        val hasMore: Boolean = false
    ) : FinancePayload
    data class Tables(val sections: List<FinanceTableSection>) : FinancePayload
    data class TicketImage(val dataUrl: String) : FinancePayload
}

data class CachedFinancePayload(val payload: FinancePayload, val savedAt: Long)
data class FinanceCredentials(val username: String = "", val password: String = "")

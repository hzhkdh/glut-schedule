package com.glut.schedule.service.finance

import com.glut.schedule.data.model.FinanceField
import com.glut.schedule.data.model.FinanceItem
import com.glut.schedule.data.model.FinanceModule
import com.glut.schedule.data.model.FinanceOverview
import com.glut.schedule.data.model.FinancePayload
import com.glut.schedule.data.model.FinanceSummary
import com.glut.schedule.data.model.FinanceTableSection
import org.json.JSONArray
import org.json.JSONObject

class FinanceParser {
    fun parse(module: FinanceModule, value: Any?): FinancePayload = when (module) {
        FinanceModule.OVERVIEW -> parseOverview(value.asObject())
        FinanceModule.PENDING -> FinancePayload.Items(parsePending(value))
        FinanceModule.OTHER_PAYMENTS -> FinancePayload.Items(parseOtherPayments(value))
        FinanceModule.FEE_PROJECTS -> FinancePayload.Items(parseFeeProjects(value))
        FinanceModule.TRANSACTIONS -> FinancePayload.Items(parseTransactions(value))
        FinanceModule.PAYMENT_DETAILS -> FinancePayload.Items(parsePaymentDetails(value))
        FinanceModule.COURSE_RECORDS -> FinancePayload.Items(parseCourseRecords(value))
        FinanceModule.ELECTRONIC_TICKETS -> FinancePayload.Items(parseTickets(value))
        FinanceModule.CREDIT_SETTLEMENT -> parseCreditSettlement(value.asObject())
    }

    private fun parseOverview(value: JSONObject): FinancePayload.Overview {
        val personal = value.optJSONObject("personal") ?: value
        val pending = value.opt("pending") ?: value.opt("pendingItems")
        val summary = FinanceSummary(
            receivableTotal = personal.text("ReceivableMoney", "receivableMoney"),
            paidTotal = personal.text("PayMoney", "payMoney"),
            outstandingTotal = personal.text("SlurMoney", "ArrearsMoney", "arrearsMoney"),
            electiveFee = personal.text("UnLimitCost", "ElectiveCost"),
            requiredFee = personal.text("LimitCost", "RequiredCost"),
            deferredAmount = personal.text("DeferralMoney", "deferredMoney"),
            deferredUntil = personal.text("DeferralDate", "deferredDate")
        )
        return FinancePayload.Overview(FinanceOverview(summary, parsePending(pending)))
    }

    private fun parsePending(value: Any?): List<FinanceItem> = value.asList().mapIndexed { index, item ->
        val outstanding = item.text("arrearsMoney", "qianfei", "ArrearsMoney", "xf", "amount", "Amount")
        FinanceItem(
            id = item.id(index, "ChargeID"),
            name = item.text("xm", "itemname", "ItemName", "name"),
            secondary = item.text("xq", "XQ", "term", "TermName"),
            amount = outstanding,
            term = item.text("xq", "XQ", "term", "TermName"),
            outstanding = outstanding,
            details = fields(
                "应收" to item.text("receivableMoney", "yingshou", "ReceivableMoney"),
                "减免" to item.text("remitMoney", "jianmian", "RemitMoney"),
                "已缴" to item.text("payMoney", "yijiao", "PayMoney"),
                "贷款" to item.text("loanMoney", "daikuan", "LoanMoney"),
                "未缴" to outstanding
            )
        )
    }

    private fun parseOtherPayments(value: Any?): List<FinanceItem> = value.asList().mapIndexed { index, item ->
        FinanceItem(
            id = item.id(index, "ChargeID"),
            name = item.text("itemname", "ItemName", "xm", "name"),
            secondary = item.text("description", "Remark", "remark", "beizhu"),
            amount = item.text("amount", "Amount", "xf", "money"),
            status = item.text("status", "State", "zhuangtai")
        )
    }

    private fun parseTransactions(value: Any?): List<FinanceItem> = value.asList().mapIndexed { index, item ->
        FinanceItem(
            id = item.id(index),
            name = item.text("shuoming", "description", "Remark").ifBlank { "交易记录" },
            secondary = item.text("riqi", "ChargeDate", "date"),
            amount = item.text("jiner", "Amount", "amount"),
            status = item.text("zhuangtai", "status", "State"),
            hasTicket = item.text("is_show_wzyz_ticket", "hasTicket").matches(Regex("^(1|true|yes)$", RegexOption.IGNORE_CASE)),
            details = fields(
                "支付方式" to item.text("payment", "ChargeMode", "fangshi"),
                "银行" to item.text("bank_name", "yinhang", "BankName"),
                "订单号" to item.text("dingdanhao", "OrderNo"),
                "收费序号" to item.text("xuhao", "SequenceNo")
            )
        )
    }

    private fun parseFeeProjects(value: Any?): List<FinanceItem> = value.asList().mapIndexed { index, item ->
        val outstanding = item.text("qianfei", "ArrearsMoney")
        FinanceItem(
            id = item.id(index, "ChargeID"),
            name = item.text("xiangmu", "itemname", "ItemName"),
            secondary = item.text("xueqi", "xq", "TermName"),
            amount = outstanding,
            term = item.text("xueqi", "xq", "TermName"),
            outstanding = outstanding,
            details = fields(
                "应收" to item.text("yingshou", "ReceivableMoney"),
                "减免" to item.text("jianmian", "RemitMoney"),
                "已缴" to item.text("yijiao", "PayMoney"),
                "贷款" to item.text("daikuan", "LoanMoney"),
                "未缴" to outstanding
            )
        )
    }

    private fun parsePaymentDetails(value: Any?): List<FinanceItem> = value.asList().mapIndexed { index, item ->
        FinanceItem(
            id = item.id(index),
            name = item.text("zhaiyao", "description", "Remark").ifBlank { "缴费明细" },
            secondary = item.text("riqi", "ChargeDate", "date"),
            amount = item.text("jiner", "Amount", "amount"),
            details = fields(
                "支付方式" to item.text("fangshi", "ChargeMode", "method"),
                "票据号码" to listOf("piaoju1", "piaoju2", "piaoju3").map(item::optString).filter(String::isNotBlank).joinToString("、")
            )
        )
    }

    private fun parseCourseRecords(value: Any?): List<FinanceItem> {
        val root = value.asObject()
        val source = if (root.length() == 0) value else root.opt("takeCourseList") ?: root.opt("currentList") ?: root
        return source.asList().mapIndexed { index, item ->
            FinanceItem(
                id = item.id(index, "CourseID"),
                name = item.text("kechengmingcheng", "kcmc", "CourseName", "name"),
                secondary = listOf(item.text("xuenian", "xn", "AcademicYear"), item.text("xueqi", "xq", "TermName")).filter(String::isNotBlank).joinToString(" · "),
                amount = item.text("jiner", "Amount", "money"),
                details = fields("课程代码" to item.text("kechengbianma", "kcdm", "CourseCode"), "学分" to item.text("xuefen", "xf", "Credit"), "单价" to item.text("danjia", "UnitPrice"))
            )
        }
    }

    private fun parseTickets(value: Any?): List<FinanceItem> = value.asList().mapIndexed { index, item ->
        val receipts = listOf("ReceiptNum", "ReceiptNum1", "ReceiptNum2", "ReceiptNum3", "ReceiptNum4")
            .map(item::optString).filter(String::isNotBlank)
        val chargeId = item.text("ChargeID", "chargeId")
        FinanceItem(
            id = chargeId.ifBlank { item.id(index) },
            name = "电子票据",
            secondary = item.text("ChargeDate", "riqi", "date"),
            amount = item.text("Amount", "jiner"),
            status = item.text("ApplyingStatus", "State", "status"),
            chargeId = chargeId,
            receiptNumbers = receipts,
            canPreview = chargeId.isNotBlank() && receipts.isNotEmpty(),
            details = fields("支付方式" to item.text("ChargeMode", "fangshi"), "票据号码" to receipts.joinToString("、"))
        )
    }

    private fun parseCreditSettlement(value: JSONObject): FinancePayload.Tables {
        val sections = (1..3).mapNotNull { index ->
            val title = value.text("Title_$index")
            val source = value.opt("Data_$index").asList()
            if (title.isBlank() && source.isEmpty()) return@mapNotNull null
            val columns = buildList {
                source.forEach { row -> row.keys().forEach { key -> if (key !in this) add(key) } }
            }.sortedWith(compareBy<String> { columnRank(it) }.thenBy { it })
            FinanceTableSection(
                title = title.ifBlank { "明细 $index" },
                columns = columns,
                rows = source.map { row -> columns.map(row::optString) }
            )
        }
        return FinancePayload.Tables(sections)
    }

    private fun fields(vararg entries: Pair<String, String>): List<FinanceField> = entries
        .filter { it.second.isNotBlank() }
        .map { FinanceField(it.first, it.second, it.first == "未缴") }

    private fun columnRank(name: String): Int = when {
        name.contains("序号") || name.equals("id", true) -> 0
        name.contains("类别") || name.contains("名称") || name.contains("项目") -> 1
        else -> 2
    }

    private fun JSONObject.id(index: Int, vararg extra: String): String =
        text("id", "ID", *extra).ifBlank { (index + 1).toString() }

    private fun JSONObject.text(vararg keys: String): String {
        keys.forEach { key ->
            if (has(key) && !isNull(key)) return opt(key)?.toString()?.trim().orEmpty()
        }
        return ""
    }

    private fun Any?.asObject(): JSONObject = when (this) {
        is JSONObject -> this
        is String -> runCatching { JSONObject(this) }.getOrDefault(JSONObject())
        else -> JSONObject()
    }

    private fun Any?.asList(): List<JSONObject> {
        val array = when (this) {
            is JSONArray -> this
            is JSONObject -> listOf("items", "rows", "list").firstNotNullOfOrNull { key -> optJSONArray(key) }
                ?: return emptyList()
            is String -> runCatching { JSONArray(this) }.getOrNull() ?: return emptyList()
            else -> return emptyList()
        }
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }
}

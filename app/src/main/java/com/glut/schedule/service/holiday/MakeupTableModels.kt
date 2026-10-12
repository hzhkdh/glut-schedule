package com.glut.schedule.service.holiday

import com.glut.schedule.data.model.MakeupEntry
import java.time.LocalDate
import org.json.JSONObject

/**
 * 维护者托管的「调休补课对照表」。
 *
 * 来源是学校放假通知正文（如「9月20日、10月10日上班上课，分别补10月6日、10月7日的课」），
 * 由维护者录入后放在 `update.999314.xyz/holiday-makeups.json`。这是本功能唯一的**权威**来源：
 * 从补班日推导「补哪一天」只能作为兜底（官方通知已证伪过端点规律，见 `AutoDayCopy.kt`）。
 *
 * 按**学期 id** 分组而不是只存绝对日期：桂林/南宁/研究生共享同一份全国放假安排（补班日
 * 日期相同），但补哪一天取决于各校区校历；只按「日期落在学期范围内」筛选会让南宁用户
 * 吃到桂林的映射，而 App 无从拒绝。分组后「本学期没有条目」也是一个可见状态。
 */
data class MakeupTable(
    val revision: Int,
    val updatedAt: String,
    val bySemester: Map<String, List<MakeupEntry>>
) {
    fun entriesFor(semesterId: String): List<MakeupEntry> = bySemester[semesterId].orEmpty()

    /**
     * 这张表**收录了**这个学期吗？
     *
     * 关键区别：`entriesFor(id)` 为空有两种含义——「表里没这个学期」和「维护者显式写了
     * `"makeups": []` 表示本学期没有调休」。前者要由推导兜底，后者必须让推导**整学期退场**，
     * 所以判据是「学期键存不存在」而不是「条目多不多」。
     */
    fun covers(semesterId: String): Boolean = bySemester.containsKey(semesterId)
}

/** 只接受这个协议号：不认识的值一律当不可用，避免把别的 JSON（如 update.json）当成表解析。 */
private const val SUPPORTED_SCHEMA = 1

/** 每学期条目上界。一学期补班日通常 1~4 个，20 是防御性上限。 */
private const val MAX_MAKEUPS_PER_SEMESTER = 20

/**
 * 解析对照表；结构不合法返回 null。
 *
 * 两处刻意的口径：
 * - `revision` 缺失时按 0 处理（它只用于显示与写入短路，不是功能依据），但 `schema` 必须
 *   严格等于 [SUPPORTED_SCHEMA]——那是防止误解析的硬闸门。
 * - **空表是合法的**：`{"schema":1,"semesters":{}}` 与某学期 `"makeups": []` 都会被接受，
 *   表示维护者显式确认「这个学期没有调休」。这与 `isUsableHolidayYearPayload` 要求
 *   「至少有一个具名放假日」的口径**故意不同**——那里的空载荷代表「年份未公布」，
 *   是上游还没给数据；这里的空载荷是维护者的编辑结果，必须传播。
 */
internal fun parseMakeupTableJson(json: String): MakeupTable? {
    if (json.isBlank()) return null
    return runCatching {
        val root = JSONObject(json)
        if (root.optInt("schema", -1) != SUPPORTED_SCHEMA) return null
        val semesters = root.optJSONObject("semesters") ?: return null

        val bySemester = linkedMapOf<String, List<MakeupEntry>>()
        val keys = semesters.keys()
        while (keys.hasNext()) {
            val semesterId = keys.next().trim()
            if (semesterId.isEmpty()) continue
            val semesterEntry = semesters.optJSONObject(semesterId) ?: continue
            val makeups = semesterEntry.optJSONArray("makeups") ?: continue
            bySemester[semesterId] = buildList {
                for (index in 0 until minOf(makeups.length(), MAX_MAKEUPS_PER_SEMESTER)) {
                    val item = makeups.optJSONObject(index) ?: continue
                    // 单条坏数据逐条丢弃，不整体失败：一条录错的日期不该让整学期的自动调课消失。
                    val source = parseTableDate(item.optString("source")) ?: continue
                    val target = parseTableDate(item.optString("target")) ?: continue
                    if (source == target) continue
                    add(MakeupEntry(source, target))
                }
            }
        }

        MakeupTable(
            revision = root.optInt("revision", 0).coerceAtLeast(0),
            updatedAt = root.optString("updatedAt").trim(),
            bySemester = bySemester
        )
    }.getOrNull()
}

/**
 * **写闸门**：只有真的能解析出表结构才落盘。
 *
 * 比 `NoticeChecker` 严格是必要的：远端路径不存在时，静态托管站常常返回 200 + 一个
 * HTML 兜底页。若像通知那样「只要 isSuccessful 就存原文」，那个 HTML 会被解析成
 * 「表被清空了」并永久落盘，把远端规则全部抹掉，而且再也不会自动恢复。
 */
internal fun isUsableMakeupTablePayload(json: String?): Boolean =
    parseMakeupTableJson(json.orEmpty()) != null

private fun parseTableDate(value: String): LocalDate? =
    runCatching { LocalDate.parse(value.trim()) }.getOrNull()

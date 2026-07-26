package com.glut.schedule.service.greeting

import org.json.JSONObject

enum class GreetingCategory(val jsonKey: String) {
    GREETING("greeting"),
    EXAM_TODAY("examToday"),
    EXAM_TOMORROW("examTomorrow"),
    EXAM_UPCOMING("examUpcoming"),
    SEMESTER_WEEK("semesterWeek"),
    SEMESTER_ENDING("semesterEnding")
}

data class GreetingTemplateSet(
    private val values: Map<GreetingCategory, List<String>>
) {
    fun forCategory(category: GreetingCategory): List<String> = values[category].orEmpty()

    fun hasAnyTemplate(): Boolean = GreetingCategory.entries.any { forCategory(it).isNotEmpty() }

    /**
     * 远程模板按分类覆盖；空分类继续使用内置内容，避免局部配置错误拖垮全部问候语。
     */
    fun overlay(remote: GreetingTemplateSet): GreetingTemplateSet = GreetingTemplateSet(
        GreetingCategory.entries.associateWith { category ->
            remote.forCategory(category).ifEmpty { forCategory(category) }
        }
    )
}

data class GreetingTemplateDocument(
    val contentVersion: Int,
    val updatedAt: String,
    val templates: GreetingTemplateSet
)

object GreetingTemplateParser {
    const val MAX_JSON_BYTES = 64 * 1024
    private const val MAX_TEMPLATES_PER_CATEGORY = 50
    private const val MAX_TEMPLATE_LENGTH = 60
    // Android ICU 对未转义的右花括号比桌面 JVM 更严格，两端都使用显式转义写法。
    private val placeholderRegex = Regex("""\{([A-Za-z]+)\}""")
    private val allowedPlaceholders = setOf("name", "period", "course", "days", "week")

    fun parse(json: String): GreetingTemplateDocument? {
        if (json.isBlank() || json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return null
        return runCatching {
            val root = JSONObject(json)
            if (root.optInt("schemaVersion", -1) != 1) return null
            val templatesObject = root.optJSONObject("templates") ?: return null
            val values = GreetingCategory.entries.associateWith { category ->
                val array = templatesObject.optJSONArray(category.jsonKey)
                    ?: return@associateWith emptyList()
                buildList {
                    for (index in 0 until minOf(array.length(), MAX_TEMPLATES_PER_CATEGORY)) {
                        val template = array.optString(index).trim()
                        if (isValidTemplate(template)) add(template)
                    }
                }.distinct()
            }
            GreetingTemplateDocument(
                contentVersion = root.optInt("contentVersion", 0),
                updatedAt = root.optString("updatedAt").trim(),
                templates = GreetingTemplateSet(values)
            )
        }.getOrNull()
    }

    private fun isValidTemplate(template: String): Boolean {
        if (template.isBlank() || template.length > MAX_TEMPLATE_LENGTH) return false
        if (template.any { it == '\n' || it == '\r' || it.isISOControl() }) return false
        return placeholderRegex.findAll(template).all { it.groupValues[1] in allowedPlaceholders }
    }
}

fun builtInGreetingTemplates(): GreetingTemplateSet = GreetingTemplateSet(
    mapOf(
        GreetingCategory.GREETING to listOf(
            "Hi～{name}，{period}好",
            "{name}，{period}好呀",
            "{period}好，今天也要从容一点"
        ),
        GreetingCategory.EXAM_TODAY to listOf(
            "{course}今天登场，稳住",
            "今天考{course}，从容作答",
            "{course}就在今天，祝你顺利"
        ),
        GreetingCategory.EXAM_TOMORROW to listOf(
            "{course}明天登场，准备接招",
            "明天考{course}，稳住",
            "{course}在明天，今晚早点休息"
        ),
        GreetingCategory.EXAM_UPCOMING to listOf(
            "距离{course}考试还有{days}天",
            "{course}还有{days}天，慢慢准备",
            "{days}天后考{course}，心里有数就好"
        ),
        GreetingCategory.SEMESTER_WEEK to listOf(
            "第{week}周啦，继续加油",
            "现在是第{week}周，按自己的节奏来",
            "第{week}周，今天也稳稳向前"
        ),
        GreetingCategory.SEMESTER_ENDING to listOf(
            "学期还剩{days}天，稳稳收尾",
            "学期余额不多啦，从容收官",
            "还有{days}天，给这个学期收好尾"
        )
    )
)

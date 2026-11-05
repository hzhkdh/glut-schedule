package com.glut.schedule.service.academic

import org.jsoup.Jsoup

data class ProfileGroup(val id: String, val title: String, val keys: List<String>)
data class ProfileField(val key: String, val label: String, val group: String, val sensitive: Boolean, val aliases: List<String>)
/** 字段白名单与只读解析；不缓存原始 HTML 或教务照片。 */
object PersonalCenterParser {
    val fields = listOf(
        ProfileField("studentNo","学号","academic",false,listOf("学号","用户名")),
        ProfileField("name","姓名","academic",false,listOf("姓名","真实姓名")),
        ProfileField("department","院系","academic",false,listOf("院系","所在院系","学院")),
        ProfileField("major","专业","academic",false,listOf("专业")),
        ProfileField("grade","年级","academic",false,listOf("年级")),
        ProfileField("className","班级","academic",false,listOf("班级","行政班级")),
        ProfileField("category","学生类别","academic",false,listOf("学生类别")),
        ProfileField("phone","联系电话","contact",false,listOf("联系电话","手机号码")),
        ProfileField("email","电子邮箱","contact",false,listOf("电子邮箱")),
        ProfileField("idType","证件类型","contact",false,listOf("证件类型")),
        ProfileField("idNumber","证件号码","contact",true,listOf("证件号码","身份证号码")),
        ProfileField("address","通讯地址","contact",true,listOf("通讯地址","通信地址")),
        ProfileField("postalCode","邮政编码","contact",false,listOf("邮政编码")),
        ProfileField("formerName","曾用名","more",false,listOf("曾用名")),
        ProfileField("englishName","英文名","basic",false,listOf("英文名")),
        ProfileField("namePinyin","姓名拼音","basic",false,listOf("姓名拼音")),
        ProfileField("gender","性别","basic",false,listOf("性别")),
        ProfileField("birthDate","出生日期","basic",false,listOf("出生日期")),
        ProfileField("birthPlace","出生地","basic",false,listOf("出生地")),
        ProfileField("nationality","国籍","basic",false,listOf("国籍")),
        ProfileField("origin","籍贯","basic",false,listOf("籍贯")),
        ProfileField("ethnicity","民族","basic",false,listOf("民族")),
        ProfileField("politics","政治面貌","academic",false,listOf("政治面貌")),
        ProfileField("religion","信仰宗教","more",false,listOf("信仰宗教")),
        ProfileField("health","健康状况","more",false,listOf("健康状况")),
        ProfileField("marriage","婚姻状况","more",false,listOf("婚姻状况")),
        ProfileField("campus","校区","enrollment",false,listOf("校区")),
        ProfileField("direction","专业方向","enrollment",false,listOf("专业方向","方向")),
        ProfileField("enrollment","入学日期","enrollment",false,listOf("入学日期")),
        ProfileField("status","学生当前状态","enrollment",false,listOf("学生当前状态")),
        ProfileField("registered","是否有学籍","enrollment",false,listOf("是否有学籍")),
        ProfileField("onCampus","是否在校","enrollment",false,listOf("是否在校")),
        ProfileField("education","文化程度","more",false,listOf("文化程度")),
        ProfileField("teachingType","授课类型","enrollment",false,listOf("授课类型")),
        ProfileField("training","培养方式","enrollment",false,listOf("培养方式")),
        ProfileField("studyForm","学习形式","enrollment",false,listOf("学习形式")),
        ProfileField("studyMethod","学习方式","enrollment",false,listOf("学习方式")),
        ProfileField("homeAddress","家庭住址","contact",false,listOf("家庭住址")),
        ProfileField("bank","银行卡号","more",false,listOf("银行卡号")),
        ProfileField("campusCard","一卡通号","more",false,listOf("一卡通号")),
        ProfileField("foreignLanguage","外语语种","more",false,listOf("外语语种")),
        ProfileField("foreignLevel","外语水平","more",false,listOf("外语水平")),
        ProfileField("partyDate","入党时间","more",false,listOf("入党时间")),
        ProfileField("leagueDate","入团时间","more",false,listOf("入团时间")),
        ProfileField("household","户口性质","more",false,listOf("户口性质")),
        ProfileField("familyType","家庭类别","more",false,listOf("家庭类别")),
        ProfileField("studentFile","学籍表号","enrollment",false,listOf("学籍表号")),
        ProfileField("startStation","乘车起始站","more",false,listOf("乘车起始站")),
        ProfileField("endStation","乘车终点站","more",false,listOf("乘车终点站")),
        ProfileField("overseas","港澳台侨外","more",false,listOf("港澳台侨外"))
    )
    // 分组与顺序独立于网页原始排版，首组保留固定八项。
    val groups = listOf(
        ProfileGroup("academic","学籍信息",listOf("name","studentNo","politics","department","major","grade","className","category")),
        ProfileGroup("basic","基本信息",listOf("gender","ethnicity","birthDate","nationality","origin","birthPlace","namePinyin","englishName")),
        ProfileGroup("enrollment","在校情况",listOf("campus","status","onCampus","registered","enrollment","training","teachingType","studyForm","studyMethod","direction","studentFile")),
        ProfileGroup("contact","联系与证件",listOf("phone","email","address","homeAddress","postalCode","idType","idNumber")),
        ProfileGroup("more","其他资料",listOf("formerName","religion","health","marriage","education","bank","campusCard","foreignLanguage","foreignLevel","partyDate","leagueDate","household","familyType","startStation","endStation","overseas"))
    )
    fun groupFields(group: ProfileGroup, values: Map<String,String>) = group.keys.mapNotNull { key -> fields.find { it.key == key } }
        .filter { group.id == "academic" || !values[it.key].isNullOrBlank() }
    private fun normalize(text: String) = text.replace(Regex("""[\s:：*＊]"""), "")
    fun parse(html: String): Map<String, String> {
        val doc = Jsoup.parse(html)
        require(doc.selectFirst("input[type=password]") == null) { "教务会话已失效，请重新登录" }
        doc.select("script,style,input,select,textarea").remove()
        val aliases = fields.flatMap { field -> field.aliases.map { normalize(it) to field.key } }.toMap()
        val values = linkedMapOf<String, String>()
        doc.select("tr").forEach { row ->
            val cells = row.children().filter { it.tagName() in listOf("td", "th") }
            for (i in 0 until (cells.size - 1).coerceAtLeast(0)) {
                val key = aliases[normalize(cells[i].text())] ?: continue
                val next = cells[i + 1].text().trim()
                if (normalize(next) !in aliases) values[key] = next.take(500)
            }
        }
        require(!values["studentNo"].isNullOrBlank() && values.size >= 3) { "个人资料页面无法识别，请重新登录后重试" }
        return values
    }
    fun mask(value: String, key: String = "phone"): String = when {
        value.isBlank() -> "未提供"
        key !in setOf("phone", "studentNo", "idNumber", "bank", "campusCard") -> "••••"
        value.length >= 8 -> value.take(3) + "••••" + value.takeLast(4)
        else -> "••••"
    }
}

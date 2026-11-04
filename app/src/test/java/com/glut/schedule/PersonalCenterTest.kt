package com.glut.schedule

import com.glut.schedule.service.academic.PersonalCenterParser
import org.junit.Assert.*
import org.junit.Test

/** 匿名资料验证只读解析，不把登录页或相邻标签误当成个人信息。 */
class PersonalCenterTest {
    @Test fun academicGroupHasNameAndVisiblePoliticsFirst() {
        val group = PersonalCenterParser.groups.first()
        assertEquals(listOf("name", "studentNo", "politics", "department", "major", "grade", "className", "category"), group.keys)
        assertFalse(PersonalCenterParser.fields.first { it.key == "politics" }.sensitive)
        assertTrue(PersonalCenterParser.groupFields(PersonalCenterParser.groups.first { it.id == "contact" }, mapOf("phone" to "")).isEmpty())
    }
    @Test fun parsesAdjacentCellsAndMasksSensitiveValues() {
        val values = PersonalCenterParser.parse("<table><tr><td>用户名</td><td>anonymous-001</td><td>真实姓名</td><td>匿名</td></tr><tr><td>所在院系</td><td>示例学院</td><td>专业</td><td>示例专业</td></tr></table>")
        assertEquals("anonymous-001", values["studentNo"])
        assertEquals("示例学院", values["department"])
        assertEquals("138••••0000", PersonalCenterParser.mask("13800000000"))
        assertEquals("••••", PersonalCenterParser.mask("1234567"))
        assertEquals("••••", PersonalCenterParser.mask("匿名地址字段示例", "address"))
        assertEquals("••••", PersonalCenterParser.mask("匿名七字地址甲乙".take(7)))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsLoginPage() { PersonalCenterParser.parse("<input type='password'><td>学号</td>") }
}

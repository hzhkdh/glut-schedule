package com.glut.schedule

import com.glut.schedule.service.parser.GradeExamParser
import org.junit.Assert.*
import org.junit.Test

class GradeExamParserTest {

    private val parser = GradeExamParser()

    @Test
    fun `parse empty HTML returns empty list`() {
        val result = parser.parse("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse blank HTML returns empty list`() {
        val result = parser.parse("   \n  ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse HTML with no infolist_tab tables returns empty list`() {
        val html = "<html><body><table><tr><td>hello</td></tr></table></body></html>"
        val result = parser.parse(html)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse HTML with only one table returns empty list`() {
        val html = """
            <html><body><center>
            <table cellpadding="0" cellspacing="0" class="infolist_tab">
            <tr><th>考试名称</th><th>考试时间</th></tr>
            </table>
            </center></body></html>
        """.trimIndent()
        val result = parser.parse(html)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse real skilltest HTML with CET4 and Mandarin entries`() {
        val html = """
<html><body><center>
<table cellspacing="0" cellpadding="0" id="title"><tr><td><div>等级考试通知</div></td></tr></table>
<table cellpadding="0" cellspacing="0" class="infolist_tab"><tr><th>考试名称</th><th>考试时间</th><th>报名条件</th><th>说明</th><th>报名</th></tr></table>
<br><br><br>
<table cellspacing="0" cellpadding="0" id="title"><tr><td><div>我参加的考试</div></td></tr></table>
<table cellpadding="0" cellspacing="0" class="infolist_tab">
<tr><th>考试名称</th><th>考试时间</th><th>准考证号</th><th>证件号码</th><th>成绩</th><th>是否批准考试</th><th>取消</th></tr>
<tr class="infolist_common"><td><a href="#">大学英语四级考试</a></td><td>2025-06-14 09:00~11:20</td><td class="center">451110251105930</td><td>450127200504252179</td><td>424.0</td><td class="center"><div class="statusnatural">已批准</div></td><td class="center">&nbsp;</td></tr>
<tr class="infolist_common"><td><a href="#">普通话水平测试</a></td><td>2024-11-22 11:00~2024-11-29 11:00</td><td class="center">450552024080022</td><td>450127200504252179</td><td>70.4</td><td class="center"><div class="statusnatural">已批准</div></td><td class="center">&nbsp;</td></tr>
<tr><td colspan="10" id="foot"><img src="space.gif"/></td></tr></table>
</center></body></html>
"""
        val result = parser.parse(html)

        assertEquals(2, result.size)

        // First entry: CET-4
        val cet4 = result[0]
        assertEquals("大学英语四级考试", cet4.examName)
        assertEquals("2025-06-14 09:00~11:20", cet4.examTime)
        assertEquals("451110251105930", cet4.ticketNumber)
        assertEquals("424.0", cet4.score)
        assertEquals("已批准", cet4.status)
        assertTrue(cet4.id.isNotBlank())

        // Second entry: Mandarin test
        val mandarin = result[1]
        assertEquals("普通话水平测试", mandarin.examName)
        assertEquals("2024-11-22 11:00~2024-11-29 11:00", mandarin.examTime)
        assertEquals("450552024080022", mandarin.ticketNumber)
        assertEquals("70.4", mandarin.score)
        assertEquals("已批准", mandarin.status)
        assertTrue(mandarin.id.isNotBlank())

        // IDs should differ
        assertNotEquals(cet4.id, mandarin.id)
    }

    @Test
    fun `parse table with no data rows returns empty list`() {
        val html = """
<html><body><center>
<table cellspacing="0" cellpadding="0" id="title"><tr><td><div>等级考试通知</div></td></tr></table>
<table cellpadding="0" cellspacing="0" class="infolist_tab"><tr><th>考试名称</th></tr></table>
<br><br><br>
<table cellspacing="0" cellpadding="0" id="title"><tr><td><div>我参加的考试</div></td></tr></table>
<table cellpadding="0" cellspacing="0" class="infolist_tab">
<tr><th>考试名称</th><th>考试时间</th><th>准考证号</th><th>证件号码</th><th>成绩</th><th>是否批准考试</th></tr>
<tr><td colspan="10" id="foot"><img src="space.gif"/></td></tr></table>
</center></body></html>
"""
        val result = parser.parse(html)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse decodes HTML entities`() {
        val html = """
<html><body><center>
<table cellpadding="0" cellspacing="0" id="title"><tr><td><div>等级考试通知</div></td></tr></table>
<table cellpadding="0" cellspacing="0" class="infolist_tab"><tr><th>考试名称</th></tr></table>
<br><br><br>
<table cellpadding="0" cellspacing="0" id="title"><tr><td><div>我参加的考试</div></td></tr></table>
<table cellpadding="0" cellspacing="0" class="infolist_tab">
<tr><th>考试名称</th><th>考试时间</th><th>准考证号</th><th>证件号码</th><th>成绩</th><th>是否批准考试</th></tr>
<tr class="infolist_common"><td><a href="#">大学英语六级&amp;口语</a></td><td>2025-12&nbsp;13</td><td class="center">12345</td><td>111</td><td>500.0</td><td class="center"><div>已批准</div></td></tr>
</table>
</center></body></html>
"""
        val result = parser.parse(html)
        assertEquals(1, result.size)
        assertEquals("大学英语六级&口语", result[0].examName)
        assertEquals("2025-12 13", result[0].examTime)
    }
}

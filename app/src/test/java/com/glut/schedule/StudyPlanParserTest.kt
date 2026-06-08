package com.glut.schedule

import com.glut.schedule.service.parser.StudyPlanParser
import org.junit.Assert.*
import org.junit.Test

class StudyPlanParserTest {
    private val parser = StudyPlanParser()

    @Test
    fun `parseStudentIds extracts from HTML entities`() {
        val html = "<html><body><center><table class=\"broken_tab\"><tr><td><a href=\"./scheduleJump.jsp?link=studentScheduleLineShow.do&amp;studentId=testId123==&amp;classId=33757\"><img/></a></td></tr></table></center></body></html>"
        val result = parser.parseStudentIds(html)
        assertNotNull(result)
        assertEquals("testId123==", result!!.first)
        assertEquals("33757", result.second)
    }

    @Test
    fun `parseStudentIds handles literal ampersand`() {
        val html = "<html><body><table class=\"broken_tab\"><tr><td><a href=\"./scheduleJump.jsp?link=studentScheduleLineShow.do&studentId=abc123==&classId=99999\"><img/></a></td></tr></table></body></html>"
        val result = parser.parseStudentIds(html)
        assertNotNull(result)
        assertEquals("abc123==", result!!.first)
        assertEquals("99999", result.second)
    }

    @Test
    fun `parseStudentIds returns null for empty HTML`() {
        assertNull(parser.parseStudentIds(""))
    }

    @Test
    fun `parseStudentIds returns null when no match`() {
        assertNull(parser.parseStudentIds("<html><body><p>nothing</p></body></html>"))
    }

    @Test
    fun `parseGroups skips student info table`() {
        val html = """<html><body><center>
<table class="datalist"><tr><th>院系</th><th>专业</th><th>年级</th></tr><tr><td>计算机学院</td><td>人工智能</td><td>2024</td></tr></table>
</center></body></html>"""
        assertTrue(parser.parseGroups(html).isEmpty())
    }

    @Test
    fun `parseGroups from free elective table`() {
        val html = """<html><body><center>
<table cellpadding="0" cellspacing="0" class="datalist">
<tr><th>任选课组名称</th><th>说明</th><th>课程要求</th><th>学分要求</th><th>门数要求</th><th>课组审查要求</th></tr>
<tr><td><a href="#">大学语文</a></td><td>desc</td><td>任选</td><td align="center">2 &gt;= 2</td><td align="center">1 &gt;= 0</td><td align="center"><img src="course_pass.png"/></td></tr>
<tr><td><a href="#">第二课堂</a></td><td>desc</td><td>任选</td><td align="center">0 &lt; 6</td><td align="center">0 &gt;= 0</td><td align="center"><img src="course_failed.png"/></td></tr>
</table></center></body></html>"""
        val groups = parser.parseGroups(html)
        assertEquals(2, groups.size)

        val g1 = groups[0]
        assertEquals("大学语文", g1.groupName)
        assertEquals("任选", g1.attribute)
        assertEquals(2.0, g1.creditEarned, 0.01)
        assertEquals(2.0, g1.creditRequired, 0.01)
        assertEquals(1, g1.countPassed)
        assertEquals(0, g1.countRequired)
        assertTrue(g1.isPassed)

        val g2 = groups[1]
        assertEquals("第二课堂", g2.groupName)
        assertEquals("任选", g2.attribute)
        assertEquals(0.0, g2.creditEarned, 0.01)
        assertEquals(6.0, g2.creditRequired, 0.01)
        assertEquals(0, g2.countPassed)
        assertEquals(0, g2.countRequired)
        assertFalse(g2.isPassed)
    }

    @Test
    fun `parseGroups from mandatory group table`() {
        val html = """<html><body><center>
<table class="datalist">
<tr><th>课组层级</th><th>课组名称</th><th>课程要求</th><th>学分</th><th>门数</th></tr>
<tr><td></td><td>实践教学环节</td><td>必修</td><td>6 &lt; 34</td><td>3 &lt; 8</td><td><img src="course_failed.png"/></td></tr>
</table></center></body></html>"""
        val groups = parser.parseGroups(html)
        assertEquals(1, groups.size)

        val g = groups[0]
        assertEquals("实践教学环节", g.groupName)
        assertEquals("必修", g.attribute)
        assertEquals(6.0, g.creditEarned, 0.01)
        assertEquals(34.0, g.creditRequired, 0.01)
        assertEquals(3, g.countPassed)
        assertEquals(8, g.countRequired)
        assertFalse(g.isPassed)
    }

    @Test
    fun `parseGroups returns empty for blank HTML`() {
        assertTrue(parser.parseGroups("").isEmpty())
    }
}

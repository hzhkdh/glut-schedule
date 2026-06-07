package com.glut.schedule

import com.glut.schedule.service.parser.ScoreParser
import org.junit.Assert.*
import org.junit.Test

class ScoreParserTest {
    private val parser = ScoreParser()

    // Helper: generate N empty td cells for column padding
    private fun pad(n: Int) = (1..n).joinToString("") { "<td></td>" }

    @Test
    fun returnsEmptyForBlankInput() {
        assertTrue(parser.parseScoreHtml("").isEmpty())
        assertTrue(parser.parseScoreHtml("   ").isEmpty())
    }

    @Test
    fun returnsEmptyForNonScoreHtml() {
        val html = "<html><body><p>Hello World</p></body></html>"
        assertTrue(parser.parseScoreHtml(html).isEmpty())
    }

    // ---- Guilin Campus Tests (column indices: course=4, score=7) ----

    @Test
    fun parsesGuilinScoreTableWithExplicitYearTerm() {
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th><th>学分</th></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>高等数学（二）</td><td></td><td></td><td>91</td><td>4.0</td></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>大学英语（四）</td><td></td><td></td><td>85</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025-2026", term = 1)
        assertEquals(2, scores.size)
        assertEquals("高等数学（二）", scores[0].courseName)
        assertEquals("91", scores[0].score)
        assertEquals(4.0, scores[0].gpa, 0.01)
        assertEquals(4.0, scores[0].credit, 0.01)
        assertEquals("2025-2026", scores[0].year)
        assertEquals(1, scores[0].term)
        assertEquals("大学英语（四）", scores[1].courseName)
    }

    @Test
    fun parsesGuilinWithDashYearAutoExtraction() {
        // Auto-extract year from cells when year=null — dash format "2025-2026"
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th><th>学分</th></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>高等数学</td><td></td><td></td><td>91</td><td>4.0</td></tr>
                <tr><td>2024-2025</td><td>秋</td><td></td><td></td><td>大学英语</td><td></td><td></td><td>85</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html)
        assertEquals(2, scores.size)
        assertEquals("2025", scores[0].year)
        assertEquals(1, scores[0].term)
        assertEquals("2024", scores[1].year)
        assertEquals(2, scores[1].term)
    }

    @Test
    fun parsesGuilinWithSimpleNumericYearAutoExtraction() {
        // The real GLUT academic system uses simple 4-digit years like "2019"
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th><th>学分</th></tr>
                <tr><td>2025</td><td>春</td><td></td><td></td><td>高等数学</td><td></td><td></td><td>91</td><td>4.0</td></tr>
                <tr><td>2024</td><td>秋</td><td></td><td></td><td>大学英语</td><td></td><td></td><td>85</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html)
        assertEquals(2, scores.size)
        assertEquals("2025", scores[0].year)
        assertEquals("2024", scores[1].year)
    }

    @Test
    fun parsesGuilinWithEncodedYearAutoExtraction() {
        // Encoded year format (year - 1980): "45" = 2025, "44" = 2024
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th><th>学分</th></tr>
                <tr><td>45</td><td>春</td><td></td><td></td><td>高等数学</td><td></td><td></td><td>91</td><td>4.0</td></tr>
                <tr><td>44</td><td>秋</td><td></td><td></td><td>大学英语</td><td></td><td></td><td>85</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html)
        assertEquals(2, scores.size)
        assertEquals("2025", scores[0].year)  // 45 + 1980 = 2025
        assertEquals("2024", scores[1].year)  // 44 + 1980 = 2024
    }

    @Test
    fun parsesChineseGradeLevels() {
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th><th>学分</th></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>体育</td><td></td><td></td><td>优秀</td><td>1.0</td></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>实验课</td><td></td><td></td><td>及格</td><td>2.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025-2026", term = 1)
        assertEquals(2, scores.size)
        assertEquals(4.0, scores[0].gpa, 0.01)
        assertEquals(1.0, scores[1].gpa, 0.01)
    }

    // ---- Nanning Campus Tests (column indices from GlutAssistantN: course=3, score=5, gpa=6, credit=7) ----

    @Test
    fun parsesNanningCampusFormat() {
        // GlutAssistantN column layout:
        // [0]=year [1]=term [2]=courseCode [3]=courseName [4]=teacher [5]=score [6]=gpa [7]=credit [11]=category
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th>课程代码</th><th>课程名称</th><th>教师</th><th>成绩</th><th>绩点</th><th>学分</th></tr>
                <tr><td>2025</td><td>春</td><td>CS101</td><td>高等数学</td><td>王老师</td><td>91</td><td>4.0</td><td>4.0</td></tr>
                <tr><td>2025</td><td>春</td><td>EN201</td><td>大学英语</td><td>李老师</td><td>85</td><td>3.7</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025", term = 1, isNanning = true)
        assertEquals(2, scores.size)
        assertEquals("高等数学", scores[0].courseName)
        assertEquals("91", scores[0].score)
        assertEquals(4.0, scores[0].gpa, 0.01)
        assertEquals(4.0, scores[0].credit, 0.01)
        assertEquals("大学英语", scores[1].courseName)
        assertEquals("85", scores[1].score)
        assertEquals(3.7, scores[1].gpa, 0.01)
        assertEquals(3.0, scores[1].credit, 0.01)
    }

    @Test
    fun parsesNanningCampusWithExtraColumns() {
        // Nanning table has many columns (up to index 11+). Test with full column set.
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th>代码</th><th>课程名</th><th>教师</th><th>成绩</th><th>绩点</th><th>学分</th><th></th><th></th><th></th><th>类别</th><th></th></tr>
                <tr><td>45</td><td>1</td><td>CS101</td><td>程序设计</td><td>张老师</td><td>95</td><td>4.0</td><td>3.0</td><td></td><td></td><td></td><td>必修</td><td></td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025", term = 1, isNanning = true)
        assertEquals(1, scores.size)
        assertEquals("程序设计", scores[0].courseName)
        assertEquals("95", scores[0].score)
        assertEquals(4.0, scores[0].gpa, 0.01)
        assertEquals(3.0, scores[0].credit, 0.01)
        assertEquals("必修", scores[0].category)
    }

    @Test
    fun parsesNanningAutoYearTermExtraction() {
        // Auto-extract encoded year and term from Nanning cells
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th>代码</th><th>课程名</th><th>教师</th><th>成绩</th><th>绩点</th><th>学分</th></tr>
                <tr><td>45</td><td>春</td><td>CS101</td><td>数据结构</td><td>赵老师</td><td>78</td><td>2.7</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, isNanning = true)
        assertEquals(1, scores.size)
        assertEquals("2025", scores[0].year)  // 45 + 1980
        assertEquals(1, scores[0].term)
        assertEquals("数据结构", scores[0].courseName)
    }

    // ---- Edge Cases ----

    @Test
    fun handlesTableWithoutHeaderRow() {
        val html = """
            <table class="datalist" width="100%">
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>高等数学</td><td></td><td></td><td>91</td><td>4.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025-2026", term = 1)
        assertEquals(1, scores.size)
        assertEquals("高等数学", scores[0].courseName)
        assertEquals("91", scores[0].score)
    }

    @Test
    fun handlesMixedWhitespaceAndNbspInHtml() {
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th></tr>
                <tr><td>  2025-2026  </td><td>&nbsp;春&nbsp;</td><td></td><td></td><td>  高等数学（二）  </td><td></td><td></td><td>  &nbsp;91&nbsp;  </td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025-2026", term = 1)
        assertEquals(1, scores.size)
        assertEquals("高等数学（二）", scores[0].courseName)
        assertEquals("91", scores[0].score)
    }

    @Test
    fun generatesStableIds() {
        val id1 = com.glut.schedule.data.model.ScoreInfo.stableId("高数", "2025-2026", 1)
        val id2 = com.glut.schedule.data.model.ScoreInfo.stableId("高数", "2025-2026", 1)
        val id3 = com.glut.schedule.data.model.ScoreInfo.stableId("高数", "2024-2025", 1)
        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
    }

    @Test
    fun scoreToGpaProducesCorrectMapping() {
        val html = """
            <table class="datalist" width="100%">
                <tr><th></th><th></th><th></th><th></th><th>course</th><th></th><th></th><th>score</th></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>A</td><td></td><td></td><td>95</td></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>B</td><td></td><td></td><td>82</td></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>C</td><td></td><td></td><td>73</td></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>D</td><td></td><td></td><td>61</td></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>E</td><td></td><td></td><td>不及格</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025-2026", term = 1)
        assertEquals(5, scores.size)
        assertEquals(4.0, scores[0].gpa, 0.01) // 95 → ≥90 → 4.0
        assertEquals(3.3, scores[1].gpa, 0.01) // 82 → ≥80 → 3.3
        assertEquals(2.7, scores[2].gpa, 0.01) // 73 → ≥70 → 2.7
        assertEquals(2.0, scores[3].gpa, 0.01) // 61 → ≥60 → 2.0
        assertEquals(0.0, scores[4].gpa, 0.01) // 不及格 → 0.0
    }

    @Test
    fun encodedYearNotAppliedToFourDigitYears() {
        // "2025" is a 4-digit year, not an encoded value. Should stay as "2025".
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程</th><th></th><th></th><th>成绩</th></tr>
                <tr><td>2025</td><td>1</td><td></td><td></td><td>课程A</td><td></td><td></td><td>85</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html)
        assertEquals(1, scores.size)
        assertEquals("2025", scores[0].year)
    }

    @Test
    fun handlesNanningTerm3AsAutumn() {
        // Nanning encodes autumn term as "3" instead of "2"
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th>代码</th><th>课程名</th><th>教师</th><th>成绩</th><th>绩点</th><th>学分</th></tr>
                <tr><td>45</td><td>3</td><td>CS101</td><td>数据结构</td><td>王老师</td><td>85</td><td>3.7</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025", isNanning = true)
        assertEquals(1, scores.size)
        assertEquals(2, scores[0].term)  // "3" → 秋 → term 2
    }
}

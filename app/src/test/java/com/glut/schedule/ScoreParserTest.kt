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

    @Test
    fun parsesGuilinScoreTableWithExplicitYearTerm() {
        // Column layout matching real GLUT academic system:
        // [0]=year [1]=term [2][3]=padding [4]=course [5][6]=padding [7]=score [8]=credit
        val html = """
            <table class="datalist" width="100%">
                ${pad(2)}<td>高等数学（二）</td>${pad(2)}<td>91</td><td>4.0</td>${pad(3)}
                ${pad(2)}<td>大学英语（四）</td>${pad(2)}<td>85</td><td>3.0</td>${pad(3)}
                ${pad(2)}<td>线性代数</td>${pad(2)}<td>58</td><td>3.0</td>${pad(3)}
            </table>
        """.trimIndent()
            .replace("\n            <tr>", "\n            <tr><td>2025-2026</td><td>春</td>")
            .let { it.replace("\n            <tr>", "\n            <tr><td>2025-2026</td><td>秋</td>") }
            .let { it.replace("\n            <tr>", "\n            <tr><td>2024-2025</td><td>春</td>") }

        // Actually, let me write this more clearly:
        // With explicit year/term, the HTML should only contain rows for that semester
        val clearHtml = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th><th>学分</th><th></th><th></th></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>高等数学（二）</td><td></td><td></td><td>91</td><td>4.0</td><td></td><td></td></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>大学英语（四）</td><td></td><td></td><td>85</td><td>3.0</td><td></td><td></td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(clearHtml, year = "2025-2026", term = 1)
        assertEquals(2, scores.size)
        assertEquals("高等数学（二）", scores[0].courseName)
        assertEquals("91", scores[0].score)
        assertEquals(4.0, scores[0].gpa, 0.01) // 91 ≥ 90 → 4.0
        assertEquals(4.0, scores[0].credit, 0.01)
        assertEquals("2025-2026", scores[0].year)
        assertEquals(1, scores[0].term)
        assertEquals("大学英语（四）", scores[1].courseName)
        assertEquals("85", scores[1].score)
    }

    @Test
    fun parsesAllScoresWithAutoYearTermExtraction() {
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th></th><th></th><th>课程名称</th><th></th><th></th><th>成绩</th><th>学分</th></tr>
                <tr><td>2025-2026</td><td>春</td><td></td><td></td><td>高等数学</td><td></td><td></td><td>91</td><td>4.0</td></tr>
                <tr><td>2024-2025</td><td>秋</td><td></td><td></td><td>大学英语</td><td></td><td></td><td>85</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html)
        assertEquals(2, scores.size)
        assertEquals("2025-2026", scores[0].year)
        assertEquals(1, scores[0].term)
        assertEquals("2024-2025", scores[1].year)
        assertEquals(2, scores[1].term)
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

    @Test
    fun parsesNanningCampusFormat() {
        val html = """
            <table class="datalist" width="100%">
                <tr><th>学年</th><th>学期</th><th>课程名称</th><th>课程性质</th><th>成绩</th><th>学分</th></tr>
                <tr><td>2025-2026</td><td>春</td><td>高等数学</td>
                    <td>必修</td><td>91</td><td>4.0</td></tr>
                <tr><td>2025-2026</td><td>春</td><td>大学英语</td>
                    <td>必修</td><td>85</td><td>3.0</td></tr>
            </table>
        """.trimIndent()

        val scores = parser.parseScoreHtml(html, year = "2025-2026", term = 1, isNanning = true)
        assertEquals(2, scores.size)
        assertEquals("高等数学", scores[0].courseName)
        assertEquals("91", scores[0].score)
        assertEquals(4.0, scores[0].credit, 0.01)
        assertEquals("2025-2026", scores[0].year)
        assertEquals(1, scores[0].term)
    }

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
        // Test via parseScoreHtml with various score formats
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
}

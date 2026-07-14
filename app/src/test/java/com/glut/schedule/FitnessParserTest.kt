package com.glut.schedule

import com.glut.schedule.data.model.FitnessHistoryRequest
import com.glut.schedule.service.parser.FitnessParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessParserTest {
    private val parser = FitnessParser()

    @Test
    fun historyRecordsKeepTheirOfficialDetailRequestFields() {
        val html = """
            <form method="post" action="/SportWeb/health_info/listdetalhistroyScore.jsp">
              <input value="student-a" type="hidden" name="studentNo">
              <input type="hidden" name="academicYear" value="2024-2025">
              <input name="term" value="1" type="hidden">
              <input type="hidden" value="2024" name="gradeNo">
              <input type="hidden" name="sex" value="1">
              <table>
                <tr><td>学年</td><td>学期</td><td>年级</td><td>体测成绩</td><td>体测等级</td></tr>
                <tr><td>2024-2025</td><td>1</td><td>2024</td><td>88</td><td>良好</td></tr>
              </table>
            </form>
        """.trimIndent()

        val record = parser.parseHistory(html).single()

        assertEquals(
            FitnessHistoryRequest("student-a", "2024-2025", "1", "2024", "1"),
            record.detailRequest
        )
    }

    @Test
    fun historyRecordsReadDetailFieldsFromFormsInsideEachTableRow() {
        val html = """
            <table>
              <tr>
                <td>序号</td><td>学号</td><td>姓名</td><td>性别</td><td>学年</td>
                <td>学期</td><td>年级</td><td>体测成绩</td><td>体测等级</td><td>详细</td>
              </tr>
              <tr>
                <td>1</td><td>student-a</td><td>测试用户</td><td>男</td><td>2024-2025</td>
                <td>1</td><td>1</td><td>88</td><td>良好</td>
                <td><form method="post" action="/SportWeb/health_info/listdetalhistroyScore.jsp">
                  <input name="studentNo" value="student-a"><input name="academicYear" value="2024-2025">
                  <input name="term" value="1"><input name="gradeNo" value="1"><input name="sex" value="1">
                </form></td>
              </tr>
              <tr>
                <td>2</td><td>student-a</td><td>测试用户</td><td>男</td><td>2023-2024</td>
                <td>2</td><td>1</td><td>80</td><td>及格</td>
                <td><form method="post" action="/SportWeb/health_info/listdetalhistroyScore.jsp">
                  <input name="studentNo" value="student-a"><input name="academicYear" value="2023-2024">
                  <input name="term" value="2"><input name="gradeNo" value="1"><input name="sex" value="1">
                </form></td>
              </tr>
            </table>
        """.trimIndent()

        val records = parser.parseHistory(html)

        assertEquals(listOf("2024-2025", "2023-2024"), records.map { it.year })
        assertEquals(
            FitnessHistoryRequest("student-a", "2024-2025", "1", "1", "1"),
            records[0].detailRequest
        )
        assertEquals(
            FitnessHistoryRequest("student-a", "2023-2024", "2", "1", "1"),
            records[1].detailRequest
        )
    }

    @Test
    fun historyParserFindsTheScoreTableInsideLayoutTables() {
        val html = """
            <table class="layout">
              <tr><td>当前位置：学生历年成绩查询</td></tr>
              <tr><td><table class="content">
                <tr><td>序号</td><td>学年</td><td>学期</td><td>年级</td><td>体测成绩</td><td>体测等级</td><td>详细</td></tr>
                <tr><td>1</td><td>2024-2025</td><td>1</td><td>1</td><td>88</td><td>良好</td><td>
                  <form action="/SportWeb/health_info/listdetalhistroyScore.jsp">
                    <input name="studentNo" value="student-a"><input name="academicYear" value="2024-2025">
                    <input name="term" value="1"><input name="gradeNo" value="1"><input name="sex" value="1">
                  </form>
                </td></tr>
              </table></td></tr>
            </table>
        """.trimIndent()

        val record = parser.parseHistory(html).single()

        assertEquals("2024-2025", record.year)
        assertEquals("88", record.totalScore)
        assertEquals("student-a", record.detailRequest?.studentNo)
    }

    @Test
    fun currentResultKeepsWeightAndAllBonusRows() {
        val html = """
            <td>项目名称</td><td>测试成绩</td><td>分数</td><td>结论</td>
            <td>身高(cm)</td><td>161.1</td><td>---</td><td>---</td>
            <td>体重(kg)</td><td>53.3</td><td>100</td><td>正常</td>
            <td>肺活量</td><td>3422</td><td>64</td><td>及格</td>
            <td>耐力加分</td><td>0</td><td>0</td><td>---</td>
            <td>柔韧力量加分</td><td>0</td><td>0</td><td>---</td>
        """.trimIndent()

        val result = parser.parseHistoryDetail(html)

        assertEquals(
            listOf("身高(cm)", "体重(kg)", "肺活量", "耐力加分", "柔韧力量加分"),
            result.items.map { it.name }
        )
        assertEquals("100", result.items[1].score)
    }

    @Test
    fun currentResultRecognizesBothSitAndReachNames() {
        val html = """
            <td>项目名称</td><td>测试成绩</td><td>分数</td><td>结论</td>
            <td>坐位体前屈</td><td>21.5</td><td>90</td><td>优秀</td>
            <td>坐体前屈</td><td>20.0</td><td>85</td><td>良好</td>
        """.trimIndent()

        val result = parser.parseCurrent(html)

        assertEquals(2, result.items.size)
        assertEquals(listOf("90", "85"), result.items.map { it.score })
    }

    @Test
    fun standardSeparatesRowspanLevelsFromScoresAndParsesFourTables() {
        val html = """
            <table><tr><td>表一：学生体质测试评分标准（大学男生）</td></tr>
            <tr><td>分值\项目</td><td>肺活量(12年级)</td><td>50米跑(12年级)</td></tr>
            <tr><td rowspan="2">优 秀</td><td>100</td><td>5040</td><td>6.7</td></tr>
            <tr><td>95</td><td>4920</td><td>6.8</td></tr>
            <tr><td>各项指标的权重比例：肺活量：15% 50米跑：20%</td></tr></table>
            <table><tr><td>表二：学生体质测试评分标准（大学女生）</td></tr>
            <tr><td>分值\项目</td><td>肺活量(12年级)</td><td>50米跑(12年级)</td></tr>
            <tr><td>良好</td><td>85</td><td>3150</td><td>8.0</td></tr></table>
            <table><tr><td>表三：体重指数(BMI)单项评分表</td></tr>
            <tr><td>对象</td><td>低体重</td><td>正常</td><td>超重</td><td>肥胖</td></tr>
            <tr><td>80分</td><td>100分</td><td>80分</td><td>60分</td></tr>
            <tr><td>大学男生</td><td>&lt;=17.8</td><td>17.9-23.9</td><td>24-27.9</td><td>&gt;=28</td></tr>
            <tr><td>大学女生</td><td>&lt;=17.1</td><td>17.2-23.9</td><td>24-27.9</td><td>&gt;=28</td></tr></table>
            <table><tr><td>表四：学生体质测试加分项目评分表</td></tr>
            <tr><td>分值\项目</td><td>引体向上</td><td>仰卧起坐</td><td>1000米跑</td><td>800米跑</td></tr>
            <tr><td>10</td><td>13</td><td>-50”</td><td>-35”</td><td>-50”</td></tr></table>
        """.trimIndent()

        val standard = parser.parseStandard(html)

        assertEquals(listOf("male", "female", "bmi", "bonus"), standard.map { it.key })
        assertEquals("优秀", standard[0].rows[0].level)
        assertEquals("100", standard[0].rows[0].score)
        assertEquals("", standard[0].rows[1].level)
        assertTrue(standard[0].weightNote.contains("肺活量：15%"))
        assertEquals("大学男生", standard[2].rows[0].label)
        assertEquals("<=17.8", standard[2].rows[0].values[0])
        assertEquals("10", standard[3].rows[0].score)
    }

    @Test
    fun compositeStandardKeepsAllTwelveColumnsFromOfficialTableStructure() {
        val html = """
            <table>
              <tr><td colspan="14">表一：学生体质测试评分标准（大学男生）</td></tr>
              <tr>
                <td colspan="2">分值\项目</td>
                <td>肺活量（12年级）</td><td>肺活量（34年级）</td>
                <td>50米跑（12年级）</td><td>50米跑（34年级）</td>
                <td>千米跑（12年级）</td><td>千米跑（34年级）</td>
                <td>立定跳远（12年级）</td><td>立定跳远（34年级）</td>
                <td>坐位体前屈（12年级）</td><td>坐位体前屈（34年级）</td>
                <td>引体向上（12年级）</td><td>引体向上（34年级）</td>
              </tr>
              <tr>
                <td rowspan="2">优 秀</td><td>100</td>
                <td>5040</td><td>5140</td><td>6.7</td><td>6.6</td>
                <td>3.17</td><td>3.15</td><td>273</td><td>275</td>
                <td>24.9</td><td>25.1</td><td>19</td><td>20</td>
              </tr>
              <tr>
                <td>95</td><td>4920</td><td>5020</td><td>6.8</td><td>6.7</td>
                <td>3.22</td><td>3.20</td><td>268</td><td>270</td>
                <td>23.1</td><td>23.3</td><td>18</td><td>19</td>
              </tr>
              <tr><td colspan="14">各项指标的权重比例：肺活量：15%</td></tr>
            </table>
        """.trimIndent()

        val male = parser.parseStandard(html).single()

        assertEquals(12, male.headers.size)
        assertEquals("肺活量（12年级）", male.headers.first())
        assertEquals("引体向上（34年级）", male.headers.last())
        assertEquals(12, male.rows[0].values.size)
        assertEquals("5040", male.rows[0].values.first())
        assertEquals("20", male.rows[0].values.last())
        assertEquals(12, male.rows[1].values.size)
    }
}

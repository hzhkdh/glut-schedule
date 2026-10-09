package com.glut.schedule

import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.ui.pages.ScoreDisplayPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成绩页展示口径（I2）。
 *
 * 这条规则原来写死在 `ScoreScreen` 里（`term == 2 → 学年起点`），加入研究生后不成立：
 * 研究生第 1 学期才是秋季，而且门户给的 `Year` 是 `2026-2027` 这种**学年串**
 * （已归一为起始年 2026），两个学期同属这一年。照抄本科口径会把春季成绩挪到早一年。
 */
class ScoreDisplayPolicyTest {

    private fun score(name: String, year: String, term: Int) = ScoreInfo(
        id = ScoreInfo.stableId(name, year, term),
        courseName = name,
        score = "85",
        gpa = 0.0,
        credit = 2.0,
        year = year,
        term = term,
        category = "学位课",
        examType = ""
    )

    /** 复刻 ScoreScreen 的分组键，供本测试独立断言。 */
    private fun groupKey(score: ScoreInfo, policy: ScoreDisplayPolicy): Int {
        val y = score.year.toIntOrNull() ?: 0
        return when {
            policy.yearIsAcademicStartYear -> y
            score.term in policy.autumnTermNumbers -> y
            else -> y - 1
        }
    }

    @Test
    fun graduatePutsBothTermsOfAnAcademicYearUnderTheSameStartYear() {
        val policy = ScoreDisplayPolicy.of(CampusType.GRADUATE)
        // 2026-2027 第一学期（秋季，term=1）与第二学期（春季，term=2）。
        assertEquals(2026, groupKey(score("合成课程甲", "2026", 1), policy))
        assertEquals(2026, groupKey(score("合成课程乙", "2026", 2), policy))
        // 秋季是第 1 学期、春季是第 2 学期——与本科相反。
        assertTrue(1 in policy.autumnTermNumbers)
        assertTrue(2 in policy.springTermNumbers)
        assertTrue(policy.yearIsAcademicStartYear)
        assertFalse(policy.supportsGpa)
    }

    @Test
    fun guilinAndNanningKeepTheirExistingGroupingRule() {
        listOf(CampusType.GUILIN, CampusType.NANNING).forEach { campus ->
            val policy = ScoreDisplayPolicy.of(campus)
            assertFalse(policy.yearIsAcademicStartYear)
            assertTrue(policy.supportsGpa)
            // 第 2 学期是秋季、是学年起点；春季回退一年。与改动前逐字一致。
            assertEquals(2026, groupKey(score("合成课程甲", "2026", 2), policy))
            assertEquals(2025, groupKey(score("合成课程乙", "2026", 1), policy))
        }
    }

    /**
     * 学期号无法归类时不能被丢掉。
     *
     * `term = 0`（门户改了格式、`Term` 解析失败）既不在秋栏也不在春栏；
     * 若只按两栏过滤，这条成绩会从界面上消失而一切看起来正常。
     */
    @Test
    fun unclassifiableTermIsKeptInTheOtherBucket() {
        val policy = ScoreDisplayPolicy.of(CampusType.GRADUATE)
        val scores = listOf(
            score("合成秋季课", "2026", 1),
            score("合成春季课", "2026", 2),
            score("合成未知学期课", "2026", 0)
        )
        val autumn = scores.filter { it.term in policy.autumnTermNumbers }
        val spring = scores.filter { it.term in policy.springTermNumbers }
        val other = scores.filter {
            it.term !in policy.autumnTermNumbers && it.term !in policy.springTermNumbers
        }
        assertEquals(listOf("合成秋季课"), autumn.map { it.courseName })
        assertEquals(listOf("合成春季课"), spring.map { it.courseName })
        assertEquals(listOf("合成未知学期课"), other.map { it.courseName })
        // 三栏合计必须等于全部成绩，一条都不能少。
        assertEquals(scores.size, autumn.size + spring.size + other.size)
    }
}

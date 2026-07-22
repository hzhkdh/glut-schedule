package com.glut.schedule.service.parser

import com.glut.schedule.data.model.AcademicEnrollment
import com.glut.schedule.data.model.AcademicEnrollmentSource
import com.glut.schedule.data.model.AcademicSemester
import com.glut.schedule.data.model.SemesterSeason
import com.glut.schedule.data.settings.CampusType
import java.time.LocalDate

object AcademicSemesterParser {
    private val selectRegex = Regex("""<select\b[^>]*name\s*=\s*[\"']([^\"']+)[\"'][^>]*>([\s\S]*?)</select>""", RegexOption.IGNORE_CASE)
    private val optionRegex = Regex("""<option\b([^>]*)>([\s\S]*?)</option>""", RegexOption.IGNORE_CASE)
    private val inputRegex = Regex("""<input\b([^>]*)>""", RegexOption.IGNORE_CASE)
    private val attributeRegex = Regex("""([\w:-]+)\s*=\s*([\"'])(.*?)\2""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val tagRegex = Regex("<[^>]+>")

    fun parseEnrollment(
        html: String,
        studentNumber: String = "",
        currentYear: Int = LocalDate.now().year
    ): AcademicEnrollment? {
        val fields = inputRegex.findAll(html).mapNotNull { match ->
            val attrs = attributes(match.groupValues[1])
            val name = attrs["name"] ?: return@mapNotNull null
            name.lowercase() to attrs["value"].orEmpty().trim()
        }.toMap()
        val plausibleYears = 2000..(currentYear + 1)
        val entranceDate = fields["entrancedate"]
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.takeIf { it.year in plausibleYears }
        val portalYear = sequenceOf("enrollyearid", "entrancegradeid", "gradeid")
            .mapNotNull { fields[it]?.let { value -> normalizePortalYear(value, plausibleYears) } }
            .firstOrNull()
        val studentYear = studentNumber.trim()
            .takeIf { it.length >= 3 && it.all(Char::isDigit) }
            ?.substring(1, 3)
            ?.toIntOrNull()
            ?.plus(2000)
        val enrollmentYear = entranceDate?.year ?: portalYear ?: studentYear ?: return null
        val source = when {
            entranceDate != null -> AcademicEnrollmentSource.ENTRANCE_DATE
            portalYear != null -> AcademicEnrollmentSource.PORTAL_FIELD
            else -> AcademicEnrollmentSource.STUDENT_NUMBER
        }
        return AcademicEnrollment(
            entranceDate = entranceDate,
            enrollmentYear = enrollmentYear,
            source = source,
            isConsistent = entranceDate == null || portalYear == null || entranceDate.year == portalYear
        )
    }

    fun parseCatalog(
        html: String,
        campus: CampusType,
        enrollmentDate: LocalDate,
        today: LocalDate = LocalDate.now()
    ): List<AcademicSemester> {
        val selects = selectRegex.findAll(html).associate { match ->
            match.groupValues[1].lowercase() to parseOptions(match.groupValues[2])
        }
        val years = selects["year"].orEmpty().mapNotNull { option ->
            val year = option.text.filter(Char::isDigit).take(4).toIntOrNull() ?: return@mapNotNull null
            PortalYear(year, option.value, option.selected)
        }.distinctBy { it.year }
        if (years.isEmpty()) return emptyList()

        val parsedTerms = selects["term"].orEmpty().mapNotNull { option ->
            val season = when {
                option.text.contains("春") -> SemesterSeason.SPRING
                option.text.contains("秋") -> SemesterSeason.AUTUMN
                else -> null
            } ?: return@mapNotNull null
            PortalTerm(season, option.value, option.selected)
        }
        val terms = if (parsedTerms.isNotEmpty()) parsedTerms else defaultTerms(campus)

        val selectedYear = years.firstOrNull { it.selected }?.year ?: today.year
        val selectedSeason = terms.firstOrNull { it.selected }?.season ?: seasonForDate(today)
        val enrollmentSeason = SemesterSeason.AUTUMN

        return years.sortedByDescending { it.year }.flatMap { year ->
            listOf(SemesterSeason.SPRING, SemesterSeason.AUTUMN).mapNotNull { season ->
                val term = terms.firstOrNull { it.season == season } ?: return@mapNotNull null
                if (!isAtOrAfter(year.year, season, enrollmentDate.year, enrollmentSeason)) return@mapNotNull null
                if (!isAtOrBefore(year.year, season, selectedYear, selectedSeason)) return@mapNotNull null
                AcademicSemester.create(
                    campus = campus,
                    portalYear = year.year,
                    portalYearId = year.value,
                    season = season,
                    portalTermId = term.value,
                    isCurrent = year.year == selectedYear && season == selectedSeason
                )
            }
        }
    }

    private fun parseOptions(body: String): List<Option> = optionRegex.findAll(body).map { match ->
        val rawAttrs = match.groupValues[1]
        val attrs = attributes(rawAttrs)
        Option(
            value = attrs["value"].orEmpty(),
            text = tagRegex.replace(match.groupValues[2], "").trim(),
            selected = Regex("""\bselected\b""", RegexOption.IGNORE_CASE).containsMatchIn(rawAttrs)
        )
    }.toList()

    private fun attributes(raw: String): Map<String, String> = attributeRegex.findAll(raw).associate { match ->
        match.groupValues[1].lowercase() to match.groupValues[3]
    }

    private fun normalizePortalYear(value: String, plausibleYears: IntRange): Int? {
        val year = value.trim().toIntOrNull() ?: return null
        return when {
            year in 1..99 -> year + 1980
            year in plausibleYears -> year
            else -> null
        }
    }

    private fun defaultTerms(campus: CampusType): List<PortalTerm> = listOf(
        PortalTerm(SemesterSeason.SPRING, "1", false),
        PortalTerm(SemesterSeason.AUTUMN, if (campus == CampusType.GUILIN) "2" else "3", false)
    )

    private fun seasonForDate(date: LocalDate): SemesterSeason =
        if (date.monthValue >= 8) SemesterSeason.AUTUMN else SemesterSeason.SPRING

    private fun isAtOrAfter(year: Int, season: SemesterSeason, otherYear: Int, otherSeason: SemesterSeason): Boolean =
        semesterOrdinal(year, season) >= semesterOrdinal(otherYear, otherSeason)

    private fun isAtOrBefore(year: Int, season: SemesterSeason, otherYear: Int, otherSeason: SemesterSeason): Boolean =
        semesterOrdinal(year, season) <= semesterOrdinal(otherYear, otherSeason)

    private fun semesterOrdinal(year: Int, season: SemesterSeason): Int =
        year * 2 + if (season == SemesterSeason.SPRING) 0 else 1

    private data class Option(val value: String, val text: String, val selected: Boolean)
    private data class PortalYear(val year: Int, val value: String, val selected: Boolean)
    private data class PortalTerm(val season: SemesterSeason, val value: String, val selected: Boolean)
}

# Nanning Timetable Import Fix + Dual-Campus Periods — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix Nanning campus timetable import (showing "0 courses") and add dual-campus class period support.

**Architecture:** New `NanningCurrcourseParser` handles `currcourse.jsdo` nested-table HTML format by preprocessing out `<table class="none">` blocks before regex parsing. `CompositeScheduleParser` tries Guilin parser first, then Nanning. `CampusType` enum in DataStore drives period selection. URL discovery prioritizes `currcourse.jsdo` for Nanning with 3-tier fallback.

**Tech Stack:** Kotlin, OkHttp, Android DataStore, Room, JUnit 4

---

### Task 1: Add campus-specific class period definitions

**Files:** Modify `app/src/main/java/com/glut/schedule/data/model/ScheduleModels.kt`

- [ ] **Step 1: Add `guilinClassPeriods()` and `nanningClassPeriods()`**

After the existing `defaultClassPeriods()` (line ~151), add:

```kotlin
fun guilinClassPeriods(): List<ClassPeriod> = listOf(
    ClassPeriod(1, "08:30", "09:15"),
    ClassPeriod(2, "09:20", "10:05"),
    ClassPeriod(3, "10:25", "11:10"),
    ClassPeriod(4, "11:15", "12:00"),
    ClassPeriod(5, "14:30", "15:15"),
    ClassPeriod(6, "15:20", "16:05"),
    ClassPeriod(7, "16:25", "17:10"),
    ClassPeriod(8, "17:15", "18:00"),
    ClassPeriod(9, "18:30", "19:15"),
    ClassPeriod(10, "19:20", "20:05"),
    ClassPeriod(11, "20:10", "20:55"),
    ClassPeriod(12, "21:00", "21:45")
)

fun nanningClassPeriods(): List<ClassPeriod> = listOf(
    ClassPeriod(1, "08:40", "09:20"),
    ClassPeriod(2, "09:25", "10:05"),
    ClassPeriod(3, "10:25", "11:05"),
    ClassPeriod(4, "11:10", "11:50"),
    ClassPeriod(5, "14:30", "15:10"),
    ClassPeriod(6, "15:15", "15:55"),
    ClassPeriod(7, "16:05", "16:45"),
    ClassPeriod(8, "16:50", "17:30"),
    ClassPeriod(9, "19:30", "20:10"),
    ClassPeriod(10, "20:15", "20:55"),
    ClassPeriod(11, "21:00", "21:40")
)
```

- [ ] **Step 2: Update `defaultClassPeriods()` to delegate**

Replace body with: `fun defaultClassPeriods(): List<ClassPeriod> = guilinClassPeriods()`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/glut/schedule/data/model/ScheduleModels.kt
git commit -m "ref(nanning): Extract campus-specific class period definitions"
```

---

### Task 2: Add CampusType to settings store

**Files:** Modify `app/src/main/java/com/glut/schedule/data/settings/ScheduleSettingsStore.kt`

- [ ] **Step 1: Add `CampusType` enum**

At top of file after imports, before `class ScheduleSettingsStore`:

```kotlin
enum class CampusType { GUILIN, NANNING }
```

- [ ] **Step 2: Add DataStore key**

Inside the class, after `customBackgroundUriKey`:

```kotlin
private val campusTypeKey = stringPreferencesKey("campus_type")
```

- [ ] **Step 3: Add `campusType` Flow and setter**

After `customBackgroundUri` Flow:

```kotlin
val campusType: Flow<CampusType> = context.scheduleSettings.data.map { preferences ->
    val name = preferences[campusTypeKey] ?: CampusType.GUILIN.name
    runCatching { CampusType.valueOf(name) }.getOrDefault(CampusType.GUILIN)
}

suspend fun setCampusType(type: CampusType) {
    context.scheduleSettings.edit { preferences ->
        preferences[campusTypeKey] = type.name
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/glut/schedule/data/settings/ScheduleSettingsStore.kt
git commit -m "feat(nanning): Add CampusType to settings store"
```

---

### Task 3: Make repository campus-aware for periods

**Files:** Modify `app/src/main/java/com/glut/schedule/data/repository/ScheduleRepository.kt` and `ScheduleApplication.kt`

- [ ] **Step 1: Add `ScheduleSettingsStore` to repository constructor**

```kotlin
import com.glut.schedule.data.settings.CampusType
import com.glut.schedule.data.settings.ScheduleSettingsStore
import com.glut.schedule.data.model.guilinClassPeriods
import com.glut.schedule.data.model.nanningClassPeriods

class ScheduleRepository(
    private val dao: ScheduleDao,
    private val settingsStore: ScheduleSettingsStore
) {
```

- [ ] **Step 2: Update `classPeriods` Flow**

```kotlin
val classPeriods: Flow<List<ClassPeriod>> = combine(
    dao.observeClassPeriods(),
    settingsStore.campusType
) { periods, campusType ->
    periods.map { it.toModel() }.ifEmpty {
        when (campusType) {
            CampusType.GUILIN -> guilinClassPeriods()
            CampusType.NANNING -> nanningClassPeriods()
        }
    }
}
```

Remove old `import com.glut.schedule.data.model.defaultClassPeriods`.

- [ ] **Step 3: Wire in ScheduleApplication.kt**

Change line 37 to: `val scheduleRepository = ScheduleRepository(database.scheduleDao(), settingsStore)`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/glut/schedule/data/repository/ScheduleRepository.kt
git add app/src/main/java/com/glut/schedule/ScheduleApplication.kt
git commit -m "feat(nanning): Campus-aware class periods in repository"
```

---

### Task 4: Create NanningCurrcourseParser

**Files:** Create `app/src/main/java/com/glut/schedule/service/parser/NanningCurrcourseParser.kt`

- [ ] **Step 1: Write the parser**

```kotlin
package com.glut.schedule.service.parser

import com.glut.schedule.data.model.CourseOccurrence
import com.glut.schedule.data.model.CourseColorMapper
import com.glut.schedule.data.model.ScheduleCourse
import java.security.MessageDigest

class NanningCurrcourseParser : AcademicScheduleParser {

    override fun parsePersonalSchedule(html: String): List<ScheduleCourse> {
        if (html.isBlank()) return emptyList()
        if (!html.contains("infolist_common")) return emptyList()

        // Step 1: Extract all <table class="none">...</table> blocks BEFORE parsing rows.
        // This avoids the nested-</tr> regex trap that breaks GlutAcademicScheduleParser.
        val nestedTables = mutableListOf<String>()
        val cleanedHtml = nestedTableRegex.replace(html) { match ->
            nestedTables.add(match.value)
            "<!--NESTED_${nestedTables.lastIndex}-->"
        }

        // Step 2: Parse infolist_common rows in the cleaned HTML
        val rowMatches = infolistRowRegex.findAll(cleanedHtml).toList()
        if (rowMatches.isEmpty()) return emptyList()

        val courses = mutableListOf<ScheduleCourse>()

        for (rowMatch in rowMatches) {
            val rowHtml = rowMatch.value

            // Extract course name from <a class="infolist">
            val title = courseNameRegex.find(rowHtml)?.groupValues?.get(1)?.trim()
                ?: continue

            // Extract teacher from <a href='...teacherinfo...'>
            val teacher = teacherRegex.find(rowHtml)?.groupValues?.get(1)?.trim()
                ?: "待确认"

            // Find nested table placeholder for this row
            val placeholderMatch = nestedPlaceholderRegex.find(rowHtml)
            if (placeholderMatch == null) continue // MOOC course, no time/location

            val tableIdx = placeholderMatch.groupValues[1].toIntOrNull() ?: continue
            if (tableIdx !in nestedTables.indices) continue

            // Step 3: Parse the nested table.none rows
            val nestedHtml = nestedTables[tableIdx]
            val timeRows = tableRowRegex.findAll(nestedHtml).toList()

            val occurrences = mutableListOf<CourseOccurrence>()
            val id = "import-nn-${stableId("$title-$teacher")}"

            for ((occIdx, timeRow) in timeRows.withIndex()) {
                val cells = tableCellRegex.findAll(timeRow.value)
                    .map { it.groupValues[1].trim() }
                    .map { htmlToPlainText(it) }
                    .toList()
                if (cells.size < 4) continue

                val weekText = cells[0]    // "14-15周" or "11-18周单"
                val dayOfWeek = parseWeekday(cells[1]) // "星期一" -> 1
                if (dayOfWeek == 0) continue

                val (startSection, endSection) = parsePeriodRange(cells[2])
                if (startSection == 0) continue

                val room = cells[3].takeUnless {
                    it == "&nbsp;" || it.isBlank()
                }.orEmpty()

                occurrences.add(
                    CourseOccurrence(
                        id = "$id-occurrence-$occIdx",
                        courseId = id,
                        dayOfWeek = dayOfWeek.coerceIn(1, 7),
                        startSection = startSection.coerceIn(1, 11),
                        endSection = endSection.coerceIn(startSection, 11),
                        weekText = weekText,
                        note = room
                    )
                )
            }

            if (occurrences.isNotEmpty()) {
                courses.add(
                    ScheduleCourse(
                        id = id, title = title,
                        room = occurrences.firstOrNull()?.note.orEmpty(),
                        teacher = teacher,
                        colorHex = CourseColorMapper.colorForCourse(id, title),
                        occurrences = occurrences
                    )
                )
            }
        }

        return CourseColorMapper.assignColors(courses)
    }

    private fun parseWeekday(text: String): Int = when {
        text.contains("一") -> 1; text.contains("二") -> 2
        text.contains("三") -> 3; text.contains("四") -> 4
        text.contains("五") -> 5; text.contains("六") -> 6
        text.contains("日") || text.contains("天") -> 7
        else -> 0
    }

    private fun parsePeriodRange(text: String): Pair<Int, Int> {
        val clean = text.replace("第", "").replace("节", "").trim()
        val parts = clean.split("-", "－", "—")
        val start = parts.getOrNull(0)?.toIntOrNull() ?: return 0 to 0
        val end = parts.getOrNull(1)?.toIntOrNull() ?: start
        return start to end
    }

    private fun htmlToPlainText(html: String): String {
        return html.replace(Regex("""<[^>]+>"""), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").trim()
    }

    private fun stableId(value: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    private companion object {
        val nestedTableRegex = Regex(
            """(?is)<table\b[^>]*class\s*=\s*["']none["'][^>]*>.*?</table>"""
        )
        val infolistRowRegex = Regex(
            """(?is)<tr\b[^>]*class\s*=\s*["']infolist_common["'][^>]*>.*?</tr>"""
        )
        val nestedPlaceholderRegex = Regex("""<!--NESTED_(\d+)-->""")
        val courseNameRegex = Regex(
            """(?is)<a\b[^>]*class\s*=\s*["']infolist["'][^>]*>\s*([^<]+?)\s*</a>"""
        )
        val teacherRegex = Regex(
            """(?is)<a\b[^>]*teacherinfo[^>]*>\s*([^<]+?)\s*</a>"""
        )
        val tableRowRegex = Regex("""(?is)<tr\b[^>]*>(.*?)</tr>""")
        val tableCellRegex = Regex("""(?is)<t[dh]\b[^>]*>(.*?)</t[dh]>""")
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/glut/schedule/service/parser/NanningCurrcourseParser.kt
git commit -m "feat(nanning): Add NanningCurrcourseParser for currcourse.jsdo HTML"
```

---

### Task 5: Create CompositeScheduleParser + wire DI

**Files:** Create `app/src/main/java/com/glut/schedule/service/parser/CompositeScheduleParser.kt`, modify `ScheduleApplication.kt`

- [ ] **Step 1: Write composite parser**

```kotlin
package com.glut.schedule.service.parser

import com.glut.schedule.data.model.ScheduleCourse

class CompositeScheduleParser(
    private val parsers: List<AcademicScheduleParser>
) : AcademicScheduleParser {

    override fun parsePersonalSchedule(html: String): List<ScheduleCourse> {
        for (parser in parsers) {
            val result = runCatching {
                parser.parsePersonalSchedule(html)
            }.getOrDefault(emptyList())
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }
}
```

- [ ] **Step 2: Wire in ScheduleApplication.kt**

Change line 41 from `val academicScheduleParser = GlutAcademicScheduleParser()` to:

```kotlin
import com.glut.schedule.service.parser.CompositeScheduleParser
import com.glut.schedule.service.parser.NanningCurrcourseParser

val academicScheduleParser = CompositeScheduleParser(
    listOf(GlutAcademicScheduleParser(), NanningCurrcourseParser())
)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/glut/schedule/service/parser/CompositeScheduleParser.kt
git add app/src/main/java/com/glut/schedule/ScheduleApplication.kt
git commit -m "feat(nanning): Add CompositeScheduleParser for multi-format support"
```

---

### Task 6: Add currcourse extraction helpers to ApiProbeService

**Files:** Modify `app/src/main/java/com/glut/schedule/service/academic/ApiProbeService.kt`

- [ ] **Step 1: Add extraction helpers to companion object**

In `companion object`, after `findTodayPlanJsonResult`:

```kotlin
fun extractInternalIdFromCurrcourse(body: String): String? {
    return Regex("""showTimetable\.do\?id=(\d+)""").find(body)?.groupValues?.get(1)
}

fun extractYearIdFromCurrcourse(body: String): String? {
    return Regex("""showTimetable\.do\?id=\d+&yearid=(\d+)""").find(body)?.groupValues?.get(1)
}

fun extractTermIdFromCurrcourse(body: String): String? {
    return Regex("""showTimetable\.do\?id=\d+&yearid=\d+&termid=(\d+)""").find(body)?.groupValues?.get(1)
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/glut/schedule/service/academic/ApiProbeService.kt
git commit -m "feat(nanning): Add currcourse.jsdo param extraction helpers"
```

---

### Task 7: Fix Nanning URL discovery + propagate campus type

**Files:** Modify `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt`

- [ ] **Step 1: Replace Nanning fallback (lines ~396–407)**

Replace the old fallback block with 3-tier priority:

```kotlin
            // Nanning: currcourse.jsdo is the primary schedule endpoint.
            // showTimetable.do requires yearid/termid extracted from currcourse.jsdo.
            if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                // Priority 1: currcourse.jsdo (primary Nanning endpoint)
                val currcourse = results.find {
                    it.url.contains("currcourse.jsdo") && it.httpCode == 200
                }
                if (currcourse != null && currcourse.body.length > 1000) {
                    htmlResult = currcourse
                }

                // Priority 2: showTimetable.do with params from currcourse.jsdo
                if (htmlResult == null) {
                    val currcourseBody = results
                        .find { it.url.contains("currcourse.jsdo") }?.body ?: ""
                    val extractedId = ApiProbeService.extractInternalIdFromCurrcourse(currcourseBody)
                    val extractedYearId = ApiProbeService.extractYearIdFromCurrcourse(currcourseBody)
                    val extractedTermId = ApiProbeService.extractTermIdFromCurrcourse(currcourseBody)
                    if (extractedId != null) {
                        val showUrl = "$campusBaseUrl/academic/manager/coursearrange/showTimetable.do" +
                            "?id=$extractedId" +
                            "&yearid=${extractedYearId ?: "46"}" +
                            "&termid=${extractedTermId ?: "1"}" +
                            "&timetableType=STUDENT&sectionType=BASE"
                        htmlResult = apiProbeService.probeUrl(cookie, showUrl)
                    }
                }

                // Priority 3: Last resort with student ID + default yearid/termid
                if (htmlResult == null) {
                    val username = _uiState.value.username
                    if (username.isNotBlank()) {
                        val fallbackUrl = "$campusBaseUrl/academic/manager/coursearrange/showTimetable.do" +
                            "?id=$username&yearid=46&termid=1&timetableType=STUDENT&sectionType=BASE"
                        htmlResult = apiProbeService.probeUrl(cookie, fallbackUrl)
                    }
                }
            }
```

- [ ] **Step 2: Add campus type propagation in `onLoginSuccess`**

After `sessionStore.saveCookie(cookie)` (line ~376):

```kotlin
            val campusType = if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
                com.glut.schedule.data.settings.CampusType.NANNING
            } else {
                com.glut.schedule.data.settings.CampusType.GUILIN
            }
            settingsStore.setCampusType(campusType)
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt
git commit -m "feat(nanning): Fix Nanning URL discovery with 3-tier priority + campus propagation"
```

---

### Task 8: Recognize glutnn.cn domain

**Files:** Modify `app/src/main/java/com/glut/schedule/service/academic/AcademicImportConfig.kt`

- [ ] **Step 1: Update `isAcademicDomainPage` (line ~88)**

```kotlin
fun isAcademicDomainPage(url: String): Boolean {
    return runCatching {
        val uri = URI(url)
        uri.host.contains("glut.edu.cn", ignoreCase = true) ||
            uri.host.contains("glutnn.cn", ignoreCase = true)
    }.getOrDefault(false)
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/glut/schedule/service/academic/AcademicImportConfig.kt
git commit -m "fix(nanning): Recognize glutnn.cn as valid academic domain"
```

---

### Task 9: Write unit tests for NanningCurrcourseParser

**Files:** Create `app/src/test/java/com/glut/schedule/NanningCurrcourseParserTest.kt`

- [ ] **Step 1: Write test class**

```kotlin
package com.glut.schedule

import com.glut.schedule.service.parser.NanningCurrcourseParser
import org.junit.Assert.*
import org.junit.Test

class NanningCurrcourseParserTest {
    private val parser = NanningCurrcourseParser()

    @Test fun returnsEmptyForBlankInput() {
        assertTrue(parser.parsePersonalSchedule("").isEmpty())
    }

    @Test fun returnsEmptyForNonCurrcourseHtml() {
        assertTrue(parser.parsePersonalSchedule("<html><body><p>Hi</p></body></html>").isEmpty())
    }

    @Test fun parsesSingleCourseWithOneTimeSlot() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">高等数学</a></td>
                <td class="center"><a href='...teacherinfo...' class="infolist">张三</a></td>
                <td><table class="none"><tr>
                    <td>1-18周</td><td>星期一</td><td>第1-2节</td><td>06104</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()
        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        assertEquals("高等数学", courses[0].title)
        assertEquals("张三", courses[0].teacher)
        assertEquals(1, courses[0].occurrences[0].dayOfWeek)
        assertEquals(1, courses[0].occurrences[0].startSection)
        assertEquals(2, courses[0].occurrences[0].endSection)
    }

    @Test fun parsesCourseWithMultipleTimeSlots() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">大学英语</a></td>
                <td class="center"><a href='...teacherinfo...' class="infolist">李四</a></td>
                <td><table class="none">
                    <tr><td>1-18周</td><td>星期二</td><td>第1-2节</td><td>06201</td></tr>
                    <tr><td>1-18周</td><td>星期四</td><td>第3-4节</td><td>06201</td></tr>
                </table></td>
            </tr></table>
        """.trimIndent()
        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        assertEquals(2, courses[0].occurrences.size)
    }

    @Test fun skipsMoocCourseWithoutNestedTable() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">广播电视概论（慕课）</a></td>
                <td class="center"></td><td></td>
            </tr></table>
        """.trimIndent()
        assertTrue(parser.parsePersonalSchedule(html).isEmpty())
    }

    @Test fun handlesEmptyTeacher() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">某课程</a></td>
                <td class="center"></td>
                <td><table class="none"><tr>
                    <td>1-9周</td><td>星期三</td><td>第5-6节</td><td>8301</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()
        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        assertEquals("待确认", courses[0].teacher)
    }

    @Test fun handlesSingleDoubleWeekNotation() {
        val html = """
            <table class="infolist_tab"><tr class="infolist_common">
                <td><a class="infolist">体育</a></td>
                <td class="center"><a href='...teacherinfo...' class="infolist">王五</a></td>
                <td><table class="none"><tr>
                    <td>1-18周单</td><td>星期五</td><td>第3-4节</td><td>操场</td>
                </tr></table></td>
            </tr></table>
        """.trimIndent()
        val courses = parser.parsePersonalSchedule(html)
        assertEquals(1, courses.size)
        assertEquals("1-18周单", courses[0].occurrences[0].weekText)
    }

    @Test fun parsesMultipleCourses() {
        val html = """
            <table class="infolist_tab">
                <tr class="infolist_common">
                    <td><a class="infolist">高等数学</a></td>
                    <td class="center"><a href='...teacherinfo...' class="infolist">张三</a></td>
                    <td><table class="none"><tr><td>1-18周</td><td>星期一</td><td>第1-2节</td><td>06104</td></tr></table></td>
                </tr>
                <tr class="infolist_common">
                    <td><a class="infolist">大学英语</a></td>
                    <td class="center"><a href='...teacherinfo...' class="infolist">李四</a></td>
                    <td><table class="none"><tr><td>1-18周</td><td>星期二</td><td>第1-2节</td><td>06201</td></tr></table></td>
                </tr>
            </table>
        """.trimIndent()
        val courses = parser.parsePersonalSchedule(html)
        assertEquals(2, courses.size)
    }
}
```

- [ ] **Step 2: Run tests**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.NanningCurrcourseParserTest"
```

Expected: All 7 tests pass.

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/glut/schedule/NanningCurrcourseParserTest.kt
git commit -m "test(nanning): Add NanningCurrcourseParser unit tests"
```

---

### Task 10: Integration test with real HTML + build

- [ ] **Step 1: Verify real HTML parse count**

```powershell
conda run -n claude python -c "
import re
html = open('nanning_currcourse.html', 'r', encoding='utf-8').read()
nested = len(re.findall(r'<table[^>]*class=\\\"none\\\"[^>]*>', html))
infolist = len(re.findall(r'infolist_common', html))
print(f'infolist_common rows: {infolist}, table.none blocks: {nested}')
"
```

Expected: `infolist_common` > 10, `table.none` > 5.

- [ ] **Step 2: Run ALL unit tests**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat testDebugUnitTest
```

Expected: All tests pass (existing + new).

- [ ] **Step 3: Build debug APK**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Bump version**

In `app/build.gradle.kts`:
```
versionCode = 44
versionName = "0.7.10"
```

- [ ] **Step 5: Build release APK**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleRelease
```

Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/release/glutShedule_0.7.10.apk`.

- [ ] **Step 6: Final commit**

```bash
git add app/build.gradle.kts
git commit -m "release: v0.7.10 - Nanning timetable import fix + dual-campus periods
Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Verification Checklist

- [ ] `guilinClassPeriods()` contains 12 periods matching original defaults
- [ ] `nanningClassPeriods()` contains 11 periods matching GlutAssistantN
- [ ] `CompositeScheduleParser` returns first non-empty result (Guilin first, Nanning fallback)
- [ ] Nanning URL discovery: Priority 1 = `currcourse.jsdo`, Priority 2 = `showTimetable.do` with extracted params, Priority 3 = fallback with student ID
- [ ] `NanningCurrcourseParser` preprocesses nested `table.none` blocks to avoid regex nesting trap
- [ ] Campus type persisted in DataStore, defaults to `GUILIN`
- [ ] `classPeriods` Flow reacts to campus type: empty DB + NANNING → 11 periods
- [ ] All existing Guilin import unit tests pass unchanged
- [ ] Release APK built successfully

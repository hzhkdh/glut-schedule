# Nanning Campus Timetable Import Fix + Dual-Campus Periods

**Date:** 2026-06-07
**Status:** Draft
**Author:** Claude Code

## 1. Problem Analysis

### 1.1 Symptoms
- Nanning campus login works, exam/score import works
- Timetable import shows "0 courses"
- Old Guilin schedule persists after Nanning import

### 1.2 Root Cause (Three Layers)

**Layer 1 — URL Construction Error**
- Nanning fallback constructs `showTimetable.do?id=$username` WITHOUT `yearid`/`termid` params
- Result: "学年传递错误" (academic year transmission error)
- Correct URL (extracted from `currcourse.jsdo`): `showTimetable.do?id=237607&yearid=46&termid=1&timetableType=STUDENT&sectionType=BASE`
- `framePage.do` returns 404 for Nanning (redirect chain uses `./index_frame.jsp` relative path), so `buildCurrentStudentTimetableUrls` can't extract internal user ID

**Layer 2 — Parser Incompatibility**
- `GlutAcademicScheduleParser` uses regex-based HTML parsing (`rowRegex`, `tableCellRegex`)
- Nanning's `currcourse.jsdo` HTML has nested `<table class="none">` inside `<td>` cells
- Non-greedy regex `.*?` in `rowRegex` stops at first `</tr>` which is INSIDE the nested table
- This mangies cell boundaries, making `parseArrangementOccurrences` unable to match
- GlutAssistantN uses a proper DOM parser (`package:html/parser.dart`) which handles nesting

**Layer 3 — Course Data Only in `currcourse.jsdo`**
- `showTimetable.do` for Nanning requires `yearid`/`termid` (extracted from `currcourse.jsdo`)
- `currcourse.jsdo` (59168 bytes) is the PRIMARY schedule endpoint for Nanning — confirmed working
- GlutAssistantN exclusively uses `currcourse.jsdo` for Nanning

### 1.3 Class Period Differences

| | Guilin | Nanning |
|---|---|---|
| Periods | 12 | 11 |
| Period 1 start | 08:30 | 08:40 |
| Morning block | 08:30–12:00 | 08:40–11:50 |
| Afternoon start | 14:30 | 14:30 |
| Evening start | 18:30 | 19:30 |

Current code hardcodes `coerceIn(1, 12)` in the parser and uses single `defaultClassPeriods()`.

## 2. Solution Overview

Three components:

1. **NanningCurrcourseParser** — new dedicated parser for `currcourse.jsdo` HTML format
2. **URL Discovery Fix** — for Nanning campus, prioritize `currcourse.jsdo` and extract params from it
3. **Campus-Aware Class Periods** — store campus type, serve correct periods per campus

## 3. Detailed Design

### 3.1 NanningCurrcourseParser

**File:** `app/src/main/java/com/glut/schedule/service/parser/NanningCurrcourseParser.kt`

Implements `AcademicScheduleParser` interface.

**Reason for new parser:** The existing `GlutAcademicScheduleParser` uses regex-based HTML parsing
that breaks on nested `<table class="none">` inside `<td>` cells. A new parser uses targeted
regex on specific HTML patterns to avoid the nested-table regex trap.

**Parsing Strategy:**

Step 1: Pre-process — extract all `<table class="none">...</table>` blocks, store with index, replace with `<!--NESTED_N-->` placeholders.

Step 2: Parse infolist_common rows in the cleaned HTML using regex:
```kotlin
// Row pattern: <tr class="infolist_common"[^>]*>(.*?)</tr>
// No more nested </tr> issues since table.none blocks are removed

// From each row:
// - Course name: <a[^>]*class="infolist"[^>]*>([^<]+)</a>
// - Teacher: <a[^>]*teacherinfo[^>]*>([^<]+)</a>
// - If no teacher link → teacher = ""
```

Step 3: For each row, parse the corresponding nested table block:
```kotlin
// Each nested table block row: <tr> <td>WEEKS</td> <td>DAY</td> <td>PERIOD</td> <td>LOCATION</td> </tr>
// Week: strip "周" suffix, handle "单"/"双" notation
// Day: 星期一→1, 星期二→2, ..., 星期日→7
// Period: strip "第" prefix and "节" suffix, parse "5-6" → start=5, end=6
// Location: trim, "&nbsp;" → ""
```

Step 4: Build `ScheduleCourse` objects with `CourseOccurrence` for each time slot.

**Week parsing:** Reuse logic from `isWeekTextActive` and `expandActiveWeeks` in `ScheduleModels.kt`.
Handle: single week "11", range "11-14", comma-separated "11-14,16-17", single/double "11-14单", mixed "11-14,15-17单".

**Course ID:** `"import-nn-${stableId("$title-$teacher-$day-$period")}"` with "nn" prefix to avoid collision with Guilin imports.

**Edge cases handled:**
- MOOC courses with no time/location (no table.none) → skip
- Courses with empty teacher → "待确认"
- Courses with multiple time slots → multiple CourseOccurrence
- Missing location → use course note field as room

### 3.2 CompositeScheduleParser

**File:** `app/src/main/java/com/glut/schedule/service/parser/CompositeScheduleParser.kt`

```kotlin
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

Tries each parser in order. First non-empty result wins.
Order: `GlutAcademicScheduleParser` first (backward compatible), `NanningCurrcourseParser` second.

### 3.3 URL Discovery Fix

**File:** `DirectLoginViewModel.kt` — `performImport()` method

Replace the Nanning-specific fallback block (currently lines 396–407) with:

```kotlin
if (campusBaseUrl == AcademicLoginResult.NANNING_URL) {
    // Priority 1: currcourse.jsdo is the primary Nanning schedule endpoint
    val currcourse = results.find {
        it.url.contains("currcourse.jsdo") && it.httpCode == 200
    }
    if (currcourse != null && currcourse.body.length > 1000) {
        htmlResult = currcourse
    }

    // Priority 2: Try showTimetable.do with params extracted from currcourse.jsdo
    if (htmlResult == null) {
        val body = results.find { it.url.contains("currcourse.jsdo") }?.body ?: ""
        val extractedId = ApiProbeService.extractInternalIdFromCurrcourse(body)
        val extractedYearId = ApiProbeService.extractYearIdFromCurrcourse(body)
        val extractedTermId = ApiProbeService.extractTermIdFromCurrcourse(body)
        if (extractedId != null) {
            val url = "$campusBaseUrl/academic/manager/coursearrange/showTimetable.do" +
                "?id=$extractedId" +
                "&yearid=${extractedYearId ?: "46"}" +
                "&termid=${extractedTermId ?: "1"}" +
                "&timetableType=STUDENT&sectionType=BASE"
            htmlResult = apiProbeService.probeUrl(cookie, url)
        }
    }

    // Priority 3: Last resort fallback with student ID
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

**New helpers in ApiProbeService companion object:**

```kotlin
fun extractInternalIdFromCurrcourse(body: String): String? =
    Regex("""showTimetable\.do\?id=(\d+)""").find(body)?.groupValues?.get(1)

fun extractYearIdFromCurrcourse(body: String): String? =
    Regex("""showTimetable\.do\?id=\d+&yearid=(\d+)""").find(body)?.groupValues?.get(1)

fun extractTermIdFromCurrcourse(body: String): String? =
    Regex("""showTimetable\.do\?id=\d+&yearid=\d+&termid=(\d+)""").find(body)?.groupValues?.get(1)
```

### 3.4 Campus-Aware Class Periods

**New period definitions in ScheduleModels.kt:**

```kotlin
fun guilinClassPeriods(): List<ClassPeriod> = listOf(
    ClassPeriod(1, "08:30", "09:15"), ClassPeriod(2, "09:20", "10:05"),
    ClassPeriod(3, "10:25", "11:10"), ClassPeriod(4, "11:15", "12:00"),
    ClassPeriod(5, "14:30", "15:15"), ClassPeriod(6, "15:20", "16:05"),
    ClassPeriod(7, "16:25", "17:10"), ClassPeriod(8, "17:15", "18:00"),
    ClassPeriod(9, "18:30", "19:15"), ClassPeriod(10, "19:20", "20:05"),
    ClassPeriod(11, "20:10", "20:55"), ClassPeriod(12, "21:00", "21:45")
)

fun nanningClassPeriods(): List<ClassPeriod> = listOf(
    ClassPeriod(1, "08:40", "09:20"), ClassPeriod(2, "09:25", "10:05"),
    ClassPeriod(3, "10:25", "11:05"), ClassPeriod(4, "11:10", "11:50"),
    ClassPeriod(5, "14:30", "15:10"), ClassPeriod(6, "15:15", "15:55"),
    ClassPeriod(7, "16:05", "16:45"), ClassPeriod(8, "16:50", "17:30"),
    ClassPeriod(9, "19:30", "20:10"), ClassPeriod(10, "20:15", "20:55"),
    ClassPeriod(11, "21:00", "21:40")
)

// Backward compatible default
fun defaultClassPeriods(): List<ClassPeriod> = guilinClassPeriods()
```

**CampusType enum + settings in ScheduleSettingsStore.kt:**

```kotlin
enum class CampusType { GUILIN, NANNING }

// New DataStore key
val campusType: Flow<CampusType>

suspend fun setCampusType(type: CampusType)
```

**Repository update (ScheduleRepository.kt):**

```kotlin
val classPeriods: Flow<List<ClassPeriod>> = combine(
    dao.observeClassPeriods(),
    settingsStore.campusType
) { dbPeriods, campus ->
    if (dbPeriods.isNotEmpty()) dbPeriods
    else when (campus) {
        CampusType.GUILIN -> guilinClassPeriods()
        CampusType.NANNING -> nanningClassPeriods()
    }
}
```

**Campus type propagation (DirectLoginViewModel.kt — onLoginSuccess):**

```kotlin
settingsStore.setCampusType(
    if (campusBaseUrl == AcademicLoginResult.NANNING_URL) CampusType.NANNING
    else CampusType.GUILIN
)
```

### 3.5 Parser Section Range Bounds

`NanningCurrcourseParser` uses `coerceIn(1, 11)` for Nanning's 11 periods.
`GlutAcademicScheduleParser` continues using `coerceIn(1, 12)` for Guilin's 12 periods.

## 4. Data Flow (Post-Fix)

```
Nanning Login
  ↓
performImport()
  ↓
probeAllEndpoints(cookie, baseUrl=NANNING_URL)
  ↓
For Nanning: currcourse.jsdo → 200 OK, 59168 bytes
  ↓
findTimetableHtmlResult() → currcourse.jsdo (score 37)
  ↓
CompositeScheduleParser.parsePersonalSchedule(html)
  ├─ GlutAcademicScheduleParser → empty (can't parse Nanning format)
  └─ NanningCurrcourseParser → List<ScheduleCourse> with N courses
  ↓
scheduleRepository.replaceImportedCourses(courses)
  └─ Writes nanningClassPeriods() to DB
  └─ Sets campusType = NANNING in DataStore
  ↓
ScheduleScreen reads classPeriods flow → gets Nanning 11 periods
ScheduleGrid renders with correct time slots (08:40 start, etc.)
```

## 5. Files to Create

| File | Purpose |
|------|---------|
| `service/parser/NanningCurrcourseParser.kt` | Parses Nanning `currcourse.jsdo` HTML |
| `service/parser/CompositeScheduleParser.kt` | Tries parsers in order |

## 6. Files to Modify

| File | Changes |
|------|---------|
| `data/model/ScheduleModels.kt` | Add `guilinClassPeriods()`, `nanningClassPeriods()` |
| `data/settings/ScheduleSettingsStore.kt` | Add `campusType` flow, `CampusType` enum |
| `data/repository/ScheduleRepository.kt` | Campus-aware `classPeriods` flow |
| `service/academic/ApiProbeService.kt` | Add currcourse extraction helpers |
| `ui/pages/DirectLoginViewModel.kt` | 3-tier Nanning URL discovery + campus type propagation |
| `MainActivity.kt` | Wire `CompositeScheduleParser` |

## 7. Testing Strategy

### 7.1 Unit Tests

**NanningCurrcourseParserTest:**
- Real HTML from `nanning_currcourse.html` → parse and verify course count > 0
- Empty HTML → empty list
- Multi-slot course → correct occurrences array
- Empty teacher → "待确认"
- Single/double week notation → correct week text
- Missing nested table → course skipped (MOOC)

**ScheduleModelsTest:**
- `guilinClassPeriods()` size = 12
- `nanningClassPeriods()` size = 11

**CompositeScheduleParserTest:**
- Guilin HTML → Glut parser handles it
- Nanning HTML → Nanning parser handles it (Glut returns empty)

### 7.2 Manual Test Checklist

- [ ] Login with Nanning account → captcha dialog shown
- [ ] After login → timetable import shows course count > 0
- [ ] Schedule grid shows 11 periods with Nanning times
- [ ] Switch weeks → courses display correctly
- [ ] Login with Guilin account → 12 periods with Guilin times
- [ ] Switch back to Nanning → periods revert to 11

## 8. Release

- Version bump: increment `versionCode` and `versionName` minor version
- Build release APK after all tests pass

## 9. HTML Format Reference

Nanning `currcourse.jsdo` returns:
```html
<table class="infolist_tab">
    <tr> <!-- header: 课程号, 课程序号, 课程名称, 任课教师, ... --> </tr>
    <tr class="infolist_common">
        <td>COURSE_CODE</td>
        <td>SERIAL</td>
        <td><a class="infolist">COURSE_NAME</a></td>
        <td class="center"><a href='...teacherinfo...'>TEACHER</a></td>
        <!-- credits, type, exam, etc. -->
        <td>
            <table class="none">
                <tr>
                    <td>14-15周</td>      <!-- week range ± 单/双 -->
                    <td>星期一</td>        <!-- weekday -->
                    <td>第5-6节</td>      <!-- period range -->
                    <td>8203D</td>        <!-- room -->
                </tr>
                <!-- multiple rows for multi-slot courses -->
            </table>
        </td>
    </tr>
</table>
```

Weekday mapping: 星期一=1, 星期二=2, 星期三=3, 星期四=4, 星期五=5, 星期六=6, 星期日=7

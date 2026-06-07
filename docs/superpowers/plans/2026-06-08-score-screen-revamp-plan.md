# Score Screen Revamp Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken 8-POST score fetching loop with a single GET request, add horizontal chip semester filtering, and improve the score card UI with semester-block grouping and color-coded scores.

**Architecture:** Unify score fetching on a single GET to `studentOwnScore.do?year=&term=&para=0` (matching DirectLoginViewModel's working approach). Persist campus URL in DataStore so ScoreViewModel works for both Guilin and Nanning. Add LazyRow filter chips that filter the displayed semester blocks.

**Tech Stack:** Kotlin, Jetpack Compose + Material 3, Room, OkHttp, DataStore

---

## File Structure

| File | Role |
|---|---|
| `AcademicSessionStore.kt` | **Modify** — add campus URL persistence |
| `ScoreViewModel.kt` | **Modify** — single GET, campus URL, chip filter state |
| `ScoreScreen.kt` | **Rewrite** — LazyRow chips, semester block cards |
| `DirectLoginViewModel.kt` | **Modify** — save campus URL on successful login |

**Reused (no changes):**
- `ScoreParser.kt` — already handles year=null/term=null, dual-campus
- `ScoreModels.kt` / `ScheduleEntities.kt` — models complete
- `ScheduleDao.kt` / `ScheduleRepository.kt` — DAO complete
- `MainActivity.kt` — navigation unchanged

---

### Task 1: Add campus URL storage to AcademicSessionStore

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/service/academic/AcademicSessionStore.kt`

- [ ] **Step 1: Add campus URL key and Flow**

Add a new key and Flow for the campus base URL so ScoreViewModel knows which campus server to query.

Add after `examApiUrlKey` (line 17):
```kotlin
private val campusUrlKey = stringPreferencesKey("campus_base_url")
```

Add after `examApiUrl` Flow (line 29):
```kotlin
val campusBaseUrl: Flow<String> = context.academicSessionDataStore.data.map { preferences ->
    preferences[campusUrlKey].orEmpty()
}
```

Add after `saveExamApiUrl` function (line 47):
```kotlin
suspend fun saveCampusBaseUrl(url: String) {
    context.academicSessionDataStore.edit { preferences ->
        preferences[campusUrlKey] = url
    }
}
```

- [ ] **Step 2: Build and verify compilation**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/glut/schedule/service/academic/AcademicSessionStore.kt
git commit -m "feat: add campus base URL persistence to AcademicSessionStore"
```

---

### Task 2: Save campus URL in DirectLoginViewModel on login

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt`

- [ ] **Step 1: Add campus URL save after successful login**

In `importTimetable()`, after the campus base URL is determined, persist it to DataStore so ScoreViewModel picks up the right campus later.

Find where `campusBaseUrl` is used in `importTimetable()` — the method already receives it as a parameter or derives it. Add this line right after campusBaseUrl is known and before `fetchAndSaveScores()`:

```kotlin
sessionStore.saveCampusBaseUrl(campusBaseUrl)
```

- [ ] **Step 2: Build and verify compilation**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt
git commit -m "feat: persist campus URL on login for ScoreViewModel"
```

---

### Task 3: Rewrite ScoreViewModel with single GET + chip filter state

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/ScoreViewModel.kt`

- [ ] **Step 1: Rewrite ScoreViewModel**

Replace the entire file. Key changes from current:
- `_selectedYear` MutableStateFlow for chip selection (null = show all)
- `availableYears` StateFlow derived from scores (dynamic year list)
- `selectYear()` method for chip click
- `fetchAllScores()`: single GET instead of 8×POST loop, uses `sessionStore.campusBaseUrl`
- `isNanning` detection via `campusBaseUrl == AcademicLoginResult.NANNING_URL`
- Timeout increased to 15s (single larger response)

```kotlin
package com.glut.schedule.ui.pages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.glut.schedule.data.model.ScoreInfo
import com.glut.schedule.data.repository.ScheduleRepository
import com.glut.schedule.service.academic.AcademicLoginResult
import com.glut.schedule.service.academic.AcademicLoginService
import com.glut.schedule.service.academic.AcademicSessionStore
import com.glut.schedule.service.parser.ScoreParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class ScoreUiState(
    val scores: List<ScoreInfo> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String = "",
    val hasCookie: Boolean = false
)

class ScoreViewModel(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val scoreParser: ScoreParser = ScoreParser()
) : ViewModel() {

    private val _isRefreshing = MutableStateFlow(false)
    private val _message = MutableStateFlow("")
    private val _selectedYear = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ScoreUiState> = combine(
        repository.scores,
        sessionStore.academicCookie,
        _isRefreshing,
        _message
    ) { scores, cookie, isRefreshing, message ->
        ScoreUiState(
            scores = scores,
            isRefreshing = isRefreshing,
            message = message,
            hasCookie = cookie.isNotBlank()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScoreUiState()
    )

    val availableYears: StateFlow<List<String>> = repository.scores
        .map { scores -> scores.map { it.year }.distinct().sortedDescending() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val selectedYear: StateFlow<String?> = _selectedYear

    fun selectYear(year: String?) {
        _selectedYear.value = year
    }

    fun refreshScores() {
        viewModelScope.launch {
            _isRefreshing.value = true
            _message.value = "正在获取成绩..."
            try {
                var cookie = sessionStore.academicCookie.first()
                if (cookie.isBlank()) {
                    val loginResult = loginService.silentLogin()
                    if (loginResult is AcademicLoginResult.Success) {
                        cookie = sessionStore.academicCookie.first()
                    } else {
                        _message.value = "请先在导入课表页面登录教务系统"
                        return@launch
                    }
                }

                val allScores = fetchAllScores(cookie)
                if (allScores.isNotEmpty()) {
                    repository.replaceScores(allScores)
                    _message.value = "已获取 ${allScores.size} 条成绩记录"
                } else {
                    _message.value = "未获取到成绩数据"
                }
            } catch (e: Exception) {
                _message.value = "获取失败: ${e.message}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun fetchAllScores(cookie: String): List<ScoreInfo> {
        val campusBaseUrl = sessionStore.campusBaseUrl.first()
            .ifBlank { AcademicLoginResult.DEFAULT_GUILIN_URL }

        val scoreClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val request = Request.Builder()
            .url("$campusBaseUrl/academic/manager/score/studentOwnScore.do?year=&term=&para=0")
            .header("Cookie", cookie)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .get()
            .build()

        val body = withContext(Dispatchers.IO) {
            scoreClient.newCall(request).execute().use { response ->
                response.body?.string().orEmpty()
            }
        }

        val isNanning = campusBaseUrl == AcademicLoginResult.NANNING_URL
        return scoreParser.parseScoreHtml(body, isNanning = isNanning)
    }

    fun clearMessage() { _message.value = "" }
}

class ScoreViewModelFactory(
    private val repository: ScheduleRepository,
    private val sessionStore: AcademicSessionStore,
    private val loginService: AcademicLoginService,
    private val scoreParser: ScoreParser = ScoreParser()
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ScoreViewModel(repository, sessionStore, loginService, scoreParser) as T
    }
}
```

- [ ] **Step 2: Build and verify compilation**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/glut/schedule/ui/pages/ScoreViewModel.kt
git commit -m "refactor: replace 8-POST loop with single GET in ScoreViewModel"
```

---

### Task 4: Rewrite ScoreScreen with chips and semester block cards

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/ScoreScreen.kt`

- [ ] **Step 1: Rewrite ScoreScreen**

Full rewrite with:
- `LazyRow` filter chips (全部学期 + one per year, dynamic from `availableYears`)
- Semester block cards (dark header with year/term/GPA/credits, white body with score rows)
- Chip click → filter scores by year (via `viewModel.selectYear()`)
- Score + GPA columns both use the GPA-based color
- Bottom summary card (overall GPA + total credits)
- Empty states for no-data and no-match

```kotlin
package com.glut.schedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glut.schedule.data.model.ScoreInfo

private val ScorePrimary = Color(0xFF141821)
private val ScoreSecondary = Color(0xFF667085)
private val ScoreAccent = Color(0xFF3F7DF6)
private val ScorePageBg = Color(0xFFF6F4EF)
private val ScoreCardBg = Color(0xFFFFFEFB)
private val ScoreChipBg = Color(0xFFE8E4D6)
private val ScoreSemesterHeaderBg = Color(0xFF141821)

@Composable
fun ScoreScreen(
    viewModel: ScoreViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedYear by viewModel.selectedYear.collectAsState()
    val availableYears by viewModel.availableYears.collectAsState()

    val allScores = uiState.scores
    val filteredScores = if (selectedYear == null) allScores
    else allScores.filter { it.year == selectedYear }

    val grouped = filteredScores
        .groupBy { Pair(it.year, it.term) }
        .entries
        .sortedByDescending { "${it.key.first}-${it.key.second}" }

    val listState = rememberLazyListState()

    LaunchedEffect(selectedYear) {
        if (grouped.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ScorePageBg)
    ) {
        if (uiState.isRefreshing) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = ScoreAccent,
                trackColor = ScoreAccent.copy(alpha = 0.12f)
            )
        }

        if (uiState.message.isNotBlank()) {
            Text(
                uiState.message,
                color = ScoreSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)
            )
        }

        // Filter chips
        if (availableYears.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp)
            ) {
                item {
                    FilterChip(
                        label = "全部学期",
                        isSelected = selectedYear == null,
                        onClick = { viewModel.selectYear(null) }
                    )
                }
                items(availableYears) { year ->
                    FilterChip(
                        label = year,
                        isSelected = selectedYear == year,
                        onClick = { viewModel.selectYear(year) }
                    )
                }
            }
        }

        // Empty states
        if (allScores.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (uiState.hasCookie) "暂无成绩数据\n请点击刷新按钮获取"
                    else "请先在导入课表页面登录教务系统",
                    color = ScoreSecondary,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else if (filteredScores.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text("该学年暂无成绩", color = ScoreSecondary, fontSize = 14.sp)
            }
        } else {
            // Semester blocks
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                grouped.forEach { (yearTerm, termScores) ->
                    val (year, term) = yearTerm
                    val avgGpa = termScores.map { it.gpa }.filter { it > 0 }
                        .average().let { if (it.isNaN()) 0.0 else it }
                    val totalCredit = termScores.sumOf { it.credit }

                    item(key = "semester_${year}_$term") {
                        SemesterBlock(
                            year = year,
                            term = term,
                            avgGpa = avgGpa,
                            totalCredit = totalCredit,
                            scores = termScores
                        )
                    }
                }

                // Bottom summary
                item {
                    val overallGpa = allScores.map { it.gpa }.filter { it > 0 }
                        .average().let { if (it.isNaN()) 0.0 else it }
                    val overallCredit = allScores.sumOf { it.credit }
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        color = ScoreSemesterHeaderBg,
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            "全部课程平均绩点 ${String.format("%.2f", overallGpa)} · 总学分 ${String.format("%.1f", overallCredit)}",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(14.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        color = if (isSelected) ScoreSemesterHeaderBg else ScoreChipBg,
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            label,
            color = if (isSelected) Color.White else ScorePrimary,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun SemesterBlock(
    year: String,
    term: Int,
    avgGpa: Double,
    totalCredit: Double,
    scores: List<ScoreInfo>
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = ScoreCardBg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Column {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ScoreSemesterHeaderBg)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "$year 第${term}学期",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        "绩点 ${String.format("%.2f", avgGpa)}",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                    Text(
                        "学分 ${String.format("%.1f", totalCredit)}",
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 12.sp
                    )
                }
            }

            // Column headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
            ) {
                Text("课程名称", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(2.5f))
                Text("成绩", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Text("绩点", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(0.8f), textAlign = TextAlign.Center)
                Text("学分", color = ScoreSecondary, fontSize = 11.sp, modifier = Modifier.weight(0.7f), textAlign = TextAlign.Center)
            }

            // Score rows
            scores.forEachIndexed { index, score ->
                ScoreRow(
                    score = score,
                    showDivider = index < scores.lastIndex
                )
            }
        }
    }
}

@Composable
private fun ScoreRow(score: ScoreInfo, showDivider: Boolean) {
    val gpaColor = when {
        score.gpa >= 3.7 -> Color(0xFF2D9A72)
        score.gpa >= 3.0 -> Color(0xFF3F7DF6)
        score.gpa >= 2.0 -> Color(0xFFD97706)
        score.gpa > 0 -> Color(0xFFDC2626)
        else -> ScoreSecondary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(2.5f)) {
            Text(
                score.courseName,
                color = ScorePrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            if (score.category.isNotBlank()) {
                Text(score.category, color = ScoreSecondary, fontSize = 11.sp)
            }
        }
        Text(
            score.score,
            color = gpaColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        Text(
            if (score.gpa > 0) String.format("%.1f", score.gpa) else "-",
            color = gpaColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(0.8f),
            textAlign = TextAlign.Center
        )
        Text(
            if (score.credit > 0) String.format("%.1f", score.credit) else "-",
            color = ScoreSecondary,
            fontSize = 12.sp,
            modifier = Modifier.weight(0.7f),
            textAlign = TextAlign.Center
        )
    }

    if (showDivider) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 8.dp),
            color = ScorePageBg,
            thickness = 0.5.dp
        )
    }
}
```

- [ ] **Step 2: Build and verify compilation**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleDebug
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/glut/schedule/ui/pages/ScoreScreen.kt
git commit -m "feat: add semester filter chips and block card UI to ScoreScreen"
```

---

### Task 5: Handle import flow score refresh + end-to-end build

**Files:**
- Modify: `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt`

- [ ] **Step 1: Ensure campus URL is saved during import flow**

In `importTimetable()`, the campus base URL is already determined. Add a line to persist it to DataStore before the score/import operations run. Find the `importTimetable` method and locate where `campusBaseUrl` is first used. Add:

```kotlin
sessionStore.saveCampusBaseUrl(campusBaseUrl)
```

This ensures ScoreViewModel can later query the correct campus without the user needing to re-import.

- [ ] **Step 2: Clean build and verify full assembly**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat clean assembleDebug
```

- [ ] **Step 3: Build release APK**

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
.\gradlew.bat assembleRelease
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt
git commit -m "feat: persist campus URL in import flow for ScoreViewModel"
```

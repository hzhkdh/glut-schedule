# 南宁分校课表教室信息错误 & 课程卡片缺失问题总结

**版本**: v0.14.4 修复  
**日期**: 2026-06-11  
**影响范围**: 南宁分校（jw.glutnn.cn）课表导入  

---

## 问题现象

1. **教室信息不准确**：同一门课在不同周次/天次有不同上课地点，但 App 中全部显示为第一个教室名。例如：
   - 安全生产管理：周二 6304D、周三 8208D/6301D/6502D → App 全部显示 6304D
   - 基础工程实训：周一/四 6310D、周二 6302D、周三 6304D → App 全部显示 6310D

2. **部分课程卡片不显示**：如第14周周一"思想政治理论课实践教学（四）"等课程在课表中缺失。

---

## 根因分析（共4层问题）

### 第1层：节次映射偏移

**根因**：`mapDisplaySection()` 函数最初仅针对桂林本部设计。桂林教务系统在"第4节"和"第5节"之间夹有"中午1/2"时段，因此节次号需要 +2 偏移：

```
桂林: 第1-4节→section 1-4, 中午1/2→section 5-6, 第5-12节→section 7-14
南宁: 第1-11节→section 1-11（直排，无中午时段）
```

南宁没有中午时段，但解析器仍施加了相同的 +2 偏移，导致第5节被映射到 section 7，与 `nanningClassPeriods()` 的节次定义不匹配。

同时 `ScheduleGrid.kt` 中的 `NOON_SECTIONS = {5, 6}` 被用于南宁校区时，会将正常的第5-6节（下午 14:30-15:55）错误标记为午休时段而隐藏，导致部分课程卡片不显示。

**涉及文件**：
- `AcademicScheduleParser.kt` — `mapDisplaySection()` 
- `ScheduleGrid.kt` — `PeriodColumn`, `TimetableBody`, `visibleBlocks`

**修复**：
- `mapDisplaySection` 增加 `hasNoon` 参数，通过 `html.contains("中午")` 自动检测校区
- `ScheduleGrid` 通过 `periods.size <= 11` 自动识别南宁（11节）vs 桂林（14节）
- 南宁校区节次标签直排（"1"-"11"），不使用 `periodLabel()` 的午1/午2映射
- `effectiveShowNoon` 对南宁永为 `true`，不禁用 NOON_SECTIONS 过滤

---

### 第2层：periods 缓存污染

**根因**：`classPeriods` 的 Flow 直接返回 DB 缓存值，忽略 `campusType`：

```kotlin
// 旧代码 — 永远返回 DB 缓存，忽略 campusType
val classPeriods = combine(dao.observeClassPeriods(), campusType) { periods, _ ->
    periods.map { it.toModel() }.ifEmpty { ... }
}
```

`ClassPeriodEntity` 主键为 `section`，`insertClassPeriods` 使用 `OnConflictStrategy.REPLACE`。切换校区时（桂林 14 节 → 南宁 11 节），sections 12-14 成为孤儿行残留在 DB 中。`observeClassPeriods()` 返回 14 行 → Grid 误判为桂林 → 应用错误的节次标签和偏移。

**修复**：
- `classPeriods` Flow 改为以 `campusType` 为准，始终返回对应校区的正确 periods
- 新增 `replaceClassPeriods()` 事务方法（先 `DELETE` 后 `INSERT`），彻底清理孤儿行
- `seedIfEmpty()` 和 `replaceImportedCourses()` 改用 `replaceClassPeriods()`

**涉及文件**：
- `ScheduleDao.kt` — 新增 `deleteClassPeriods()`, `replaceClassPeriods()`
- `ScheduleRepository.kt` — `classPeriods` Flow 重构

---

### 第3层：currcourse.jsdo 才是南宁的主数据源

**关键发现**：之前的分析一直围绕 `showTimetable.do`（与桂林同格式的网格表格），但通过浏览器逐一验证实际 HTTP 响应后发现：

| Endpoint | 响应 | 说明 |
|----------|------|------|
| `showTimetable.do?id=5241994207` | 错误页 | "学生只能查看本人课表！"（username 非内部 ID） |
| `framePage.do` | 404 | 南宁教务不支持此接口，无法提取内部 studentId |
| `currcourse.jsdo` | **200 OK** | 南宁真正的主课表数据源 |

App 导入流程中 `currcourse.jsdo` 优先级高于 `showTimetable.do`，因此南宁解析实际走的是 `NanningCurrcourseParser`（而非桂林的 `GlutAcademicScheduleParser`）。

---

### 第4层：NanningCurrcourseParser 缺少按教室拆分（最终根因）

**根因**：`NanningCurrcourseParser.mergeCompatibleCourses()` 逻辑有缺陷：

```kotlin
// 旧代码 — 缺少 splitCourseByOccurrenceRoom 步骤
private fun mergeCompatibleCourses(courses: List<ScheduleCourse>): List<ScheduleCourse> {
    return courses
        .groupBy { "${it.title}|${it.teacher}|${it.room}" }  // room 固定为 firstRoom!
        .map { (_, group) ->
            val first = group.first()
            first.copy(occurrences = group.flatMap { it.occurrences }.distinctBy { ... })
        }
}
```

问题链条：
1. 建课时 `ScheduleCourse.room` 设为 `rawSlots.first().room`（第一个教室）
2. 同一课程在不同时间有不同教室时，per-occurrence 的 `note` 虽是正确教室，但**课程级别的 `room` 字段只取第一个**
3. `mergeCompatibleCourses` 按 `title|teacher|room` 分组，同课异室被错误合并
4. UI 显示 `@${course.room}` → 全部展示第一个教室名

**修复**：新增 `splitCourseByOccurrenceRoom()` 方法（与桂林 `GlutAcademicScheduleParser` 一致）：

```kotlin
private fun splitCourseByOccurrenceRoom(course: ScheduleCourse): List<ScheduleCourse> {
    return course.occurrences
        .groupBy { occurrence -> occurrence.note.trim().ifBlank { course.room.trim() } }
        .map { (room, occurrences) ->
            val id = "import-nn-${stableId("room-bound-${course.title}-${course.teacher}-$room")}"
            course.copy(
                id = id,
                room = room,
                occurrences = occurrences.mapIndexed { index, occurrence ->
                    occurrence.copy(id = "$id-occurrence-$index", courseId = id, note = room)
                }
            )
        }
}
```

`mergeCompatibleCourses` 改为：先 `flatMap { splitCourseByOccurrenceRoom(it) }` 拆分 → 再 `groupBy { title|teacher|room }` 归并。

**涉及文件**：
- `NanningCurrcourseParser.kt` — 新增 `splitCourseByOccurrenceRoom()`，重构 `mergeCompatibleCourses()`

---

## 技术要点

| 要点 | 说明 |
|------|------|
| 桂林 vs 南宁数据源 | 桂林用 `showTimetable.do`（`<table id="timetable">` 网格），南宁用 `currcourse.jsdo`（`<table class="none">` 嵌套） |
| 节次体系差异 | 桂林 14 节（含中午1/2），南宁 11 节（直排，无午休） |
| 教室拆分必要性 | `currcourse.jsdo` 一门课对应多行时间槽，每行独立教室，必须按 `occurrence.note` 拆分 |
| 内部 ID 获取 | 南宁 `framePage.do` 返回 404，无法提取内部 studentId；`showTimetable.do` 需要 studentId 而非 login username |

---

## 测试覆盖

新增 6 个专项测试，全部通过：

| 测试方法 | 所属类 | 验证内容 |
|----------|--------|----------|
| `nanningTimetableMapsSectionsDirectlyWithoutNoonOffset` | AcademicScheduleParserTest | 南宁第5节直排为 section 5 |
| `nanningTimetableSplitsSameTitleDifferentRoomsIntoDistinctCourses` | AcademicScheduleParserTest | showTimetable 网格格式同框异室拆分 |
| `nanningTimetableParsesAllCoursesInMultiCourseCell` | AcademicScheduleParserTest | 4门课同框全解析（含思想政治理论课） |
| `nanningCrossDaySameTitleDifferentRoomsStaySeparate` | AcademicScheduleParserTest | 跨天同名异室不混淆（安全生产管理） |
| `splitsSameCourseDifferentRoomsIntoDistinctCourses` | NanningCurrcourseParserTest | currcourse.jsdo 多教室拆分 |
| `splitsTrainingCourseDifferentRoomsPerDay` | NanningCurrcourseParserTest | 基础工程实训按天拆分教室（3→3门） |

---

## 经验教训

1. **不能假设数据源格式**：南宁和桂林使用不同的教务 endpoint（`currcourse.jsdo` vs `showTimetable.do`），HTML 结构完全不同，各自对应不同的 Parser 实现。
2. **缓存污染隐蔽性强**：DB 孤儿行 + Flow 直接返回缓存值 = 正确的 `campusType` 被无视，UI 表现异常但行为难以从代码审查中定位。
3. **浏览器验证不可替代**：最终通过浏览器逐一验证每个 endpoint 的实际 HTTP 响应（`framePage.do`→404, `currcourse.jsdo`→200, `showTimetable.do?id=username`→错误页），才发现了 `currcourse.jsdo` 是南宁主数据源这一关键事实。
4. **两个 Parser 必须保持逻辑一致**：`NanningCurrcourseParser` 和 `GlutAcademicScheduleParser` 的 `mergeCompatibleCourses` 存在实现差异（一个有 `splitCourseByOccurrenceRoom`，一个没有），这种不一致是 bug 的温床。
5. **测试驱动修 Bug**：每个根因修复都对应新增单元测试，使用从浏览器抓取的真实 HTML 数据作为输入，确保修复精准且不回退。

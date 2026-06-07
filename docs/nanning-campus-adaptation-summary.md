# 南宁分校课表适配总结

**日期：** 2026-06-08
**版本：** v0.7.11
**参考项目：** [GlutAssistantN](https://github.com/nano71/GlutAssistantN)

---

## 一、背景

桂林理工大学有桂林本校（`jw.glut.edu.cn`）和南宁分校（`jw.glutnn.cn`）两个校区。本项目最初只支持桂林本校的课表导入。用户反馈南宁分校能登录成功、能查看考试安排，但课表导入显示"0 个课程"，且课表界面仍显示桂林的 12 节时段模板。

## 二、两校教务系统差异

| 维度 | 桂林本校 | 南宁分校 |
|------|----------|----------|
| 教务网址 | `jw.glut.edu.cn` | `jw.glutnn.cn` |
| 教务平台 | 正方教务 | 优慕课 (U-MOOC) |
| 学号位数 | 13 位 | 10 位 |
| 登录验证码 | 可选（可为空） | 必须输入，服务端校验 |
| 密码加密 | 明文传输 | MD5(MD5(password)) 双重哈希 |
| 课表端点 | `showTimetable.do` | `currcourse.jsdo` |
| 课表 HTML 格式 | `<table id="timetable">` + `<<课程名>>` | `.infolist_common` 行 + 嵌套 `table.none` |
| HTML 编码 | UTF-8 | GBK |
| `framePage.do` | 正常返回用户信息 | 404（重定向链用相对路径 `./index_frame.jsp`） |
| 上课节次 | 12 节（08:30 开始） | 11 节（08:40 开始） |
| 上午时段 | 08:30–12:00 | 08:40–11:50 |
| 晚上开始 | 18:30 | 19:30 |

## 三、问题诊断过程

### 3.1 第一阶段：用 Python 脚本抓取真实 HTML

参考 GlutAssistantN 的实现，编写了 `nanning_get_captcha.py` + `nanning_use_captcha.py` 两个阶段脚本：
1. 获取登录页面 → 下载验证码图片
2. 用户输入验证码 → 登录 → 遍历课表端点

关键发现：
- `showTimetable.do?id=学号`（无 `yearid`/`termid`）→ 返回 **"学年传递错误"**
- `currcourse.jsdo` → 返回 59168 字节的有效 HTML，包含 `.infolist_common` 表格

### 3.2 第二阶段：定位解析器失效原因

通过逐行追踪 `GlutAcademicScheduleParser` 的解析流程，发现三层问题：

**层次 1 — URL 构造错误**
- 南宁回退 URL 缺少 `yearid`/`termid` 参数
- 正确的 URL 需要从 `currcourse.jsdo` 页面提取（如 `showTimetable.do?id=237607&yearid=46&termid=1`）

**层次 2 — 正则嵌套陷阱**
- `GlutAcademicScheduleParser` 使用 `rowRegex` + `tableCellRegex` 逐行解析
- 非贪婪 `.*?` 在 `<tr class="infolist_common">...</tr>` 中遇到嵌套 `<table class="none"><tr>...</tr></table>` 内的 `</tr>` 时提前终止
- 导致单元格边界错乱、时间数据无法匹配

**层次 3 — 解析器优先级错误**
- `CompositeScheduleParser` 先跑桂林 parser，它对南宁 HTML 的 `parseTextBased` 兜底策略也能返回一些结果（质量极差）
- 南宁 parser 从未被调用

### 3.3 第三阶段："100 课程" 伪解析

v0.7.10 上线后出现新问题：
- 显示导入 100 门课程（实际应约 14 门）
- 课表仍显示 12 节时段
- 课程卡片不显示

根因：
1. `courseNameRegex` 同时匹配了课程名和教师名（两者都使用 `class="infolist"` 的 `<a>` 标签）
2. `replaceImportedCourses` 硬编码写入 `defaultClassPeriods()`（12 节）
3. 桂林 parser 返回的混乱数据包含错误的 section 号

## 四、解决方案

### 4.1 新建 `NanningCurrcourseParser`

采用**预处理嵌套表格**的策略，避免正则嵌套陷阱：

```
1. 提取所有 <table class="none">...</table> → 替换为 <!--NESTED_N--> 占位符
2. 在干净的 HTML 中解析 infolist_common 行 → 提取课程名、教师
3. 匹配占位符 → 查找对应嵌套表格 → 解析周次/星期/节次/地点
4. 构建 ScheduleCourse + CourseOccurrence
```

关键正则修复：
```kotlin
// 排除教师链接（含 teacherinfo 的 href），必须在标签边界内检查
val courseNameRegex = Regex(
    """(?is)<a\b(?!\s*[^>]*teacherinfo)[^>]*class\s*=\s*["']infolist["'][^>]*>\s*([^<]+?)\s*</a>"""
)
```

### 4.2 三级 URL 优先级

```kotlin
// Priority 1: currcourse.jsdo（南宁主端点，已验证 59168 字节有效数据）
// Priority 2: showTimetable.do?id=内部ID&yearid=提取值&termid=提取值
// Priority 3: showTimetable.do?id=学号&yearid=46&termid=1（兜底）
```

### 4.3 校区感知时段

```kotlin
enum class CampusType { GUILIN, NANNING }

// DataStore 持久化校区类型
val campusType: Flow<CampusType>

// 登录成功时自动设置
settingsStore.setCampusType(campusType)

// Repository 根据校区返回对应时段
val classPeriods = combine(dao.observeClassPeriods(), campusType) { ... }
```

### 4.4 CompositeScheduleParser

```kotlin
// 南宁 parser 优先（它对非南宁 HTML 返回 empty，不影响桂林）
CompositeScheduleParser(listOf(
    NanningCurrcourseParser(),  // 先尝试
    GlutAcademicScheduleParser() // 兜底
))
```

## 五、两校时段对照

| 节次 | 桂林 | 南宁 |
|------|------|------|
| 1 | 08:30–09:15 | 08:40–09:20 |
| 2 | 09:20–10:05 | 09:25–10:05 |
| 3 | 10:25–11:10 | 10:25–11:05 |
| 4 | 11:15–12:00 | 11:10–11:50 |
| 5 | 14:30–15:15 | 14:30–15:10 |
| 6 | 15:20–16:05 | 15:15–15:55 |
| 7 | 16:25–17:10 | 16:05–16:45 |
| 8 | 17:15–18:00 | 16:50–17:30 |
| 9 | 18:30–19:15 | 19:30–20:10 |
| 10 | 19:20–20:05 | 20:15–20:55 |
| 11 | 20:10–20:55 | 21:00–21:40 |
| 12 | 21:00–21:45 | — |

## 六、新建/修改文件清单

### 新建文件

| 文件 | 职责 |
|------|------|
| `service/parser/NanningCurrcourseParser.kt` | 解析南宁 `currcourse.jsdo` HTML（预处理嵌套表格策略） |
| `service/parser/CompositeScheduleParser.kt` | 组合多个 parser，首个非空结果胜出 |
| `NanningCurrcourseParserTest.kt` | 8 个单元测试（空白输入、单/多时段、慕课跳过、空教师、单双周、多课程） |

### 修改文件

| 文件 | 改动 |
|------|------|
| `data/model/ScheduleModels.kt` | 拆分 `guilinClassPeriods()`（12 节）和 `nanningClassPeriods()`（11 节） |
| `data/settings/ScheduleSettingsStore.kt` | 新增 `CampusType` 枚举 + DataStore 持久化 |
| `data/repository/ScheduleRepository.kt` | `classPeriods` 结合校区类型；`replaceImportedCourses` 写校区时段 |
| `ScheduleApplication.kt` | 注入 `CompositeScheduleParser`（南宁优先） |
| `service/academic/ApiProbeService.kt` | 新增 `extractInternalIdFromCurrcourse()` 等参数提取工具 |
| `ui/pages/DirectLoginViewModel.kt` | 三级 URL 优先级；校区类型传播 |
| `service/academic/AcademicImportConfig.kt` | `isAcademicDomainPage` 识别 `glutnn.cn` |

## 七、经验总结

1. **正则解析 HTML 的局限性**：嵌套表格是 regex 的天敌。采用"提取→替换→解析→还原"的预处理策略可以规避这个问题。如果 HTML 结构更复杂，应考虑引入 Jsoup 等 DOM 解析器。

2. **Composite 模式的顺序至关重要**：两个 parser 都能部分匹配对方的格式时，必须先跑特异性更高的 parser（本例中南宁 parser 检查 `infolist_common` 作为门槛）。

3. **参考开源实现是捷径**：GlutAssistantN 项目验证了 `currcourse.jsdo` 是南宁的正确端点，并提供了 HTML 格式参考。但它的解析逻辑（Dart + DOM parser）不能直接移植到 Kotlin + 正则的架构。

4. **DataStore + Flow 是校区切换的优雅方案**：`combine(dao, campusType)` 让时段数据随校区类型自动切换，无需手动管理状态。

5. **诊断优于猜测**：用 Python 脚本抓取真实 HTML 是定位根因的关键一步。没有实际数据，"100 课程"这类 bug 很难从代码推理中复现。

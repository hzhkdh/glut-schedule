# Score Screen Revamp — 设计文档

**日期**：2026-06-08
**状态**：已批准
**方案**：C + C1（一键获取全部 + 按学期分组 + 横向滚动 Chip）

---

## 1. 问题分析

当前 `ScoreScreen` 已有完整的代码框架（ScoreScreen、ScoreViewModel、ScoreParser、ScoreEntity），但存在以下问题：

- **ScoreViewModel 使用 8 次 POST 请求**（4 年 × 2 学期）逐学期拉取，效率低且不可靠
- **DirectLoginViewModel 已证明**：一次 GET 请求 `studentOwnScore.do?year=&term=&para=0` 即可获取全部成绩
- 两套逻辑不一致，需要统一

## 2. 方案

**核心思路**：一键拉取全部成绩，本地持久化，按学期自动分组展示。

### 2.1 数据流

```
ScoreScreen (刷新按钮 / 导入后自动触发)
  → ScoreViewModel.refreshScores()
    → AcademicSessionStore 检查 cookie/session 有效
    → OkHttp GET: studentOwnScore.do?year=&term=&para=0
    → ScoreParser.parseScoreHtml() 解析 HTML
    → Repository.replaceScores() 存入 Room
    → Room Flow 自动推送到 UI
    → ScoreScreen 按 year+term 分组展示
```

### 2.2 API 统一

| 方面 | 当前 ScoreViewModel | DirectLoginViewModel | 修复后 |
|---|---|---|---|
| 请求方式 | POST × 8 次 | GET × 1 次 | GET × 1 次 |
| URL 参数 | `year=2022&term=1` | `year=&term=` | `year=&term=` |
| 校区支持 | 仅桂林 | 桂林 + 南宁 | 桂林 + 南宁（复用 ScoreParser） |

### 2.3 UI 布局

```
┌─────────────────────────────┐
│  ☰ 考试成绩            🔄刷新 │  TopAppBar
├─────────────────────────────┤
│ [全部学期][2025-2026][...]→  │  LazyRow 横向滚动 Chip
├─────────────────────────────┤
│ ┌─────────────────────────┐ │
│ │ 📅 2025-2026 春  GPA 3.56│ │  学期区块头部（深色背景）
│ │ 高等数学（二）   91  3.9 │ │  课程名 | 分数(颜色) | GPA(颜色)
│ │ 大学英语（四）   85  3.3 │ │
│ │ 程序设计基础    72  2.3 │ │
│ └─────────────────────────┘ │
│ ┌─────────────────────────┐ │
│ │ 📅 2025-2026 秋  GPA 3.28│ │
│ │ ...                     │ │
│ └─────────────────────────┘ │
│ ┌─────────────────────────┐ │
│ │ 📊 全部 GPA 3.42·46.5学分│ │  底部汇总卡片
│ └─────────────────────────┘ │
└─────────────────────────────┘
```

### 2.4 Chip 交互

- **LazyRow** 横向滚动 Chip，Material 3 `FilterChip` 或自定义
- 选项动态生成：从已加载的成绩数据中提取存在的年份列表
- "全部学期" 始终在第一位且默认选中
- 点击 Chip → LazyColumn 平滑滚动到对应学期区块（`animateScrollToItem`）

### 2.5 颜色编码（已有，无需改动）

| GPA 范围 | 颜色 | 含义 |
|---|---|---|
| ≥ 3.7 | 绿色 `#2D9A72` | 优秀 |
| ≥ 3.0 | 蓝色 `#3F7DF6` | 良好 |
| ≥ 2.0 | 橙色 `#D97706` | 中等 |
| > 0 | 红色 `#DC2626` | 不及格 |

改进：分数列也使用相同的颜色（当前仅绩点列使用颜色）。

### 2.6 状态处理

- **加载中**：TopAppBar 下方线性进度条
- **空状态（无数据）**：图标 + "暂无成绩数据" + 引导文字
- **错误**：Snackbar 提示，保留旧数据
- **未登录**：提示先登录导入

## 3. 改动范围

### 需修改的文件

| 文件 | 改动 |
|---|---|
| `ScoreViewModel.kt` | 替换 8 次 POST 为 1 次 GET，添加 session 检查，添加 chip 过滤状态 |
| `ScoreScreen.kt` | 添加 LazyRow Chip，学期区块 UI，分数列颜色，滚动联动 |
| `DirectLoginViewModel.kt` | 导入完成后触发 ScoreViewModel 刷新（已有 fetchAndSaveScores，确保调用） |

### 无需修改的文件

| 文件 | 原因 |
|---|---|
| `ScoreParser.kt` | 已支持 year=null/term=null 的全量解析，桂林+南宁双校区 |
| `ScoreModels.kt` / `ScheduleEntities.kt` | 数据模型完整 |
| `ScheduleDao.kt` / `ScheduleRepository.kt` | DAO 方法完整 |
| `MainActivity.kt` | Drawer 导航已连接 |

## 4. 测试要点

- [ ] GET 请求全量成绩解析（桂林校区 HTML）
- [ ] GET 请求全量成绩解析（南宁校区 HTML）
- [ ] Chip 过滤：点击年份 Chip → 仅显示对应学期的成绩区块
- [ ] "全部学期" Chip → 显示所有学期
- [ ] 空数据状态显示
- [ ] 错误状态不覆盖旧数据
- [ ] 分数颜色与绩点颜色一致

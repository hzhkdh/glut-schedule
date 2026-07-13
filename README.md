# 桂系一站式

面向桂林理工大学学生的本地 Android 校园信息工具。支持直接输入学号密码导入教务数据，并可独立登录体测平台查询最新成绩、历年成绩和评分标准。账号密码使用系统加密存储，业务数据保存在本机。

## 功能

### 课表
- 启动即进入课表页面，无需登录注册
- 顶部显示当前周数、星期和日期，左右滑动或点击箭头切换周次
- 左侧节次列显示节次与上下课时间（桂林/南宁两套作息）
- 彩色课程卡片展示课程名、教室、教师，选课属性徽章（必修蓝/限选橙/任选绿）
- 同一时段多门冲突课程自动分组，点击轮换显示
- 调课自动处理：原课消失，补课出现在对应的周次/节次
- 支持自定义背景图片和课程卡片颜色

### 数据导入
- 直接输入学号密码登录教务系统，自动检测桂林/南宁校区
- 南宁分校支持验证码登录
- 登录后自动拉取：课表、考试安排、考试成绩、等级考试、教学计划、调课信息
- 凭据加密存储（EncryptedSharedPreferences），支持静默登录刷新
- 记住密码：勾选后加密保存，不勾选自动清除已存密码
- 体测平台使用独立登录与验证码流程，凭据和会话同样加密保存在本机

### 菜单功能
| 菜单 | 功能 | 数据来源 |
|------|------|---------|
| 课程表 | 当前学期课表，周次导航 | currcourse.jsdo / showTimetable.do |
| 考试成绩 | 历年课程成绩，学年分组 + GPA 汇总 | studentOwnScore.do (POST) |
| 考试安排 | 考试时间、地点、座位号，时间线 UI，按科目显示图标 | 多端点探测 (JSON/HTML) |
| 等级考级 | 英语四六级、普通话等国家考试 | skilltest.jsdo (moduleId=2090) |
| 体测成绩 | 最新与历年体测明细、总评、评分标准 | 体测管理平台 |
| 教学计划 | 课组学分/门数要求与完成情况 | studentSelfSchedule.jsdo → studentScheduleLineShow.do |
| 学期概览 | 学期日期、进度、节假日、调课一览 | 教务日历 + timor.tech 节假日 API |
| 导入课表 | 学号密码登录，一键导入全部数据 | 多端点并行探测 |
| 通知 | App 内公告、维护提醒、更新提示，支持未读红点 | Cloudflare Pages notices.json |
| 常见问题 | FAQ 分类展开/收起（常见问题/数据解读/隐私安全/关于项目） | — |
| 设置 | 显示周末、自定义背景、课程卡片颜色 | — |
| 关于 | 版本信息、维护者、检测更新、App 内下载安装 | Cloudflare Pages + GitHub Releases API |

### 学期概览
- **学期进度**：圆形进度环 + 已过/剩余天数精确计算
- **节假日**：本学期法定节假日列表，含倒计时，按学期范围过滤
- **暑假/寒假**：学期结束后自动显示倒计时
- **调课一览**：按补课周次分组展示，当前周高亮（浅蓝底 + 蓝色边框 + "本周"标签）

### 成绩页
- 按学年分组（秋季+春季），学年 Chip 快速切换
- 学期区块彩色左边框（秋=橙 春=绿）+ 季节徽章
- 必修课学分加权 GPA 汇总（底部深色卡片）
- 课程选课属性徽章、绩点颜色分段（优秀绿/良好蓝/及格橙/不及格红）

### 体测成绩
- 独立登录体测平台，支持验证码刷新、密码显隐和密码重置地址复制
- 展示最新体测总评及各项目分数、结论和测试成绩
- 历年成绩按学年学期切换，完整展示体重、耐力加分等项目
- 提供男生、女生、BMI、加分四张评分标准表
- 长表支持固定表头与前两列，横纵向滚动互不冲突

### App 内更新
- 启动时优先检测 Cloudflare Pages `update.json`，失败后回退 GitHub Releases / GitHub Pages
- 三步弹窗：更新日志 → [立即更新] → 下载进度 → [立即安装] → 系统安装器
- 支持下载中取消

### App 内通知
- 侧边菜单新增「通知」，未读通知显示红点
- 通知内容来自 Cloudflare Pages 托管的 `notices.json`
- 按 `publishedAt` 从新到旧排序，支持 `info` / `update` / `warning` / `important` 等级
- 支持本地缓存，网络失败时不影响 App 使用

### 数据持久化
- Room 本地数据库，所有数据离线可用
- 切换账号自动清除旧数据
- 各菜单独立刷新按钮，登录后自动刷新

## 技术栈

- Kotlin 2.2.21
- Jetpack Compose（BOM 2026.04.01）+ Material 3
- Room 2.8.4（KSP 编译）
- DataStore Preferences 1.2.1
- OkHttp 5.3.2
- Security-Crypto 1.1.0-alpha06
- kotlinx-coroutines 1.10.2
- Gradle 8.13 + AGP 8.13.0

## 环境要求

- Android Studio 2025.x+
- Android SDK Platform 36，minSdk 26
- JDK 17（Kotlin 2.2.x 不支持 Java 25）
- Android 8.0 及以上真机或模拟器

## 构建

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"

# Debug APK
.\gradlew.bat assembleDebug

# Release APK（需 keystore.properties）
.\gradlew.bat assembleRelease

# 运行所有测试
.\gradlew.bat testDebugUnitTest

# 运行单个测试
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.*Test"

# 清理
.\gradlew.bat clean
```

APK 输出：
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/glutShedule_<version>.apk`

## 架构

```
app/src/main/java/com/glut/schedule/
  MainActivity.kt                   单 Activity，侧边抽屉导航，Compose UI
  ScheduleApplication.kt            AppContainer 手动 DI

  data/
    model/
      ScheduleModels.kt             课表（ScheduleCourse, CourseOccurrence, ClassPeriod 等）
      ScoreModels.kt                成绩（ScoreInfo）
      ExamModels.kt                 考试（ExamInfo）
      GradeExamModels.kt            等级考试（GradeExamInfo）
      StudyPlanModels.kt            教学计划（StudyPlanGroup）
      SemesterOverviewModels.kt     调课（SemesterAdjustment）、节假日（HolidayInfo）
      NoticeModels.kt               App 内通知（NoticeInfo）
      CourseColorMapper.kt          课程颜色分配
      FitnessModels.kt              体测成绩与评分标准模型
    local/
      ScheduleEntities.kt           Room 实体
      ScheduleDao.kt                Room DAO
      ScheduleDatabase.kt           Room 数据库
    repository/
      ScheduleRepository.kt         统一数据仓库，组合各 DAO Flow
    settings/
      ScheduleSettingsStore.kt      DataStore（周数、周末、背景、校区、学期日期、节假日缓存）

  service/
    AppUpdater.kt                    App 内更新：OkHttp 流式下载 APK + FileProvider 安装
    UpdateChecker.kt                 版本更新检测（Cloudflare Pages + GitHub API + GitHub Pages）
    NoticeChecker.kt                 App 内通知拉取与 JSON 解析
    fitness/
      FitnessApiService.kt           体测平台登录与数据请求
      FitnessStore.kt                体测凭据、会话和页面缓存
    academic/
      AcademicSessionStore.kt       Cookie / campus URL 持久化
      AcademicExamService.kt        考试数据获取 + 多端点探针
      AcademicLoginService.kt       静默登录（凭据 → Cookie）
      AcademicImportConfig.kt       教务 URL 构建
      ApiProbeService.kt            多端点并行探测引擎
      CredentialStore.kt            加密凭据存储
      NanningPasswordHash.kt        南宁密码哈希
    parser/
      AcademicScheduleParser.kt     GLUT 课表 HTML 解析器
      NanningCurrcourseParser.kt    南宁课表 HTML 解析器
      CompositeScheduleParser.kt    组合解析器（南宁优先 → GLUT 兜底）
      ExamParser.kt                 考试 JSON/HTML 双模式解析
      ScoreParser.kt                成绩 HTML 解析（桂林/南宁列映射）
      GradeExamParser.kt            等级考试 HTML 解析
      StudyPlanParser.kt            教学计划 HTML 解析
      FitnessParser.kt              体测成绩、历史明细与评分标准解析

  ui/
    components/
      ScheduleBackground.kt         默认图片背景 + 自定义图片（LRU 缓存）
      ScheduleHeader.kt             周次、日期、操作按钮
      ScheduleGrid.kt               课表网格
    navigation/
      DrawerItem.kt                 侧边抽屉菜单项枚举
    pages/
      ScheduleScreen.kt / ScheduleViewModel.kt
      ScoreScreen.kt / ScoreViewModel.kt
      ExamScreen.kt / ExamViewModel.kt
      ExamCourseVisualMapper.kt      考试课程图标与强调色映射
      GradeExamScreen.kt / GradeExamViewModel.kt
      StudyPlanScreen.kt / StudyPlanViewModel.kt
      SemesterOverviewScreen.kt / SemesterOverviewViewModel.kt
      DirectLoginScreen.kt / DirectLoginViewModel.kt
      NoticeScreen.kt
      FitnessScoreScreen.kt / FitnessScoreViewModel.kt
      FaqScreen.kt
      AboutScreen.kt
    theme/
      Theme.kt                      Material 3 主题
```

## 数据流

- `ScheduleSettingsStore`（DataStore）→ 学期日期/周数 → ViewModel Flow combine → StateFlow<UiState>
- `ScheduleRepository`（Room DAO Flow）→ 各 DAO Flow → ViewModel `combine()` → 响应式 UI
- 导入时 `ApiProbeService.probeAllEndpoints()` 并行探测多个教务端点 → 挑选最佳结果 → 解析保存
- 更新时 `UpdateChecker` 查询 Cloudflare Pages / GitHub API → `AppUpdater` 下载 APK → FileProvider 触发系统安装
- 通知时 `NoticeChecker` 拉取 `notices.json` → DataStore 缓存与已读 ID → 侧边栏红点与通知页
- 体测查询时 `FitnessApiService` 登录体测平台 → `FitnessParser` 解析当前/历年/评分标准 → 加密会话与本地缓存 → Compose UI

## 教务端点

| 功能 | 端点 | 编码 |
|------|------|------|
| 课表 | currcourse.jsdo, showTimetable.do | GBK/UTF-8 |
| 成绩 | studentOwnScore.do (POST) | GBK |
| 考试 | studentQueryAllExam.do, queryExam.do 等 | JSON/HTML |
| 等级考试 | skilltest.jsdo?moduleId=2090 | GBK |
| 教学计划 | studentSelfSchedule.jsdo → studentScheduleLineShow.do | GBK/UTF-8 |
| 体测成绩 | tzcs.glut.edu.cn 学生体测平台 | GBK/UTF-8 |
| 节假日 | timor.tech API | JSON |

## 测试

- JUnit 4.13.2 + kotlinx-coroutines-test
- 覆盖：Parser、Repository、Service、Model 层
- 测试目录：`app/src/test/java/`

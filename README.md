# 桂系一站式

面向桂林理工大学学生的本地 Android 校园信息工具。支持导入课表与教务数据、计算专业成绩、查询体测和财务信息，并提供校历、作息时间、校车路线与校园地图。账号密码使用系统加密存储，主要业务数据和缓存保存在本机。

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
| 专业成绩 | 按培养计划筛选课程并计算学年加权成绩 | 本地考试成绩 + 教学计划 |
| 考试安排 | 考试时间、地点、座位号，时间线 UI，按科目显示图标 | 多端点探测 (JSON/HTML) |
| 考级成绩 | 英语四六级、普通话等国家考试 | skilltest.jsdo (moduleId=2090) |
| 体测成绩 | 最新与历年体测明细、总评、评分标准 | 体测管理平台 |
| 校园信息 | 校历、作息时间、校车路线和校园地图 | 内置校园地图 + 校园信息图片服务 |
| 财务 | 财务概览、缴费项目、交易记录和电子票据 | 桂林理工大学财务平台 |
| 教学计划 | 课组学分/门数要求与完成情况 | studentSelfSchedule.jsdo → studentScheduleLineShow.do |
| 学期概览 | 学期日期、进度、节假日、调课一览 | 教务日历 + timor.tech 节假日 API |
| 导入课表 | 学号密码登录，一键导入全部数据 | 多端点并行探测 |
| 通知 | App 内公告、维护提醒、更新提示，支持未读红点 | Cloudflare Pages notices.json |
| 常见问题 | FAQ 分类展开/收起（常见问题/数据解读/隐私安全/关于项目） | — |
| 设置 | 显示周末、自定义背景、课程卡片颜色 | — |
| 关于 | 版本信息、维护者、检测更新、小程序名称复制、App 内下载安装 | Cloudflare Pages + GitHub Releases API |

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

### 专业成绩
- 按一个学年（秋季 + 次年春季）汇总培养计划中的应修课程
- 采用 `Σ(课程百分制成绩 × 教学计划学分) ÷ Σ教学计划学分` 计算
- 排除补修、重学/重修、体育、公共选修/任选、辅修、双学位等课程
- 支持五级制成绩折算，并对补考、缓考、作弊/旷考等情况显示计算说明
- 展示参与计算课程、缺失课程、总学分和加权成绩

### 体测成绩
- 独立登录体测平台，支持验证码刷新、密码显隐和密码重置地址复制
- 展示最新体测总评及各项目分数、结论和测试成绩
- 历年成绩按学年学期切换，完整展示体重、耐力加分等项目
- 提供男生、女生、BMI、加分四张评分标准表
- 长表支持固定表头与前两列，横纵向滚动互不冲突

### 校园信息
- 提供教学日历、上课时间、校车路线和校园地图四个标签
- 教学日历、上课时间和校车路线从学校信息服务页面获取并进行本地缓存
- 校园地图随 APK 内置，无网络时仍可查看
- 图片支持缩放、拖动及标签切换

### 财务
- 独立登录桂林理工大学财务平台，支持验证码和会话失效重登
- 提供财务概览、待缴项目、其他缴费、收费项目、交易记录和缴费明细
- 支持选课记录、电子票据与学分结算查询
- 凭据加密保存，模块数据按更新时间缓存在本机

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
- Room 保存课表、成绩、考试、教学计划和调课等教务数据
- DataStore 保存显示设置、学期信息、通知缓存和已读状态
- 教务、体测和财务凭据使用加密存储；财务、体测和校园图片使用独立本地缓存
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
  MainActivity.kt                  单 Activity、侧边抽屉导航、页面装配
  ScheduleApplication.kt           Application 与 AppContainer 手动依赖注入

  data/
    model/                          课表、成绩、专业成绩、考试、教学计划、体测、财务、通知等领域模型
    local/                          Room 实体、DAO、数据库与迁移
    repository/                     聚合 Room Flow 的统一数据仓库
    settings/                       DataStore 设置、学期信息、通知与节假日缓存

  service/
    academic/                       教务登录、会话、凭据、多端点探测与考试请求
    parser/                         课表、成绩、考试、教学计划和体测解析器
    fitness/                        体测 API、请求协议、凭据与页面缓存
    finance/                        财务 API、HTML 解析、缓存编解码与加密存储
    campus/                         校园信息图片抓取、可信来源校验与文件缓存
    AppUpdater.kt                   APK 流式下载与 FileProvider 安装
    UpdateChecker.kt                Cloudflare Pages / GitHub 版本检测
    NoticeChecker.kt                App 内通知拉取与 JSON 解析

  ui/
    components/                     课表背景、表头和网格等复用 Compose 组件
    navigation/                     抽屉菜单定义与校区可见性规则
    pages/                          各业务页面及 ViewModel，包括专业成绩、校园信息和财务
    theme/                          Material 3 主题

app/src/main/res/
  drawable-nodpi/                  内置雁山校区地图
  drawable/                        品牌、默认背景和教学计划状态资源
  xml/                             FileProvider、备份与数据提取规则
```

## 数据流

- 教务导入：`AcademicLoginService` / `ApiProbeService` → 各 Parser → `ScheduleRepository`（Room）→ ViewModel `StateFlow` → Compose UI
- 专业成绩：Room 中的成绩 + 教学计划 → `ProfessionalScoreCalculator` 按学年筛选与折算 → `ProfessionalScoreViewModel` → 结果卡片
- 体测查询：`FitnessApiService` → `FitnessProtocol` / `FitnessParser` → `FitnessStore` → `FitnessScoreViewModel`
- 财务查询：`FinanceApiService` → `FinanceParser` → `FinanceStore` / `FinanceCacheCodec` → `FinanceViewModel`
- 校园信息：`CampusImageService` → 可信图片校验与文件缓存 → `CampusImageViewModel`；校园地图直接读取内置资源
- 设置与通知：`ScheduleSettingsStore` / `NoticeChecker` → DataStore 缓存与状态 Flow → 对应页面和侧边栏红点
- App 更新：`UpdateChecker` → `AppUpdater` 流式下载 APK → FileProvider → 系统安装器

## 数据来源与服务端点

| 功能 | 数据来源 | 格式/协议 |
|------|----------|-----------|
| 课表 | `currcourse.jsdo`、`showTimetable.do` | GBK/UTF-8 HTML |
| 成绩 | `studentOwnScore.do` | POST + GBK HTML |
| 考试 | `studentQueryAllExam.do`、`queryExam.do` 等 | JSON/HTML 多端点探测 |
| 考级成绩 | `skilltest.jsdo?moduleId=2090` | GBK HTML |
| 教学计划 | `studentSelfSchedule.jsdo` → `studentScheduleLineShow.do` | GBK/UTF-8 HTML |
| 体测成绩 | 桂林理工大学体测管理平台 | HTTP + GBK/UTF-8 HTML |
| 财务 | `https://cwjf.glut.edu.cn` | HTTPS + HTML/JSON |
| 校历、作息、校车 | `https://xxfw.glut.edu.cn/GlutInfoService/` | HTTPS + HTML 图片提取 |
| 校园地图 | APK 内置 `drawable-nodpi` 资源 | PNG |
| 节假日 | timor.tech API | HTTPS + JSON |
| 通知与更新 | Cloudflare Pages，失败时回退 GitHub | HTTPS + JSON/APK |

## 测试

- JUnit 4.13.2 + kotlinx-coroutines-test
- 覆盖领域模型、Parser、Repository、API Service、缓存编解码、ViewModel 和 UI 契约
- 包含桂林/南宁教务、专业成绩、体测、财务、校园信息、通知、更新与发布版本回归测试
- 测试目录：`app/src/test/java/`

# GLUT 教务系统对接方案对比分析

> 分析日期：2026-06-07
> 分析项目：GlutAssistant (Flutter)、glut (Kotlin)、scheduleApp_weixin (微信小程序)

---

## 一、项目概览

| 维度 | GlutAssistant | glut | scheduleApp_weixin |
|------|:---:|:---:|:---:|
| 类型 | Android (Flutter/Dart) | Android (Kotlin) | 微信小程序 (JS) |
| 包名 | com.lkm.glutassistant | com.jacknic.glut | wxab008bc865ac73e5 |
| 网络层 | http 包 (Dart) | Retrofit + OkHttp | 云函数 (Node.js http) |
| HTML 解析 | 正则表达式 | Jsoup (CSS selector) | 正则表达式 + DOM |
| 编码处理 | gbk2utf8 包 | Jsoup 自动检测 | iconv-lite |
| 数据存储 | SQLite (sqflite) | Room (SQLite) | wx.Storage (本地缓存) |
| 状态管理 | Provider | LiveData + ViewModel | setData + Storage |

---

## 二、教务系统 API 端点汇总

### 2.1 基础 URL

```
教务系统: http://jw.glut.edu.cn/academic
南宁分校: http://jw.glutnn.cn/academic
财务系统: http://cwjf.glut.edu.cn
体测系统: http://tzcs.glut.edu.cn
统一认证: http://ca.glut.edu.cn:8888/zfca/login
```

### 2.2 所有已知端点

| 功能 | 方法 | 端点 | 使用项目 |
|------|------|------|----------|
| **登录页面** | GET | `/academic/affairLogin.do` | weixin |
| **登录提交** | POST | `/academic/j_acegi_security_check` | 全部三个项目 |
| **登录验证码** | GET | `/academic/getCaptcha.do` | glut |
| **检查验证码** | GET | `/academic/checkCaptcha.do?captchaCode=xxx` | glut |
| **登录状态检查** | GET | `/academic/showHeader.do` | glut |
| **学生信息** | POST | `/academic/personal/framePage.do` | weixin |
| **菜单获取** | POST | `/academic/personal/moduleMenu.do` | weixin |
| **课表 HTML** | GET | `/academic/student/currcourse/currcourse.jsdo?year=X&term=X` | GlutAssistant, glut |
| **课表 HTML (by studentId)** | GET | `/academic/manager/coursearrange/showTimetable.do?id=X&yearid=X&termid=X&timetableType=STUDENT&sectionType=BASE` | weixin |
| **成绩查询** | POST | `/academic/manager/score/studentOwnScore.do?groupId=&moduleId=2020` | GlutAssistant, scheduleApp |
| | | 参数: `year=X&term=X&para=0` |
| **成绩查询 (全量)** | GET | `/academic/manager/score/studentOwnScore.do?year=&term=&para=0&Submit=查询` | glut |
| **全部考试** | GET | `/academic/manager/examstu/studentQueryAllExam.do?pagingNumberPerVLID=1000` | glut |
| **考试安排 (JSON)** | POST | `/academic/student/examination/queryExam.do` | weixin, scheduleApp |
| **考试安排 (HTML)** | GET | `/academic/student/examination/studentExamQuery.do` | weixin |
| **考试安排** | GET | `/academic/student/examination/examArrange.do` | weixin |
| **考试安排** | POST | `/academic/manager/examarrange/examStudentQuery.do` | weixin |
| **考试安排** | GET | `/academic/student/examination/examinationForStudent.do` | weixin |
| **考试模块入口** | GET | `/academic/accessModule.do?moduleId=2030` | weixin |
| **考试地点** | GET | `/academic/student/exam/index.jsdo?stuid=` | GlutAssistant |
| **统一认证页面** | GET | `http://ca.glut.edu.cn:8888/zfca/login` | GlutAssistant |
| **体测成绩** | POST | `http://tzcs.glut.edu.cn/spQuery` | GlutAssistant |
| | | 参数: `method=healthscore, stuNo=base64(sid), schoolid=100001` |

---

## 三、登录/认证机制对比

### 3.1 三种登录方式

#### 方式一：教务系统直接登录（最常用）

```
POST http://jw.glut.edu.cn/academic/j_acegi_security_check
Content-Type: application/x-www-form-urlencoded

j_username={学号}
j_password={密码}
j_captcha={验证码}     ← 实测可为空
```

**成功判断**：响应 `Location` 头包含 `index_new`

> **注意**：GlutAssistant 使用 POST，但 scheduleApp_weixin 云函数使用 **GET** 方式（带查询参数）同样有效：
> ```
> GET /academic/j_acegi_security_check;jsessionid={JSESSIONID}?j_username=X&j_password=X&j_captcha=
> ```

#### 方式二：统一身份认证（OA 登录）

```
1. GET http://ca.glut.edu.cn:8888/zfca/login
   → 提取隐藏字段 lt

2. POST http://ca.glut.edu.cn:8888/zfca/login
   _eventId=submit, j_captcha_response, lt, password, useValidateCode=1, username

3. POST http://ca.glut.edu.cn:8888/zfca/tojw
   → 跟随 Location 跳转 2 次
   → 最终验证 Location 包含 index_new
```

#### 方式三：南宁分校

```
POST http://jw.glutnn.cn/academic/j_acegi_security_check
（参数同方式一）
注意：秋季学期 term=3（而非 2）
```

### 3.2 Cookie 管理策略

| 项目 | Cookie 存储 | 传递方式 | 跨请求合并 |
|------|------------|---------|-----------|
| GlutAssistant | 文件系统 (纯文本) | HTTP Header `Cookie:` | 文件覆盖写入 |
| glut | Android CookieManager | OkHttp CookieJar 自动 | CookieManager 原生 |
| weixin (cloud) | 内存 Map | HTTP Header `Cookie:` | mergeCookies() 按名称覆盖 |

### 3.3 Session 持久化

- **GlutAssistant**：`/data/data/.../app_flutter/session` 纯文本文件
- **glut**：Android WebKit `CookieManager` 自动持久化
- **weixin**：云函数无状态，每次调用重新登录；客户端 `wx.setStorageSync('glut_credentials', {username, password})`

### 3.4 密码存储

- **GlutAssistant**：SharedPreferences 明文存储
- **glut**：SharedPreferences 明文存储
- **scheduleApp**：EncryptedSharedPreferences 加密存储 ✅

---

## 四、课表查询实现对比

### 4.1 API 调用对比

| 项目 | API 端点 | 请求方式 |
|------|---------|---------|
| GlutAssistant | `currcourse.jsdo?term=X&year=X` (year=公历-1980) | GET |
| glut | `currcourse.jsdo?year=X&term=X` | GET (Retrofit) |
| weixin/scheduleApp | `showTimetable.do?id={studentId}&yearid=X&termid=X&timetableType=STUDENT&sectionType=BASE` | GET |

### 4.2 HTML 解析策略

#### GlutAssistant (正则表达式)

```dart
// 1. 提取课程块
RegExp(r'infolist_common"onmouseover="(.*?)</a></td></tr>')

// 2. 从课程块提取名称、教师
RegExp(r'class="infolist">(.*?)</a></td>(.*?)arget=(.*?)</a><br></td>')

// 3. 从嵌套表格提取时间
RegExp(r'class="none"><tr><tdwid(.*?)</td></tr></table></td>')

// 4. 从时间 HTML 提取周次/星期/节次/地点
RegExp(r'nowrap>(.*?)</td>.*?nowrap>(.*?)</td>.*?nowrap>(.*?)</td>.*?nowrap>(.*?)</td>')
// 组1: 周范围  → expandWeekString()
// 组2: 星期几  → dayOfWeekChar()
// 组3: 节次    → 5-6 → 映射 7-8 (布局偏移)
// 组4: 地点
```

#### glut (Jsoup CSS Selector)

```kotlin
// 1. 读取学期信息
dom.select("select[name='year'] option[selected]")
dom.select("select[name='term'] option[selected]")

// 2. 定位课程表格
dom.select(".infolist_tab .infolist_common")

// 3. 对每个课程行:
//    child(2) → 课程名称
//    child(4) → 教师
//    嵌套 <tr> 子行 → 每个是课时安排
```

#### weixin/scheduleApp (三层解析策略)

```javascript
// 策略1: GLUT 专用 timetable 表格 (优先)
parseGlutTimetableGrid(doc)
// → 解析 <table id="timetable"> 的 rowspan/colspan 网格
// → 提取 << 课程名 >> 和 << 教师名 >> 分隔符

// 策略2: 课程安排明细表 (辅助)
parseCourseArrangementRows(doc)
// → 解析 timetable 下方 details 表格的列

// 策略3: 通用表格 (兜底)
parseGenericTable(doc)
// → 自动检测表头列 (课程/教师/时间/地点等关键字)

// 策略4: 纯文本正则 (最后手段)
parseTextBased(html)
// → 正则匹配 "课程名 星期X 第N-M节 ..." 模式
```

### 4.3 周次解析

三个项目都实现了相似的 `expandActiveWeeks()` 逻辑：

| 输入示例 | 输出 |
|---------|------|
| `"1-16"` | [1,2,...,16] |
| `"1,3,5"` | [1,3,5] |
| `"1-16周单周"` | [1,3,5,...,15] |
| `"1-16周双周"` | [2,4,6,...,16] |

### 4.4 调课/补课处理

| 项目 | 支持情况 |
|------|:---:|
| GlutAssistant | ❌ |
| glut | ❌ |
| scheduleApp/weixin | ✅ 解析调课表格，删除原课时，插入补课 |

---

## 五、成绩查询实现对比

### 5.1 API 端点对比

| 项目 | 端点 | 方法 | 参数 | 请求次数 |
|------|------|------|------|:---:|
| GlutAssistant | `manager/score/studentOwnScore.do` | POST | groupId=&moduleId=2020, year=X-1980, term=X, para=0 | 1/学期 |
| glut | `manager/score/studentOwnScore.do` | GET | year=, term=, para=0, Submit=查询 | **1次全量** |
| scheduleApp | `manager/score/studentOwnScore.do` | POST | year=X-1980, term=X, para=0 | 8次 (4年×2学期) |

> ⚠ **scheduleApp 可优化**：采用 glut 的做法，一次 `GET studentOwnScore.do?year=&term=&para=0` 获取全部成绩，减少到 1 次请求。

### 5.2 成绩 HTML 解析

#### GlutAssistant (正则)

```dart
// 桂林校区
RegExp(r'<td>[春秋]</td>.*?<td>\d+?</td><td>(.*?)</td><td>\d+?</td><td>(.*?)</td><td>(.*?)</td><td>(\d?\.\d?)</td>')
// 组1: 课程名, 组2: 教师, 组3: 成绩, 组4: 绩点

// 南宁校区 (列顺序不同)
RegExp(r'<td>[春秋]</td><td>.*?</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td><td>(.*?)</td>')
// 组1: 课程名, 组2: 教师, 组3: 成绩, 组4: 学分
```

**成绩类型处理**：
- 文字成绩 ("优秀"/"良好"/"中等"/"及格") → 转换为百分制: `(5 + GPA) * 10`
- 数字成绩 → 直接提取
- "不及格" → 特殊处理

#### glut (Jsoup)

```kotlin
dom.select("table.datalist tr")  // CSS 选择器
// child(0) = 学年, child(1) = 学期, child(4) = 课程名
// child(7) = 成绩, child(20) = 是否及格
```

### 5.3 考试查询端点对比

| 端点 | GlutAssistant | glut | weixin/scheduleApp |
|------|:---:|:---:|:---:|
| `exam/index.jsdo?stuid=` | ✅ | ❌ | ❌ |
| `examstu/studentQueryAllExam.do` | ❌ | ✅ | ❌ |
| `examination/queryExam.do` (JSON) | ❌ | ❌ | ✅ |
| `examination/studentExamQuery.do` | ❌ | ❌ | ✅ |
| `examination/examArrange.do` | ❌ | ❌ | ✅ |
| `manager/examarrange/examStudentQuery.do` | ❌ | ❌ | ✅ |
| `examination/examinationForStudent.do` | ❌ | ❌ | ✅ |

### 5.4 scheduleApp 考试端点探测策略

```
1. GET /academic/accessModule.do?moduleId=2030  → 考试模块入口
2. POST /academic/personal/moduleMenu.do        → 提取考试菜单 URL
3. 依次探测 5 个端点 (找到即停):
   a. GET  student/examination/studentExamQuery.do
   b. POST student/examination/queryExam.do         ← JSON (最佳)
   c. GET  student/examination/examArrange.do
   d. POST manager/examarrange/examStudentQuery.do
   e. GET  student/examination/examinationForStudent.do
4. 对每个响应评分 (含考试关键字/表格 → 高分)
5. 优先使用 JSON 格式，其次 HTML 表格
```

---

## 六、考试解析对比

### GlutAssistant (正则 - 考试地点)

```dart
RegExp(r'class="infolist_tab">(.*?)</table>')  // 定位表格
RegExp(r'class="infolist_common"><td>(\d+?)</td><td>(.*?)</td><td>(\d{4}-\d{2}-\d{2})(\d{2}:\d{2}--\d{2}:\d{2})</td><td>(.*?)</td>.*?<td>(.*?)</td>')
// 组1: 序号, 组2: 课程名, 组3: 日期, 组4: 时间范围, 组5: 地点, 组6: 考核方式
```

### weixin/scheduleApp (双模式解析)

```javascript
// 模式1: JSON (优先) — 递归遍历, 匹配 exam-like 字段
parseExamJson(json)
// → 支持字段变体: courseName/kcmc/name/kc 等

// 模式2: HTML (兜底) — 关键字检测表头, 映射列索引
parseExamHtml(html)
// → 列: 课程名/日期/时间/地点/座位号/考试类型
```

### 考试数据模型对比

| 字段 | GlutAssistant | glut | scheduleApp |
|------|:---:|:---:|:---:|
| 课程名称 | ✅ | ✅ | ✅ |
| 考试日期 | ✅ | ✅ | ✅ |
| 考试时间 | ✅ | ✅ | ✅ |
| 考试地点 | ✅ | ✅ | ✅ |
| 座位号 | ❌ | ❌ | ✅ |
| 考核方式 | ✅ | ❌ | ✅ |
| 考试类型 | ❌ | ❌ | ✅ |
| 备注 | ❌ | ❌ | ✅ |

---

## 七、架构对比

### GlutAssistant (Flutter)

```
UI (StatefulWidget)
  ↕ Provider
Model (ChangeNotifier)
  ↕ http package
Utility (HttpUtil.get/post + cookie header)
  → HTML (GBK → gbk2utf8 decode)
  → 正则表达式解析
  → SQLite (sqflite) 持久化
```

### glut (Kotlin + Retrofit)

```
UI (Fragment/Page)
  ↕ LiveData
ViewModel (BaseRequestViewModel)
  ↕ Coroutines
Repository → DataSource
  ↕ Retrofit (JwApi/CwApi)
OkHttp + AndroidCookieJar (共享 WebKit CookieManager)
  → HTML (Jsoup Document) / JSON (Gson)
  → Jsoup CSS Selector 解析
  → Room SQLite 持久化
```

### scheduleApp / weixin

```
[客户端]
UI (Compose / WXML)
  ↕ StateFlow / setData
ViewModel / Page Logic
  ↕ Room / wx.Storage
Repository

[云函数 / 导入层]
loginAndGetSchedule(username, password)
  1. GET affairLogin.do → JSESSIONID
  2. GET j_acegi_security_check → 登录
  3. POST framePage.do → 学生信息 + 校历
  4. GET showTimetable.do → 课表 HTML
  5. 探测考试端点 → 考试 JSON/HTML
  → 解析 (parser.js / Kotlin Parser)
  → 本地持久化
```

---

## 八、关键差异与可借鉴点

### 8.1 scheduleApp 当前可改进的方向

| 方面 | 当前实现 | 最优方案 | 改进建议 |
|------|---------|---------|---------|
| 成绩查询 | 8次请求 (4年×2学期) | glut: 1次 GET 全量 | 改用 `year=&term=` 空参数单次请求 |
| 验证码 | 需要手动输入 | 实测可为空 | 尝试空验证码登录 |
| Cookie 管理 | DataStore 字符串 | glut: CookieManager | 可考虑迁移到 CookieManager |
| 考试查询 | 单独端点 | glut: 已有可靠端点 | 可复用 `examstu/studentQueryAllExam.do` |
| 登录方式 | 教务直登 | OA 统一认证 | 可增加 OA 登录作为备选 |

### 8.2 各项目功能矩阵

| 功能 | GlutAssistant | glut | scheduleApp/weixin |
|------|:---:|:---:|:---:|
| 教务直登 | ✅ | ✅ | ✅ |
| OA 统一认证 | ✅ | ❌ | ❌ |
| 财务查询 | ❌ | ✅ | ❌ |
| 体测成绩 | ✅ | ❌ | ❌ |
| 考试地点 | ✅ | ❌ | ❌ |
| 调课/补课 | ❌ | ❌ | ✅ |
| 自定义背景 | ❌ | ❌ | ✅ |
| Widget 小组件 | ✅ | ✅ | ❌ |
| 多校区 | ✅ | ❌ | ❌ |
| 示例数据 | ❌ | ❌ | ✅ |
| 更新检查 | ❌ | ✅ | ✅ |
| 记住密码 | ✅ 明文 | ✅ 明文 | ✅ 加密 |

---

## 九、成绩查询优化建议

### 当前问题

scheduleApp 的 `DirectLoginViewModel.fetchAndSaveScores()` 遍历 4 年 × 2 学期：

```kotlin
val years = listOf("2025-2026", "2024-2025", "2023-2024", "2022-2023")
for (year in years) {
    for (term in 1..2) {
        // POST studentOwnScore.do?year=X&term=X  ← 8 次请求
    }
}
```

### 优化方案

参考 glut 的做法，**一次请求获取全部成绩**：

```kotlin
// glut 的 JwApi.kt (已验证可用)
@GET("manager/score/studentOwnScore.do?year=&term=&para=0&sortColumn=&Submit=%E6%9F%A5%E8%AF%A2")
suspend fun score(): Response<ResponseBody>
```

或使用 POST 空参数：

```
POST /academic/manager/score/studentOwnScore.do
year=&term=&para=0
```

这样从 8 次 HTTP 请求减少到 **1 次**，大幅提升导入速度。

---

## 十、总结

三个项目共享相同的底层教务系统 (`jw.glut.edu.cn`)，但采用了不同的技术栈和实现策略：

1. **登录**：核心都是 `j_acegi_security_check`，GlutAssistant 额外支持 OA 统一认证
2. **课表**：主要使用 `currcourse.jsdo` 获取 HTML，scheduleApp 的三层解析策略最健壮
3. **成绩**：使用 `studentOwnScore.do`，**scheduleApp 可优化为一次请求获取全量**（从 8 次 → 1 次）
4. **考试**：端点最多样化，scheduleApp 的探测策略最全面，但 glut 的 `studentQueryAllExam.do` 单一端点更简洁
5. **数据持久化**：都使用 SQLite，scheduleApp 额外使用 DataStore 管理设置

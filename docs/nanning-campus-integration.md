# 南宁分校集成总结

> 日期：2026-06-07 | 版本：v0.7.8+

## 概述

南宁分校教务系统 (`jw.glutnn.cn`) 使用优慕课 (U-MOOC) 平台，与桂林分校 (`jw.glut.edu.cn`) 的正方教务系统在登录机制上有显著差异，但课表/考试/成绩的 API 结构和 HTML 格式完全兼容。

## 桂林 vs 南宁对比

| 维度 | 桂林 | 南宁 |
|------|------|------|
| 教务网址 | `jw.glut.edu.cn` | `jw.glutnn.cn` |
| 学号位数 | 13位 | 10位 |
| 登录页面 | `/academic/affairLogin.do` | `/academic/common/security/affairLogin.jsp` |
| 教务平台 | 正方教务 | 优慕课 (U-MOOC) |
| 验证码 | 可为空 | 必须输入，服务端校验 |
| 密码加密 | 明文 | MD5(MD5(password)) |
| 登录方式 | GET j_acegi_security_check | GET（需先 AJAX 校验验证码） |

## 登录流程（优慕课）

```
1. validCaptcha(): AJAX POST /academic/checkCaptcha.do?captchaCode=XXXX
   → 服务端返回 "true" 才继续，否则拒绝
2. trans(): MD5(MD5(UTF8(password))) → hex 字符串
3. location.href = /academic/j_acegi_security_check?j_username=...&j_password=HASH&j_captcha=...
4. 302 → index_new.jsp → 302 → index_frame.jsp（登录成功）
```

## 关键实现

### 1. 密码哈希 (NanningPasswordHash.kt)

```kotlin
// submit_hex_md5(s, salt) from md5.js
fun hash(password: String): String {
    val first = MessageDigest.getInstance("MD5")
        .digest(password.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return MessageDigest.getInstance("MD5")
        .digest(first.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
```

### 2. Cookie 管理 — 必须用 CookieJar

错误做法：每个请求手动设置 `header("Cookie", jsession)`
- 中间请求的 Set-Cookie 被丢弃
- 会话不一致导致验证码校验失败

正确做法：`CapturingCookieJar`（与桂林登录相同机制）
- OkHttp 自动存储所有响应 Cookie
- 后续请求自动发送匹配的 Cookie

### 3. 验证码对话框

不用 WebView（太重），而是：
1. 下载验证码图片 → 显示在原生 AlertDialog
2. 用户输入 → POST checkCaptcha.do（模拟 AJAX）
3. 校验通过 → MD5 哈希 → GET 登录

### 4. 课表导入 fallback

南宁 framePage.do 返回的内部用户 ID 与学号不同，`buildCurrentStudentTimetableUrls` 可能无法提取。解决方案：用学号直接请求 `showTimetable.do?id=学号&...`。

## 涉及文件

- `DirectLoginViewModel.kt` — 南宁登录 + CookieJar + fallback 课表 URL
- `DirectLoginScreen.kt` — 原生验证码 AlertDialog
- `NanningPasswordHash.kt` — MD5(MD5(UTF8(password)))
- `AcademicLoginService.kt` — `NANNING_URL` 常量
- `ApiProbeService.kt` — `probeUrl()` 单 URL 探测
- `AcademicScheduleParser.kt` — 课表解析（两校区 HTML 格式相同）

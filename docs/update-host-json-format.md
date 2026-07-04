# app-update-host JSON 格式使用说明

本文说明 `C:\Users\ZhuanZ\Desktop\app-update-host` 仓库中两个线上 JSON 文件的维护方式：

- `update.json`：用于 App 版本更新检查。
- `notices.json`：用于 App 内“通知”菜单。

两个文件都会通过 Cloudflare Pages 发布到 `https://update.999314.xyz/`。

## update.json

示例：

```json
{
  "versionCode": 108,
  "updateDesc": "优化假期显示逻辑",
  "versionName": "0.14.10",
  "forceUpdate": false,
  "downloadUrl": "https://update.999314.xyz/glutShedule_0.14.10.apk"
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `versionCode` | number | 是 | 内部版本号，应和 `app/build.gradle.kts` 中的 `versionCode` 对应。 |
| `versionName` | string | 是 | 展示给用户看的版本号，例如 `"0.14.10"`。App 用它和当前版本比较。 |
| `downloadUrl` | string | 是 | APK 下载地址。通常是 `https://update.999314.xyz/<apk文件名>`。 |
| `updateDesc` | string | 否 | 更新说明，会显示在更新弹窗中。没有内容时填空字符串 `""`。 |
| `forceUpdate` | boolean | 否 | 当前 App 代码暂未强制执行此字段。建议保留，默认填 `false`。 |

维护规则：

- 发布新 APK 时，`versionCode` 和 `versionName` 要和 App 构建配置保持一致。
- `downloadUrl` 指向的 APK 文件必须已经放在 `app-update-host` 仓库并成功发布。
- `versionName` 建议使用纯数字点号格式，例如 `0.14.11`，避免写成 `v0.14.11`。
- 不要把通知公告写进 `update.json`，通知使用独立的 `notices.json`。

## notices.json

示例：

```json
{
  "schemaVersion": 1,
  "updatedAt": "2026-07-04T12:00:00+08:00",
  "notices": [
    {
      "id": "2026-07-04-notice-feature",
      "title": "通知中心上线",
      "content": "通知中心将用于发布教务系统维护、课表导入说明和应用更新相关提醒。",
      "level": "info",
      "publishedAt": "2026-07-04",
      "expiresAt": "",
      "url": ""
    }
  ]
}
```

顶层字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `schemaVersion` | number | 是 | 通知文件格式版本。当前固定填 `1`。 |
| `updatedAt` | string | 是 | 整个通知文件的最后更新时间。用于维护和排查，不参与通知排序。 |
| `notices` | array | 是 | 通知条目列表。可以为空数组 `[]`。 |

通知条目字段说明：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | 通知唯一 ID。App 用它判断是否已读。新通知必须使用新的 ID。 |
| `title` | string | 是 | 通知标题。为空时 App 会忽略这条通知。 |
| `content` | string | 否 | 通知正文。没有内容时填空字符串 `""`。 |
| `level` | string | 否 | 通知级别。见下方可选值。未填写或空值时按 `info` 处理。 |
| `publishedAt` | string | 是 | 发布日期，格式必须是 `yyyy-MM-dd`，例如 `"2026-07-04"`。App 按它从新到旧排序。 |
| `expiresAt` | string | 否 | 过期日期，格式为 `yyyy-MM-dd`。为空字符串表示不过期。早于当天的通知不会显示。 |
| `url` | string | 否 | 详情链接。为空时不显示“查看详情”。 |

`level` 可选值：

| 值 | 用途 | App 显示颜色 |
| --- | --- | --- |
| `info` | 普通通知、说明、提示 | 灰色 |
| `update` | 版本更新、功能更新说明 | 蓝色 |
| `warning` | 维护、故障、可能影响使用的提醒 | 橙色 |
| `important` | 重要通知、需要用户特别关注的事项 | 红色 |

排序和已读规则：

- 通知列表由 App 按 `publishedAt` 从新到旧排序。
- JSON 文件中条目的手写顺序不决定最终显示顺序。
- `updatedAt` 只表示整个文件更新时间，不决定通知上下顺序。
- App 使用 `id` 判断已读。旧通知只改 `title` 或 `content` 不会重新出现红点。
- 如果需要让用户再次看到红点，必须新增一条通知，或给旧通知换一个新的 `id`。

日期规则：

- `publishedAt` 和 `expiresAt` 使用 `yyyy-MM-dd`。
- 正确示例：`"2026-07-04"`。
- 错误示例：`"2026/07/04"`、`"2026年7月4日"`、`"07-04"`。
- `expiresAt` 为空字符串时表示长期有效。

## 发布流程

1. 编辑 `C:\Users\ZhuanZ\Desktop\app-update-host\update.json` 或 `notices.json`。
2. 确认 JSON 格式合法，尤其注意最后一个字段后面不能有逗号。
3. 提交并推送 `app-update-host` 仓库。
4. 等待 Cloudflare Pages 部署完成。
5. 在浏览器访问以下地址确认内容已更新：

```text
https://update.999314.xyz/update.json
https://update.999314.xyz/notices.json
```

## 常见错误

- 字符串没有用英文双引号，例如写成 `'title': '通知'`。JSON 必须使用 `"`。
- 最后一个字段后面多写逗号。
- `publishedAt` 日期格式写错，导致排序异常。
- 修改旧通知但没有改 `id`，用户端不会重新显示未读红点。
- 把 `notices` 写成对象 `{}`，正确格式必须是数组 `[]`。
- `downloadUrl` 指向的 APK 文件不存在或文件名拼错。

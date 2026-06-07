## 修复

### 成绩查询（4 个致命 bug）
- **年份解析**：现在支持 3 种格式（"2025-2026"、纯数字 "2019"、编码 "45"→2025）
- **南宁列索引**：对照 GlutAssistantN 修正（课程=3、成绩=5、绩点=6、学分=7）
- **POST 替代 GET**：匹配 HTML 表单和全部 3 个参考项目
- **GBK 编码**：检测并处理 GLUT 教务系统的 GBK/GB2312 编码

### 更新通知系统
- 双通道更新检查（GitHub API + GitHub Pages 容灾）
- 菜单"关于"旁红点提示
- GitHub Actions 发布时自动部署 version.json

## 下载
glutShedule_0.7.16.apk

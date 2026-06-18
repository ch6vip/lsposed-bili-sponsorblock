# LSPosed SponsorBlock 模块

目标：复刻 `Bili-v8.98.0-x1.27.3@bb_show.apk` 的 SponsorBlock 行为，做成独立 LSPosed 模块。

当前状态：
- 已可运行，命中 `tv.danmaku.bili` 主进程
- 当前版本 `v0.5.0`

已实现功能：
- 片段拉取与自动跳过（命中后 `seekTo(endMs, true)`）
- 跳过策略：手动跳过、片段静音、自动跳过倒计时取消、最小片段时长过滤
- 进度条片段标记（各分类颜色可自定义）、剩余时长扣减、Toast 提示
- 片段标记提交入口
- 提交配置：默认标记类别可配置
- 片段统计（已跳过片段数与节省时长累计，可重置）
- 设置界面：B 站「我的」注入入口；主页单入口 SponsorBlock → 详情页（小标题分隔）；关于页（版本/作者/更新，点「更新」跳转项目主页）；模块入口含基础状态面板
- 跨进程设置持久化（ContentProvider IPC + JSON 镜像三级 fallback）
- 片段缓存 TTL 可配置，`0` 表示不复用缓存
- 设置变更：重新进入播放页面生效，无需重启应用

已验证主链路：
- `aid/cid` 获取
- `BV -> SHA-256 -> 前缀`
- `GET /api/skipSegments/{prefix}`
- 命中片段后 `seekTo(endMs, true)`
- 进度条标记与 Toast 提示

目录说明：
- `app/`：LSPosed 模块实现
- `docs/`：行为对照与验证记录

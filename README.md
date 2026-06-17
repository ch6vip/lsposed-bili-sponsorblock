# LSPosed SponsorBlock 模块

目标：复刻 `Bili-v8.98.0-x1.27.3@bb_show.apk` 的 SponsorBlock 行为，做成独立 LSPosed 模块。

当前状态：
- 已可运行
- 已命中 `tv.danmaku.bili` 主进程
- 已完成片段拉取、自动跳过、进度条标记、剩余时长扣减、提交入口、设置页和持久化

已验证主链路：
- `aid/cid` 获取
- `BV -> SHA-256 -> 前缀`
- `GET /api/skipSegments/{prefix}`
- 命中片段后 `seekTo(endMs, true)`
- 进度条标记与 Toast 提示

目录说明：
- `app/`：LSPosed 模块实现
- `docs/`：行为对照与验证记录

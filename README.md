# LSPosed SponsorBlock 复刻骨架

目标：把 `Bili-v8.98.0-x1.27.3@bb_show.apk` 里的 SponsorBlock 行为，拆成可独立开发的 LSPosed 模块。

状态：初始骨架，尚未实现可运行 hook。

已确认的主链路：
- 播放器容器创建时注入
- 按 `BV` 做 `SHA-256` 前缀
- 请求 `/api/skipSegments/{prefix}`
- 命中片段后 seek 到结束点
- 绘制进度条片段和更新剩余时长

当前这个目录只放工程骨架和复刻笔记，后续直接在这里补实现。

## 计划拆分

1. `hook/`：LSPosed 入口和播放器 hook
2. `net/`：SponsorBlock 协议请求
3. `model/`：片段、分类、配置结构
4. `ui/`：设置页和调试页
5. `docs/`：行为对照和验证记录

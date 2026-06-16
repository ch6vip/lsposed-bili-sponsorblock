# LSPosed SponsorBlock 复刻计划

## 目标

把 `Bili-v8.98.0-x1.27.3@bb_show.apk` 里的 SponsorBlock 行为，复刻成可独立维护的 LSPosed 模块。

## 阶段任务

### 1. 入口与环境
- [x] 建立独立仓库骨架
- [x] 配置 LSPosed / Xposed 入口文件
- [x] 补齐基础 Gradle 与 manifest
- [x] 接入 libxposed API 102 依赖
- [ ] 构建 APK 并验证模块能被 LSPosed 识别

### 2. 视频与播放器识别
- [ ] 识别 `tv.danmaku.bili` 目标进程
- [ ] 定位播放器容器创建点
- [ ] 获取当前视频 ID
- [ ] 获取 `aid / bvid / cid / duration / currentTime`

### 3. 片段协议
- [ ] 实现 `BV -> SHA-256 -> 前缀`
- [ ] 请求 `/api/skipSegments/{prefix}`
- [ ] 解析片段 JSON
- [ ] 加缓存和失效策略

### 4. 自动跳过
- [ ] 命中片段时 seek 到结束点
- [ ] 支持 `skip / mute / poi` 分类策略
- [ ] 支持手动取消与重跳
- [ ] 支持小窗和切集场景

### 5. UI 与提示
- [ ] 画进度条片段标记
- [ ] 显示跳过提示
- [ ] 显示剩余时间扣减
- [ ] 做设置页

### 6. 提交与统计
- [ ] 片段提交入口
- [ ] 投票和反馈
- [ ] 用户统计
- [ ] 服务器地址和分类配置

## 当前判断

已确认的最小可行链路：
1. 插件进入播放器进程
2. 拉取片段
3. 在播放时 seek

后续先做 `2 + 3`，再补 UI。

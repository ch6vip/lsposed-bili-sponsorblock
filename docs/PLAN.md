# LSPosed SponsorBlock 复刻计划

## 目标

把 `Bili-v8.98.0-x1.27.3@bb_show.apk` 里的 SponsorBlock 行为，复刻成可独立维护的 LSPosed 模块。

## 开发约束

- 参考 `Bili-v8.98.0-x1.27.3@bb_show.apk` 分析 UI、播放器行为和 Hook 点。
- 参考 `hanydd/BilibiliSponsorBlock` 复刻 SponsorBlock 协议、片段过滤和跳过逻辑。
- 不做完整 B 站客户端，只通过 Hook 介入播放器和相关逻辑。
- 每完成一个功能模块或进行较大改动前，先提交并推送 GitHub。
- 关键逻辑必须加注释；APK 行为不明确时，代码和文档都标注为推测实现。

## 阶段任务

### 1. 入口与环境
- [x] 建立独立仓库骨架
- [x] 配置 LSPosed / Xposed 入口文件
- [x] 补齐基础 Gradle 与 manifest
- [x] 接入 libxposed API 101 依赖
- [x] 确认本机 Gradle 与 `E:\Android` SDK 可用
- [x] 构建 APK 并验证模块能被 LSPosed 识别

### 2. 视频与播放器识别
- [x] 识别 `tv.danmaku.bili` 主进程，跳过 `:web` 等子进程
- [x] 定位播放器容器创建点
- [x] 抽象播放器桥接模块
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

## 当前实现状态

- 入口采用 `libxposed` API 102 的 `XposedModule`。
- 当前兼容线已降到 `libxposed` API 101，避免 LSPosed API 102 激活失败。
- 模块元数据位于 `app/src/main/resources/META-INF/xposed/`。
- 当前 hook 是低风险探针版，目标：
  - `Ch1.g#onCreate(Bundle)`
  - `Ch1.g#onDestroy()`
  - `com.bilibili.playerbizcommonv2.widget.seek.v3.f#draw(Canvas)`
- `SponsorBlockClient` 已按 APK 行为生成 `/api/skipSegments/{sha256Prefix}` 请求 URL，但 JSON 解析和播放 seek 仍未实现。
- 播放器桥接采用 APK 中 `PlayerHookProvider` 的方法名策略：
  - `getPlayerCoreService()`
  - `getCurrentPosition()`
  - `getDuration()`
  - `seekTo(int, boolean)`
- `PlayerParamsV2` 字段布局暂未在已反编译 dex 中确认，当前只做运行时探针，属于推测实现。
- `player/` 模块已独立出来，用于承载播放器状态抽取和后续 seek 调用。

## 下一步验收

1. 让仓库能执行 `gradlew.bat :app:assembleDebug`。
2. 修完所有 Kotlin / `libxposed` API 编译问题。
3. 安装 debug APK，确认 LSPosed 识别模块并只作用域到 `tv.danmaku.bili`。
4. 打开 B 站播放页，抓 LSPosed 日志，确认至少命中一个播放器探针。

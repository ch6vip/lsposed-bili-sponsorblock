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
- [x] 获取当前视频 ID（通过 hook `VideoDirectorObserver.onStart`，对齐 APK `PlayerHookProvider.g` 链路）
- [x] 获取 `aid / cid`（director `onStart` 回调解析 `getLogDescription`）
- [x] 获取 `duration / currentTime`（onStart 时从 core 反射 getDuration/getCurrentPosition,进度回调持续回填 duration）
- [x] 获取 `epId`（番剧场景,普通视频为 0,提交时按需带上）

### 3. 片段协议
- [x] 实现 `BV -> SHA-256 -> 前缀`
- [x] 请求 `/api/skipSegments/{prefix}`
- [x] 解析片段 JSON
- [x] 加缓存和失效策略（内存缓存 + 1h TTL,in-flight 防并发,对齐 APK 内存 map + X-SKIP-CACHE）

### 4. 自动跳过
- [x] 绑定播放器 core 并执行 `seekTo(endMs, true)`
- [x] 通过进度文本 hook 检测命中片段
- [x] 支持 `skip / poi` 分类策略（poi_highlight 不自动跳过、不扣时长、画圆点,对齐 APK `an.d()`）
- [ ] 支持 `mute` 静音策略（APK 此版本未实现,暂缓）
- [~] 支持手动取消与重跳（APK 8.98.0 此构建无 unskip/undo,`PlayerHookProvider.C` toast 无 action 按钮,跳过即终态;按"对齐原 APK"原则不做）
- [ ] 支持小窗和切集场景（需运行时验证:小窗是否复用 `Ch1.g` 容器;若是则 director observer + 进度 hook 自动生效,若用独立容器类需补 hook。APK `onStartMiniPlay` 是 patch 层片段迁移,与我们 LSPosed 进程模型不同,不直接对应）

### 5. UI 与提示
- [x] 画进度条片段标记
- [x] 显示跳过提示
- [x] 显示剩余时间扣减
- [ ] 做设置页

### 6. 提交与统计
- [x] 片段提交协议入口
- [x] 播放器标记按钮入口（轻量推测实现）
- [ ] 投票和反馈
- [ ] 用户统计
- [ ] 服务器地址和分类配置
- [x] 本地 `userID` 持久化（按目标 App 私有 `SharedPreferences` 存储，属于推测实现）
- [x] 草稿式标记控制器（第一次标起点，第二次提交）
- [ ] APK 对齐的分类选择、手动时间编辑、预览后确认

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
- `SponsorBlockClient` 已补齐 APK 对齐的提交 URL：`GET /api/skipSegments?userID=...&videoID=...&cid=...&category=...&startTime=...&endTime=...&videoDuration=...`
- `userID` 按 APK 规则生成 32 位无连字符 UUID 字符串；存储位置采用目标 App 私有 `SharedPreferences`，这一点属于推测实现。
- `SubmissionDraftController` 已加入，用于后续挂接播放器按钮的“标记起点/终点”交互。
- 播放器标记入口已通过 `SubmissionButtonInjector` 注入到 `actions_container_right`，点击执行“标起点/标终点提交”，长按取消草稿。
- APK 原始实现注入 `ControlWidgetLinearLayout` + 两个 `ImageView`，并提供标记、预览、分类确认、手动编辑等完整弹窗流程；当前 LSPosed 版本使用普通 `TextView` 控件替代 ReVanced 资源，属于推测实现。
- 播放器桥接只负责取 core / context（用于 seek 与 toast），不再反射 `PlayerParamsV2`。
- **8.96.0 原版适配**（真机装的是原版官方 8.96.0，不是 8.98.0 patch 版；之前对 patch 版 dex 找的类名全错位）：
  - 播放器容器：`be1.j#onCreate(Bundle)` / `onDestroy()`（对应 patch 版 `Ch1.g`；注意 smali 真实类名小写 `be1`，非 jadx 显示的 `Be1`）。
  - 进度文本：`onPlayerProgressChange(int,int)`（v2 / Gemini），v1 仍用 `updateTime(int,int)`。参数是 `int,int` 不是 patch 版的 `long,long`。
  - seekbar drawable：`seek.v3.a#draw(Canvas)`（对应 patch 版 `seek.v3.f`）。
- **video id 获取：8.96.0 原版与 patch 版链路不同，当前为探针实现**：
  - patch 版 `VideoDirectorObserver.onStart + getLogDescription` 在 8.96.0 不成立：原版接口方法是 `onItemStart`/`onItemWillChange`，director service 用 `addVideoPlayEventListener` 而非 `addVideoDirectorObserver`；`Video` 对象 `getId()` 返回对象 hash、`getDescription()` 返回 "video"，不含 aid/cid；`getLogDescription()` 在 `*PlayableParams` 类上。
  - 当前 `VideoIdProbe` 在容器创建时取 `getPlayerParams()` 拿 `PlayerParamsV2`，递归 dump 字段树按名匹配 `aid/avid/cid`，命中即回调。
  - **aid/cid 的精确字段路径待运行时确认**（探针日志会打印 `videoIdProbe:` 开头的字段 dump）。
- `player/` 模块已独立出来，用于承载播放器状态抽取和后续 seek 调用。
- `sponsor/` 模块已独立出来，用于承载协议请求、缓存和后续自动跳过决策。
- 进度文本 hook 已覆盖（8.96.0 原版类名/方法名）：
  - `com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget#onPlayerProgressChange(int,int)`
  - `com.bilibili.app.gemini.player.widget.progress.GeminiProgressTextWidget#onPlayerProgressChange(int,int)`
  - `com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget#updateTime(int,int)`
- 自动跳过已按 APK 行为调用 `IPlayerCoreService#seekTo(endMs, true)`。
- 已加按 `video/cid/uuid/start-end` 的防重复跳过记录，避免同一片段被进度回调重复触发。
- 播放器 toast 提示已通过 `PlayerToastBridge` 接入，反射构造 `PlayerToast` 并调用 `getToastService().showToast(...)`。
- 当前提示文案为简化直出：`已跳过 <category>`；APK 的分类本地化文案仍待对齐。
- 进度条标记已通过 `ProgressMarkerPainter` 接入 `seek.v3.a#draw(Canvas)`，按 `start/end/duration` 在 drawable bounds 上绘制片段区间。
- 当前进度条标记使用最近活动播放器 context 和固定黄色；APK 中按分类颜色绘制，后续需要对齐分类颜色配置。
- 剩余时间扣减已通过 `RemainingTimeFormatter` + `ProgressTextDecorator` 接入进度文本 hook，当前效果是文本后追加 `(<扣减后时长>)`。
- 这是按 APK `onPlayerUpdateProgressTextLong` 行为做的文本层复刻，具体文案和去重策略仍属于推测实现。

## 下一步验收

1. [x] 让仓库能执行 `gradlew.bat :app:assembleDebug`（已补 gradle wrapper 8.12，本地 `BUILD SUCCESSFUL`，产物 `app-debug.apk`）。
2. [x] 修完所有 Kotlin / `libxposed` API 编译问题（首次构建即通过，无编译错误）。
3. [x] 安装 debug APK，确认 LSPosed 识别模块并只作用域到 `tv.danmaku.bili`（日志确认主进程加载、子进程跳过）。
4. [ ] 打开 B 站播放页，抓 LSPosed 日志，确认至少命中一个播放器探针（首轮日志显示 5 个 hook 点全 ClassNotFoundException/NoSuchMethod —— 因对着 8.98.0 patch 版类名；已改 8.96.0 原版类名，待重测）。
5. [ ] 装机看 `videoIdProbe:` 日志，确认 aid/cid 字段路径，把探针换成确定读取。

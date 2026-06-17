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
- [x] 做基础设置页
- [ ] 对齐 APK 的完整设置体验（分类颜色、用户 ID 管理、更多高级项）

### 6. 提交与统计
- [x] 片段提交协议入口
- [x] 播放器标记按钮入口（轻量推测实现）
- [ ] 投票和反馈
- [ ] 用户统计
- [x] 服务器地址和基础分类配置
- [x] 本地 `userID` 持久化（按目标 App 私有 `SharedPreferences` 存储，属于推测实现）
- [x] 草稿式标记控制器（第一次标起点，第二次提交）
- [ ] APK 对齐的分类选择、手动时间编辑、预览后确认

### 7. 稳定性与可维护性收口
- [ ] 统一设置存储与读取层：抽象 SettingsRepository / SettingsSchema，集中默认值、校验、迁移和序列化
- [ ] 收紧设置通道：限制 exported Provider 暴露面，补调用方校验，降低敏感配置外泄风险
- [ ] 共用设置 UI：让 Activity 与弹窗共用一套设置行/分组构建器，避免双实现漂移
- [ ] 修正弹窗交互：导入/校验失败时保留弹窗与输入，不要静默关闭
- [ ] 为控制器增加显式生命周期收尾：退出播放器时关闭线程、清理状态、解除挂载引用
- [ ] 统一缓存职责：明确 client / repository 的缓存边界，消除 TTL 与失效逻辑重复
- [ ] 稳定菜单注入：去掉字段猜测式兜底，补更多宿主页面布局下的定位验证
- [ ] 建立诊断入口：设置读取、网络请求、提交失败可见化，减少只靠日志排查
- [ ] 建立单元测试和最小回归矩阵：覆盖 hash、解析、跳过决策、设置快照等核心逻辑

## 当前判断

已确认的最小可行链路：
1. 插件进入播放器进程
2. 拉取片段
3. 在播放时 seek

当前阶段重点已转向：
1. 收口设置入口和文档
2. 验证小窗/切集
3. 补投票、统计和更完整的设置能力

## 当前实现状态

- 入口采用 `libxposed` API 102 的 `XposedModule`。
- 当前兼容线已降到 `libxposed` API 101，避免 LSPosed API 102 激活失败。
- 模块元数据位于 `app/src/main/resources/META-INF/xposed/`。
- 当前实现已经不是探针版，主链路已落到真实 hook：
  - 播放器容器：`be1.j#onCreate(Bundle)` / `onStart()` / `onDestroy()`
  - 视频 ID：`VideoDirectorListener` 通过 `getPlayDirectorServiceV3()` + `addVideoDirectorObserver()`
  - 进度文本：`onPlayerProgressChange(int,int)` / `updateTime(int,int)`
  - 进度条：`seek.v3.e#draw(Canvas)` 命中
- `SponsorBlockClient` 已实现片段拉取、缓存、解析和提交 URL。
- `SponsorBlockController` 已实现自动跳过、去重、Toast、标记提交入口。
- `SettingsActivity` 已实现。
- `player/` 模块已独立出来，用于承载播放器状态抽取和后续 seek 调用。
- `sponsor/` 模块已独立出来，用于承载协议请求、缓存和后续自动跳过决策。
- 已验证日志包含：
  - `ModuleSettings loaded`
  - `marker-hook fired`
  - `showToast: 跳过: ...`
  - 主进程加载与子进程跳过

## 下一步验收

1. [x] 让仓库能执行 `gradlew.bat :app:assembleDebug`（已补 gradle wrapper 8.12，本地 `BUILD SUCCESSFUL`，产物 `app-debug.apk`）。
2. [x] 修完所有 Kotlin / `libxposed` API 编译问题（首次构建即通过，无编译错误）。
3. [x] 安装 debug APK，确认 LSPosed 识别模块并只作用域到 `tv.danmaku.bili`（日志确认主进程加载、子进程跳过）。
4. [x] 打开 B 站播放页，抓 LSPosed 日志，确认命中播放器 hook 和进度条标记。
5. [x] 确认 aid/cid 获取链路已从探针切换到 `VideoDirectorListener`。

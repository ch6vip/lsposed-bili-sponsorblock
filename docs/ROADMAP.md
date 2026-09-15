# 路线图 / 待办（ROADMAP）

> **目标口径（2026-09 起）**：唯一目标宿主 = `com.bilibili.app.in` **6.5.0（9110200）**，
> 安装包 `<APK目录>\bilibili_6.5.0.apks`。旧目标（`tv.danmaku.bili` 8.96 / 8.98 patch）只作行为蓝本。
> 类名与 Hook 依据见 [`docs/APK_6.5.0_ANALYSIS.md`](./APK_6.5.0_ANALYSIS.md)；真机验证步骤见 [`docs/DEVICE_PROBE.md`](./DEVICE_PROBE.md)。
>
> 状态标记：`[x]` 已完成，`[~]` 代码已实现/待真机确认，`[ ]` 待做，`[~]`（单列于「明确不做」节）明确不做，`[!]` 阻塞中。
> 编号：`M*` = 6.5.0 迁移任务，`T*` = 功能任务（历史沿用），`R*` = 架构稳定性任务（历史沿用）。

## 开发约定

- 目标 APK 是唯一的类名来源：改 Hook 前先用 `tools/dexscan` 核对类/方法是否还在。
- **所有宿主类名/方法名集中在 `app/src/main/kotlin/com/ctf/bilisb/host/HostTargets.kt`**，不要散落到各 Hook 文件。
- 每条 Hook 必须经过 `HookProbe` 记录命中/缺失，禁止「找不到就静默 return」。
- 不做完整 B 站客户端，只 Hook 播放器与相关逻辑。
- 关键逻辑必须加注释；APK 行为不明确时，代码和文档都标注为**推测实现**。
- 每完成一个功能模块或较大改动前，独立提交并推送 GitHub。
- **完成定义**：代码改完 + 文档同步 + 至少一种可复现验证方式 + 单独提交并推送。

## P0 - 6.5.0 迁移

> 共同验收前提：`.\gradlew.bat :app:assembleDebug` 通过，且 `docs/STATUS.md` 里每条 hook 的命中情况有真机日志证据。

- [x] **M0 设备侧准备**
      设备 Xiaomi （机型略）（arm64-v8a / Android 16），KernelSU + LSPosed 已就绪，
      模块作用域 `com.bilibili.app.in`（`staticScope=true`）。
      验收：logcat 出现 `Bili2233 module loaded in com.bilibili.app.in` ✅

- [x] **M1 作用域与进程收口**（真机已验证）
      `META-INF/xposed/scope.list` → `com.bilibili.app.in`；`Entry` 包名与子进程名单走 `HostTargets`。
      证据：`Bili2233 module loaded in com.bilibili.app.in`；`:ijkservice`/`:download` 打印 `Skip hooks in non-main process`。

- [x] **M2 设置与统计路径切换**（真机已验证，并修复了"设置改了不生效"）
      `SettingsSyncBridge.HOST_PACKAGE`、镜像候选、统计文件均由 `HostTargets.HOST_DATA_DIRS` 派生；
      另外修复两处设置链路缺陷：controller 重建判断恒假（阻塞级）、镜像文件优先遮蔽模块 App 改动。
      现在来源顺序为 **IPC 优先、JSON 镜像兜底**。
      证据：`read from file /data/data/com.bilibili.app.in/sponsorblock_settings.json`（旧版本）；
      设置弹窗可改可存。

- [x] **M3 播放器入口重建**（真机已验证 + 补齐销毁入口）
      Hook `PlayerSeekWidget3#bindPlayerContainer(tv.danmaku.biliplayerv2.f)`；core 三级来源（widget → 容器 → director 服务字段）
      并支持"core 未就绪时延迟补绑"。**新增** `onDetachedFromWindow` 作为播放器离开信号（清静音/倒计时/浮层/状态引用）。
      证据：`bindPlayerContainerCalled: PlayerSeekWidget3 <- PH1.e`、`player handle bound context=...`。

- [x] **M4 进度回调与核心服务适配**（真机已验证）
      进度回调 `G(int,int)`（参数实测就是 position/duration 毫秒）；core 的 `getDuration/getCurrentPosition` 名字保留；
      seek 用 `o(int,boolean)`；喂 controller 前做参数合理性校验；long 形态仅探针不参与决策。
      证据：`PlayerProgressTextWidget#G arg0=30746 arg1=1800000`。

- [x] **M5 aid/cid 链路重建**（真机已验证）
      `PlayDirectorServiceV3#j0(E0)` 注册代理；`E0#b(current, previous)` 只取 `args[0]`；
      `Video$e#z()` → `Video$a`(`DanmakuResolveParams`) 的 `a`=avid、`b`=cid；补发后清空 pending，防跨视频串台。
      证据：`z() = DanmakuResolveParams(avid=98935548, cid=168885122, spmid=united.player-video-detail.0.0)`；
      `segments fetched video=BV14741127BN status=200 count=9`。

- [x] **M6 进度条标记**（真机已验证，含全屏 padding 修正）
      绘制目标：`seek.v3.g`（Drawable 轨道层）/`seek.v3.f`（SeekBar 本体）；几何走纯函数 `MarkerGeometry`
      （越界不抛异常）；View 路径补 padding；薄轨道抑制按**实例**而不是 contextHash。
      证据：`source=progressDrawable+pad rect=Rect(27, 32 - 2466, 40)`（与实测轨道 y=1007–1015 对齐）。

- [x] **M7 「我的」页入口**（真机已验证）
      adapter 候选 `tv.danmaku.bili.ui.main2.mine.d`；去重按 adapter/List 身份；点击监听 + 弹窗；
      `uriRouter` 收紧筛选（原误挂 `attachBaseContext`）。
      证据：`Injected Bili2233 setting item at position 2`、`Showing Bili2233 settings dialog`。

- [~] **M8 设置入口点击链路（`bilisb://settings`）**
      结论：**不是必需**。注入项自带 `OnClickListener`（真机已验证点击直接弹出设置弹窗），
      路由拦截仅作 best-effort；`blrouter.Router`/`BLRouter` 在 6.5.0 被混淆，不再投入。
      现状：`uriRouter` 已收紧筛选并按"是否真拦到 URI"单独上报探针。

- [ ] **M9 真机回归矩阵（迁移收尾）**
      已完成：普通投稿视频的自动跳过、标记、Toast、我的页入口、设置读写。
      待补：**手动跳过 / 片段静音 / 倒计时取消 / 剩余时长扣减显示 / 小窗 / 切集 / 番剧(epid) / 深色模式 / 切账号 / 提交落库**。
      验收：`docs/STATUS.md` 更新为 6.5.0 的实测结论；版本号升到 0.6.0（versionCode 6）。

## P1 - 结构整理（沿用旧编号，迁移后执行）

- [ ] R1 设置存储收敛：抽出统一的 `SettingsRepository` / `SettingsSchema`，集中默认值、校验、迁移、序列化。
- [ ] R2 设置通道收口：收紧 exported Provider 暴露面，补调用方校验与敏感字段分级。
- [ ] R6 缓存职责单一化：明确 client / repository 的缓存边界，消除 TTL、失效、强刷重复。
- [ ] R7 菜单注入稳态化：去掉字段猜测式兜底，补更多宿主页面布局的定位与回归验证。
- [x] R3 设置 UI 共用：LauncherActivity 与 SponsorBlockSettingDialog 共用 SettingsScreenBuilder。
- [x] 宿主类名集中化：`HostTargets` + `HookProbe`（本次迁移新增，替代散落的硬编码类名）。
- [ ] 反射 Method 缓存：减少每个调用点重复 `getDeclaredMethod`；迁移后类名本就易变，缓存 + 缺失即上报更有价值。
- [ ] Hook 命中状态面板：把 `HookProbe.summary()` 显示到设置页「关于/状态」区，替代只看日志。

## code review 修复（2026-09-14，4 路并行 reviewer）

> 完整问题清单与逐条修法见 `docs/STATUS.md` 的「本轮 code review 修复」小节。
> 结论：阻塞 1 条、高 9 条、中 20+ 条、低若干，**已全部修复**；单测从 18 例扩到 97 例。

- [x] 设置热更新失效（controller 重建判断恒假）—— 阻塞级，已修
- [x] 6.5.0 缺播放器销毁入口 → 静音泄漏 / 离场后倒计时 seek / 状态泄漏 —— 已加 `onDetachedFromWindow` 入口
- [x] 缓存未命中时的清理盲区、观察者 previous 覆盖 state、pendingIds 串台 —— 已修
- [x] 提交接口改 POST（官方协议），写操作不自动重试 —— 已修（待真机确认落库）
- [x] 标记几何越界抛异常导致整帧消失 —— 抽纯函数 `MarkerGeometry`，已修 + 单测覆盖
- [x] 设置 IPC 优先、原子写、字段级 sanitize、主线程 IO 收敛 —— 已修
- [x] 我的页注入去重/点击绑定/uriRouter 误挂 —— 已修（去重按身份、筛选收紧）
- [x] UI 回调兜底与 Activity 状态保护 —— 已修
- [x] 缓存单调时钟 + 容量上限、统计原子写、草稿过期、`aidToBvid` 边界 —— 已修 + 单测
- [x] CI 跑单测 + 改用 wrapper —— 已修

### 遗留（下一轮）

- [x] **提交入口迁移完成**：播放器内悬浮的「标记/SB」按钮已移除（`SubmissionButtonInjector` 删除），
  提交能力改由播放器内「空降助手」面板的「提交片段」一行承载；
  入口 = 播放器右上角「⋯」→ 更多面板里的「空降助手」行（`MorePanelInjector` 注入）。
- [x] **播放器内「空降助手」面板**：片段信息 / 空降助手开关 / 提交片段 / 手动跳过 / 刷新片段 /
  服务信息 / 三个显示开关 / 最短片段时长 / 用户 ID，改设置立即生效（不再需要重进播放页）。
- [ ] Bundle 往返单测需要 Robolectric 才能真正执行（当前被 `Assume` 跳过）；`app/build.gradle.kts` 加 `testImplementation("org.robolectric:robolectric:...")` 后启用。
- [ ] 手动跳过按钮 / 倒计时浮层目前挂在 decorView（整屏右下角），详情页滚动、小窗场景位置不准 —— 建议挂到播放器 widget 的父容器并跟随 bounds。
- [ ] 文案硬编码中文（目标宿主是国际版）—— 抽到 `strings.xml` + `values-en`。
- [ ] 分类/高亮/actionType 常量在 4 处重复定义 —— 收敛到 `SponsorCategories` 单一来源。
- [ ] `ContentProvider` authority 在清单与代码两处硬编码 —— 改字符串资源或加一致性测试。
- [ ] 反射解析无缓存（`HookResolve` 每次 `getDeclaredMethod` 未命中靠异常控制流）—— 热路径可加 `ConcurrentHashMap` 缓存。
- [ ] `SponsorSegment` 含 `LongArray`（data class 按引用比较）—— 将来若作为 Map 键需换实现。
- [ ] `ModuleSettings.load()` 的进程内缓存语义与注释已统一，但 public API 仍建议只暴露 `reload()`。

## P2 - 可维护性与回归

- [ ] R9 / T7 自动化回归：覆盖 aid/bvid 转换、响应解析、设置快照、跳过决策；建立最小设备/版本矩阵清单。
- [ ] T8 提交体验增强：分类选择、手动时间编辑、预览后确认、投票/反馈按需拆分。
- [ ] T2 设置入口稳定性观察：记录首页/我的页刷新、切账号、深色模式下入口是否稳定出现。

## 功能任务（历史 T 列表）

- [x] T1 用户 ID 管理：设置页展示/复制/重置/导入，并同步到 Hook 端提交链路。
- [ ] T2 设置入口稳定性：不同布局/刷新/切账号/深色模式下的入口记录。
- [x] T3 片段缓存 TTL 可配置：替换固定缓存时间，并验证切集/回看刷新行为。
- [x] T4 调试日志收口：移除探针和高频日志，保留关键错误与状态日志（迁移期由 HookProbe 限频日志替代）。
- [ ] T5 错误处理增强：网络失败、提交失败、设置读取失败给出可定位日志和必要 UI 提示。
- [x] T6 内存占用优化：清理播放器销毁后的 context/state/cache 引用（迁移后需在新入口重建等价清理）。
- [ ] T7 单元测试覆盖：aid/bvid 转换、响应解析、设置快照解析、跳过决策。
- [ ] T8 提交体验增强（见 P2）。

## 场景验证

- [ ] 小窗播放：确认是否复用 `bindPlayerContainer` 入口；若为独立容器需补 hook。
- [ ] 切集：确认 `E0#b(current, previous)` 再次回调后按 bvid:cid 正确重拉。
- [ ] 首页/我的页刷新、切账号、深色模式：设置入口是否稳定出现。
- [ ] 缓存 TTL=0 / 过期 / 手动刷新：行为是否一致。
- [ ] 6.5.0 特有：`split_config.arm64_v8a` 只提供 arm64 库 → 只支持 arm64 设备/模拟器。

## 明确不做

- [~] unskip / undo：行为蓝本构建无该功能。
- [~] mute core 原生音量方法：改用系统 AudioManager 方案。
- [~] 同时维护 `tv.danmaku.bili` 与 `com.bilibili.app.in` 双目标：只保留国际版 6.5.0 目标线
      （旧类名只作为 `HostTargets` 里的候选兜底保留，不再单独验证）。

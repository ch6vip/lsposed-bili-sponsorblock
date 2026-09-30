# 路线图 / 待办（ROADMAP）

> **目标口径（2026-09-29 起）**：目标宿主 = `com.bilibili.app.in` **6.5.0（9110200）与 6.6.0（9130300）双版本**，
> 共用一张候选表（`host/HostTargets.kt`），全部旧候选保留。6.5.0 安装包 `<APK目录>\bilibili_6.5.0.apks`；
> 6.6.0 为真机 adb 拉取。旧目标（`tv.danmaku.bili` 8.96 / 8.98 patch）只作行为蓝本。
> 类名与 Hook 依据见 [`docs/APK_6.5.0_ANALYSIS.md`](./APK_6.5.0_ANALYSIS.md) /
> [`docs/APK_6.6.0_ANALYSIS.md`](./APK_6.6.0_ANALYSIS.md)；真机验证步骤见 [`docs/DEVICE_PROBE.md`](./DEVICE_PROBE.md)。
>
> 状态标记：`[x]` 已完成，`[~]` 代码已实现/待真机确认，`[ ]` 待做，`[~]`（单列于「明确不做」节）明确不做，`[!]` 阻塞中。
> 编号：`M*` = 宿主迁移任务（M0-M9 为 6.5.0，M10-M12 为 6.6.0），`T*` = 功能任务（历史沿用），`R*` = 架构稳定性任务（历史沿用）。

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

- [~] **M9 真机回归矩阵（迁移收尾）**
      已完成：普通投稿视频的自动跳过、标记、Toast、我的页入口、设置读写。
      增强组 2026-09-21：四件套 **安装命中**（`33/38 hit`）；分享 QQ 历史真机通过；
      IP 属地 REST `mobi_app` 改写已触发；gRPC 头写入 hook 已挂。屏幕效果仍待看。
      本轮收口：倒计时取消 / 手动跳过 / 时长扣减显示 2026-09-21 真机通过。**片段静音 / 提交落库本轮不做**。其余小窗 / 切集 / 番剧 / 深色 / 切账号仍未覆盖。
      以及增强组屏幕效果（评论 loc / 三连 UI / 首页切后台是否真不刷新 / 分享面板 QQ 行）。
      验收文档：`docs/STATUS.md`。版本号已升到 0.6.3（versionCode 9，`checkModuleProp` 守卫）。

## P0 - 6.6.0 适配（已完成，2026-09-29 真机闭环）

> 共同验收前提：`.\gradlew.bat :app:assembleDebug` 通过，且 `docs/STATUS.md` 里每条 hook 的命中情况有真机日志证据。
> 事实依据：[`docs/APK_6.6.0_ANALYSIS.md`](./APK_6.6.0_ANALYSIS.md)；过程与方法论教训：
> `.agents/notes/implemented/reverse/2026-09-29-host-660-adaptation.md`。

- [x] **M10 静态重定位 + 候选表更新**（真机已验证）
      六个失效点逐项用字节码核实，其中**两条交接结论被推翻**（观察者注册不是 `service.D0`；
      `ip1.h` 在 6.5.0 就已消失，无需重定位）。
      实际漂移：观察者注册 `PlayDirectorServiceV3#j0(E0)`→`l0(F0)`、当前视频 `D()`→`F()`、
      容器类型 `f`→`h`、面板刷新 `f0`→`e0`、int 进度 `G`→`J`、long 进度 `j0`→`g0`、
      moss 描述符 `kr1.*`→`xr1.*`。全部旧候选保留，不引入 versionCode 分支。
      证据：安装期探针 `34/39 hit`，与 6.5.0 基线（33/38）逐键对账**等价**，主链路 0 MISS。

- [x] **M11 观察者接口自洽解析**（真机已验证）
      发现**同名类跨版本互换角色**：6.5.0 的观察者叫 `E0`（`F0` 是媒体资源接口），6.6.0 反过来。
      「按候选名顺序取第一个能加载的类」在 6.6.0 会把代理实现到空标记接口上 → 静默收不到回调。
      解法：对每个候选接口试 `forTarget(服务, ADD 候选, iface)`，**能解析出注册方法的接口即正身**。
      实现在 `VideoDirectorListener.registerDirectorService`。
      证据：`directorService <- PlayDirectorServiceV3#l0 (接口 F0)`，换集时新 id 正常派发。

- [x] **M12 进度喂入重构 + 两处运行时回归修复**（真机已验证）
      6.6.0 三个 `PlayerProgressTextWidget` 不再实例化、`seek.v3.g#draw` 播放期间不逐帧走、
      `D0$c.run` 只在 seek 后打一炮 —— **没有可依赖的宿主 tick**。
      改为模块自持 500ms 轮询已绑定 handle 的 core（`getCurrentPosition/getDuration` 真名跨版本稳定），
      等价 6.5.0 tick 语义；handle 清理时轮询自停。
      另修两个模块自身回归：① `registerDirectorService` 去重点被审查改动挪到 `invoke` 之后 →
      反射调用触发 after 回调重入 → 6.6.0 在 `onCreate` 主线程内同步注册时无限重入（详情页黑屏 + 输入 ANR）；
      ② 「⋯」面板条目接口构建/绑定方法改名换位 → 代理按旧名分发返回 null → 宿主 `onCreateViewHolder` NPE 闪退，
      改为**按签名分发**且构建分支永不返回 null。
      证据：自然播放 2ms 精度自动跳过、统计 +102.3s、彩色标记/剩余扣减/「我的」页入口截图。

- [x] **M13 进度轮询的竞态与观测盲区**（代码已修 + 单测覆盖，待真机复验）
      `startProgressPoller` 的任务体内**两次读** `sponsorBlockController`（守卫一次、喂入一次）：
      `applySnapshot` 关闭并重建 controller 的间隙里，轮询线程可能读到已置 null 的引用 →
      任务经 `coreForContext == null` 分支自我 `remove+cancel`，而该 context 不会再 bind →
      本会话剩余时间**静默零跳过**。修法：任务体开头快照 controller/module 局部变量；
      `applySnapshot` 关闭 controller 时同步停掉该 hash 的 poller；补「有 handle 但无 poller」的一次性探针。

## P1 - 结构整理（沿用旧编号，迁移后执行）

- [ ] R1 设置存储收敛：抽出统一的 `SettingsRepository` / `SettingsSchema`，集中默认值、校验、迁移、序列化。
- [ ] R2 设置通道收口：收紧 exported Provider 暴露面，补调用方校验与敏感字段分级。
      （现状已由 `ProviderAuthorityTest` 固定下来：exported=true 且无读写权限；收紧时该测试会失败并提醒同步文档。）
- [ ] R6 缓存职责单一化：明确 client / repository 的缓存边界，消除 TTL、失效、强刷重复。
- [ ] R7 菜单注入稳态化：去掉字段猜测式兜底，补更多宿主页面布局的定位与回归验证。
- [x] R3 设置 UI 共用：LauncherActivity 与 SponsorBlockSettingDialog 共用 SettingsScreenBuilder。
- [x] 宿主类名集中化：`HostTargets` + `HookProbe`（本次迁移新增，替代散落的硬编码类名）。
- [x] 反射 Method 缓存：`HookResolve.forTarget` 已改为 `ConcurrentHashMap` 缓存命中项（未命中不缓存，
      因为类名/形状会随宿主改版变化，缓存空结果会让一次瞬时失败永久生效）。
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
- [x] Bundle 往返单测需要 Robolectric 才能真正执行（此前被 `Assume` 跳过）：
      `app/build.gradle.kts` 已加 `testImplementation("org.robolectric:robolectric:4.14.1")`，
      `SettingsCodecTest` 类级 `@RunWith(RobolectricTestRunner)` + `@Config(sdk=[34])`，
      Bundle 往返用例已真跑（175 例 0 失败 0 跳过）。
- [x] 面板本体行为测试：新增 `SponsorBlockPlayerSheetBehaviorTest`（Robolectric，5 例），
      覆盖主线程拒绝 / Activity finishing 拒绝 / already-showing 去重 / dismiss 回调 / Switch 开关回调 / 文案行渲染。
- [x] 手动跳过按钮 / 倒计时浮层挂载点：新增 `ui/OverlayAnchor.kt`，优先挂**播放器容器**
      （跟随播放器 bounds），容器未 attach/未测量/裁子 View/覆盖整屏时回落 decorView 并打探针
      （`manualSkipAnchorFallback` / `countdownAnchorFallback`）。判定逻辑 11 例单测；
      **真机位置仍待复核**（详情页滚动 / 小窗）。
- [x] 文案硬编码中文（目标宿主是国际版）—— 抽到 `strings.xml` + `values-en`。
      已完成：播放器面板全部行文案与子弹窗、跳过 Toast、手动跳过按钮、倒计时浮层、
      **设置页全部文案**、**9 个分类的显示名**，共 160+ 条资源
      （`res/values/strings.xml` + `res/values-en/strings.xml`）。
      测试：`StringsLocalizationTest`（6 例：键集合一致 / 英文无中日韩字符 / 占位符对应 /
      运行时按 locale 取到各自语言 / 分类显示名两个 locale 都取得到）+
      `SettingsScreenBuilderTextTest`（2 例源码级扫描：设置页不再有用户可见中文字面量、
      各段文案都确实引用了资源）。
      **顺带修掉两个缺陷**：
      ① `isIncludeAndroidResources` 此前未显式开启，Robolectric 读不到应用资源
      （`getString` 一律抛 `Resources$NotFoundException`），任何依赖文案的代码在单测里都会崩；
      ② `SettingsCodec.sanitizeCategory` 拿**显示名表**校验**category 字面量**
      （`"sponsor" in {赞助/恰饭=sponsor,…}` 恒为 false）→ 任何已保存的默认标记类别
      都会被静默改回 `sponsor`；现按 `SponsorCategories.ids` 判定。
      保留中文的地方（有意）：日志/探针/异常说明、`SheetStateFormatter.defaultStrings` 兜底、
      `SettingsKeys` 的 init 守卫报错文案。
- [x] 分类/高亮/actionType 常量在 4 处重复定义 —— 收敛到 `SponsorCategories` 单一来源：
      顺序 + 字面量 + 显示名 + 默认颜色 + 设置键全在一张表，`SettingsKeys` 的 init 守卫与
      `SponsorCategoriesTest`（8 例）保证各派生表不漂移（`ProgressMarkerPainter` / `ColorPickerDialog`
      的配色与色板改为从元数据派生）。
- [x] `ContentProvider` authority 在清单与代码两处硬编码 —— 新增 `ProviderAuthorityTest`（3 例）：
      读合并后的 manifest，断言清单 authority 含代码常量、`${applicationId}` 占位符已被替换，
      并把 exported/权限形状固定下来（收紧为签名级权限时该测试会失败并提醒同步 ROADMAP R2）。
- [x] 反射解析无缓存（`HookResolve` 每次 `getDeclaredMethod` 未命中靠异常控制流）—— 命中项已走
      `ConcurrentHashMap` 缓存；未命中**不**缓存（宿主改版后的一次瞬时失败不该被永久记住）。
- [ ] `SponsorSegment` 含 `LongArray`（data class 按引用比较）—— 将来若作为 Map 键需换实现。
- [ ] `ModuleSettings.load()` 的进程内缓存语义与注释已统一，但 public API 仍建议只暴露 `reload()`。

## P2 - 可维护性与回归

- [ ] R9 / T7 自动化回归：覆盖 aid/bvid 转换、响应解析、设置快照、跳过决策；建立最小设备/版本矩阵清单。
- [ ] T8 提交体验增强：分类选择、手动时间编辑、预览后确认、投票/反馈按需拆分。
- [ ] T2 设置入口稳定性观察：记录首页/我的页刷新、切账号、深色模式下入口是否稳定出现。

## P3 - 解锁番剧 / CDN 加速 / 缓存番剧（规划完成，待 Gate 确认）

> 完整规划见 [`docs/UNLOCK_PLAN.md`](UNLOCK_PLAN.md)；链路实证见 [`docs/UNLOCK_FEASIBILITY.md`](UNLOCK_FEASIBILITY.md)。
> 两个前置 Gate 未确认前不写实现代码：**G1 定位反转**（免责声明改写、账号风险自担声明）、
> **G2 许可证转 GPL-3**（参考实现为 GPL-3，研读后已不构成 clean-room）。里程碑 U0-U8，约 10-13 个工作日。

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
- [~] 片段静音真机回归 / 提交落库与 POST body 降级：2026-09-21 本轮明确不做。旁路已记录 `bsbsb.top` POST body 丢 userID、query 能进业务层。
- [~] 同时维护 `tv.danmaku.bili` 与 `com.bilibili.app.in` 双目标：只保留国际版 6.5.0 目标线
      （旧类名只作为 `HostTargets` 里的候选兜底保留，不再单独验证）。
- [~] 首页顶栏消息入口 / 底栏删「消息」tab / 底栏删「我的」tab：曾移植为 HomeTabHooks，
      6.5.0 上经嗅探→默认加载器→Compose 三漏斗三轮仍未生效，2026-09-19 按需求移除。
      不要复活。理由见 `.agents/notes/implemented/simplification/2026-09-19-remove-hometab.md`。

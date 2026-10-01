# 当前状态（STATUS）

> **目标口径（2026-09 起）**：模块以 `bilibili 6.5.0 / 6.6.0` / `com.bilibili.app.in` 为目标
> （6.5.0 安装包 `<APK目录>\bilibili_6.5.0.apks`；6.6.0 真机拉取，分析见 `docs/APK_6.6.0_ANALYSIS.md`）。
> 旧目标（`tv.danmaku.bili` stock 8.96.0 适配线、`Bili-v8.98.0-x1.27.3@bb_show.apk` 行为蓝本）降级为历史记录。

模块版本：**0.7.3**（Bili2233，applicationId `io.github.ch6vip.bilisb` / versionCode 13）
—— `0.7.0`（10）为 **6.5.0/6.6.0 双版本主链路真机跑通（2026-09-29）** 的版本；
`0.7.1`（11）加了轮询生命周期重构、34 例回归测试与结构债清理；
`0.7.2`（12）修跨包资源解析（真机验证通过，见「0.7.2 真机验证」一节）；
`0.7.3` 修 deferred bind 挂死（见下节「修复落地」，单测 192 例 0 失败，**未真机复核**）。

最近变更（0.7.0）：6.6.0 适配——观察者注册 `j0(E0)`→`l0(F0)`、当前视频 `D()`→`F()`、容器 `f`→`h`、
int 进度回调 `G`→`J`（但 6.6.0 文本控件不实例化，进度改由模块自持 500ms 轮询 core 喂入）、
long 回调 `j0`→`g0`、更多面板 `f0`→`e0`、moss 描述符 `kr1.*`→`xr1.*`；
观察者接口改为「能解析出注册方法的接口即正身」（E0/F0 跨版本撞名）；
修复审查引入的 `registerDirectorService` 重入回归（6.6.0 详情页黑屏）；
冷启动拉片段失败按 30s 冷却重拉。
第二轮全量审查修复（明文策略/NSC、TTL、unmute、去重等）已先行为基线 commit。

「B 站增强」现行四件套（移植自 BiliTamer,MIT，**6 个开关全部默认关闭**，控制中心「B 站增强」页按需开启）：
IP 属地 / 隐藏互动提示（三连、UP 气泡、投票）/ 首页不自动刷新 / 分享到 QQ。
首页顶栏消息入口与底栏删 tab 已按需求移除，不要复活（见 ROADMAP「明确不做」与 `.agents/notes/implemented/simplification/2026-09-19-remove-hometab.md`）。

最近真机验证：
- **2026-09-14**：主链路（加载 / aid-cid / 拉片段 / 自动跳过 / 标记 / Toast / 我的页 / 空降助手）在 `com.bilibili.app.in` 6.5.0 通过（见下）。
- **2026-09-21**：装 0.6.3 debug（versionCode 9）冷启动，`hook summary: 33/38 hit`；增强组见「B 站增强 2026-09-21」。
- **2026-09-29**：0.7.0（debug，versionCode 10）装上 6.6.0 宿主，安装期探针见「6.6.0 真机验证（进行中）」。

## 6.6.0 真机验证（2026-09-29，进行中）

宿主：`com.bilibili.app.in` 6.6.0 / versionCode 9130300（Xiaomi 23078RKD5C，Android 16，KernelSU + LSPosed）。

**安装期 HookProbe：`32/37 hit`（+2 个延迟 MISS，共 39 键）**。与 6.5.0 基线（33/38）逐键对账**等价**：

| 与基线的差 | 键 | 归因 |
| --- | --- | --- |
| 新增 OK | `cleartextPolicy`（NetworkSecurityPolicy#isCleartextTrafficPermitted） | 0.7.0 新增的明文策略 hook |
| OK→宿主侧消失 | `progressLong:GeminiProgressTextWidget`（6.5.0 是 `k0`） | 6.6.0 的 Gemini 控件不再声明 long 回调（23 个方法实测无 (J,J) 形态）；探针专用路径，不参与跳过决策 |
| OK→宿主侧消失 | `seekTrack:seek.v3.f`（6.5.0 OK） | 6.6.0 的 `seek.v3.f` 变成 Kotlin lambda 类（Function1），不再覆写 draw；主标记仍由 `seek.v3.g` 承担 |
| MISS（与基线一致） | `ip.kmpHeaderValue` / `ip.mossScope` / `ip.identityProvider` / `ip.restInterceptor` / `ip.restParams` | 6.5.0 基线同样 MISS 的旧路径；属地靠 REST 空间参数 + gRPC 头写入兜 |
| SKIP（与基线一致） | `legacyContainer`（8.96 兜底）/ `shareQqHostProbe`（6.4.0+ 探测） | 预期行为 |

**主链路（SponsorBlock）0 MISS**，全部按新候选命中：

```
directorService        <- PlayDirectorServiceV3#l0            （新候选 l0 / 接口 F0）
containerBinding       <- PlayerSeekWidget3 / control.PlayerProgressTextWidget#bindPlayerContainer  （参数 h）
playerTeardown         <- 两 widget 的 onDetachedFromWindow
seekTrack              <- seek.v3.g#draw
progressInt            <- base / Gemini / control 三个 widget 的 #J(J...)   （新候选 J）
progressLong           <- base #g0                                          （新候选 g0）
timeDeduction          <- setText(CharSequence, BufferType)（三 widget）
morePanelRefresh       <- gemini.ui.f#e0                                    （新候选 e0）
mineAdapterNotify/Bind <- tv.danmaku.bili.ui.main2.mine.d
uriRouter              <- 2 methods（IntentHandlerActivity）
增强四件套              <- hintTriple/hintFollowPopup/hintVote/noAutoRefresh(PegasusViewModel#x0)/
                          shareQqInject/shareQqTauth×2 全 OK
IP 属地主改写           <- ip.commonHeadersScope(header.b#b) + ip.grpcBinHeaderWrite(grpc.c#f) OK
                          （xr1.g/xr1.k 新提示就位；kr1.a/up1.a 形状不匹配按设计跳过）
```

**运行时回归（2026-09-29 下午～傍晚，全部通过）**：0.7.0 首版在 6.6.0 上「详情页/搜索页黑屏 + 输入 ANR」
**是模块自己的回归，不是宿主问题**。根因：第二轮审查把 `VideoDirectorListener` 的弱表
（`registeredHosts.add`，先登记后 invoke）改成 `observersByService` map 时，把去重点挪到了
`addMethod.invoke` **之后**——invoke 的是我们挂着 after 回调的 `l0` 本身（LSPosed 对反射调用同样生效），
回调重入 `registerDirectorService` 时去重不生效 → 无限重入卡死主线程。6.5.0 上同样的坑
只是注册路径不在主线程、未爆发。真机 MIUIScout 栈（`StatisticsService.onStart → l0 →
hook chain → registerDirectorService:178 invoke`）+ 模块日志「无任何 director 完成记录」定位。
另：排查中用 `pm disable-user` 做的「模块禁用对照」**全部无效**——LSPosed 读自己的模块库，
不看宿主侧包启用状态，hook 照常注入（模块日志 09:43:50 仍有 install summary）。

修复：登记提前到 invoke 之前 + 失败回滚（`VideoDirectorListener.registerDirectorService`）。

**6.6.0 进度喂入重构（第三个真机发现）**：修复黑屏后自动跳过仍不触发。真机逐层定位发现
6.6.0 的进度链路整体搬家：三个 `PlayerProgressTextWidget` 不再实例化（J/g0 回调零触发）、
seek bar 播放期间不逐帧走 `g#draw`（仅布局/seek 爆发）、`D0$c.run`（SeekService tick 派发器）
也只在 seek 后打一炮——**没有可依赖的宿主 tick**。最终方案：模块自持 500ms 轮询已绑定
handle 的 core（`getCurrentPosition/getDuration` 真名稳定），等价 6.5.0 tick 语义；
handle 清理时轮询自停；`seek.v3.g#draw` 钩子保留为 seek/布局时的补充喂入 +
彩色标记绘制。附带改进：冷启动首次拉片段失败（status=-1）后按 30s 冷却重拉
（此前一次网络抖动 = 整个会话零片段）。

**真机回归结论（BV14741127BN「30分钟秒表」样本，8 个 skip 片段，画面直读位置）**：

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| 自动跳过（自然播放） | ✅ | 播放到 704.593s（片段起点 704.591，2ms 精度）自动 `auto-skipped`，seek 到片段尾 806.903s |
| 跳过统计 | ✅ | `sponsorblock_stats.json` totalCount 39→40，interaction +102.3s = 片段时长 |
| 进度轮询 | ✅ | `seekTick feed #1→#51→#101→#151`，500ms 节奏，位置连续推进 |
| 彩色标记 | ✅ | 进度条多段彩色片段标记正常渲染（截图） |
| 剩余时长扣减 | ✅ | 时间文本 `19:02/30:00 (15:05)`，括号为扣减后剩余时长（截图） |
| 「我的」页入口 | ✅ | 「我的服务」组 Bili2233 格子注入，点击弹出设置弹窗，版本显示 0.7.0(versionCode 10)（截图） |
| 「⋯」面板行 | ✅（含一次闪退修复） | 首版滚动到行绑定时**宿主闪退**：6.6.0 把条目接口的构建/绑定方法改名换位（构建 `b`→`c`、绑定 `e`→`b`），代理按 6.5.0 名字分发漏接构建调用返回 null → 宿主 onCreateViewHolder NPE（dropbox `data_app_crash@1790663992496`）。改为按**签名**分发且构建分支永不返回 null 后：行渲染正常（样式对齐宿主卡片）、点击打开自研面板（片段信息 8 个片段/跳过 43 次实时显示）、宿主存活 |
| 安装期探针 | ✅ | `34/39 hit`（33/38 基线 + cleartextPolicy + seekTickDispatcher，−Gemini k0/−seekTrack:f 为宿主侧消失） |
| 片段拉取 | ✅ | `status=200 count=8`；网络失败按 30s 冷却自动重拉 |
| 切集/换视频 | ✅ | 合集自动连播换集时 `videoDirector FOUND` 新 id 正常派发、状态重置 |

未逐项复核（与 6.5.0 同口径不阻塞）：跳过 Toast 截图（代码路径与统计同分支，统计已落）；
「⋯」面板行视觉样式；增强四件套运行时文案（安装命中，机制未变）。

## 发布前收口（2026-09-29 晚，**0.7.1**）

> 主题：**把「已经真机验证过的成果」变成可发布的东西**，并补掉两类静默失效风险。
> 版本口径：`0.7.0`（versionCode 10）= 6.6.0 适配真机闭环的那一版；
> `0.7.1`（versionCode 11）= 本节新增的改动，**均未经真机复核**（单测 167 例 0 失败 + release 构建通过）。
> 单测 **167 例 0 失败**（新增 34 例）；`:app:assembleRelease` 出签名包；`v0.7.1` tag 触发 CI 发布 + 镜像同步。

### 1. 进度轮询的生命周期与竞态（`M13`，影响 6.6.0 主链路）

6.6.0 上**自动跳过完全依赖**模块自持的 500ms 轮询（宿主没有任何可依赖的进度 tick）。
这段代码原来有三处失效形态，全部是「日志安静地不再跳过」——与「服务端拉不到片段」无法区分：

| 问题 | 机理 | 处置 |
| --- | --- | --- |
| 任务体**两次读** `sponsorBlockController` | 守卫读一次、喂入再读一次；`applySnapshot` 关闭/重建 controller 的间隙里第二次读拿到 null → 任务经 `coreForContext == null` 自我摘表，而该 context 之后不一定还有 bind 来重启（`ensureRebindAfterTeardown` 走的正是没有 bind 的路径） | 一次 tick 只读一次，快照后全程复用 |
| 「表项在、任务已停」挡住重启 | 旧实现 `pollerFutures.remove(h)?.cancel(false)` 里 `remove` 返回 null 会短路掉 `cancel`；自停留下的死表项让后续 `start` 的 containsKey 守卫静默失败 | 抽出 `ProgressPollerRegistry`：起表清死表项、自停只摘「自己」那一项（`thisFuture` 身份比对）、`stop` 显式入口幂等 |
| 观测盲区 | 只有 `seekTick feed` 心跳的**沉默**能反推喂入停了 | 新增 `pollerMissingForHandle` 探针：**handle 在、poller 不在**这个组合必须在日志里有名字 |

同时把「起表路径」补全：`ensureRebindAfterTeardown`（补绑路径**没有** `bindPlayerContainer` 回调）
现在也会起表，`applySnapshot` 关 controller 时停表、按新 controller 的 handle 表重启。
不变式写死为 **有 handle ⇒ 有 poller**。

### 2. 回归测试补齐（新增 34 例）

| 测试 | 例数 | 守什么 |
| --- | --- | --- |
| `ObserverRegistrationGateTest` | 6 | 新抽出的 `ObserverRegistrationGate`：**登记必须先于 invoke**（它内部会重入）。用动态代理真造一次重入，断言宿主方法只被调用一次、登记失败回滚 |
| `ProgressPollerRegistryTest` | 6 | 幂等 start / stop 真停 / **自停后能重启** / 异常不杀周期任务 / 自停与重启交错不留死表项（真实定时器，非 mock） |
| `SponsorCategoriesTest` | 8 | 分类元数据单一来源：顺序、hex↔ARGB 往返、派生表一致、未知分类兜底 |
| `OverlayAnchorTest` | 11 | 浮层挂载点判定：尺寸阈值、attach 状态、`clipChildren`、覆盖整屏回落 |
| `ProviderAuthorityTest` | 3 | manifest 与代码 authority 同源、占位符已替换、exported/权限形状 |

### 3. 结构债清理

- **分类常量收敛**：顺序/字面量/显示名/默认颜色/设置键合并进 `model/SponsorCategories.kt` 一张表；
  `SettingsKeys` 的 init 守卫 + `ProgressMarkerPainter`/`ColorPickerDialog` 改为派生，消除四处手抄。
- **浮层挂载点**：新增 `ui/OverlayAnchor.kt`，手动跳过按钮与倒计时浮层优先挂播放器容器
  （跟随 bounds），容器不适用时回落 decorView 并打探针。**真机位置待复核**。
- **authority 一致性**：加 `ProviderAuthorityTest` 固化。
- **文档口径**：README 单测数（127→167）、ROADMAP 补 6.6.0 段落（M10–M13）、
  HANDOVER_6.6.0.md 标注结案、APK_6.6.0_ANALYSIS.md 区分「静态分析 §1–§5」与「真机探针 §6」。

### 4. 本轮未做（有意）

- **浮层新挂载点的真机位置复核**、增强四件套在 6.6.0 上的屏幕效果复核。
- **设置页文案的资源落点替换**（`SettingsScreenBuilder` 90+ 条）：资源已就位，替换留作下一步。

## 0.7.2 真机验证（2026-09-30 晚，6.6.0 宿主）

装机：`app-release.apk`（0.7.2 / versionCode 12，17:39 构建）与当前源码树构建产物 **SHA-256 一致**——测试对象就是工作区代码，无需重装。
设备 Xiaomi 23078RKD5C / Android 16 / KernelSU + LSPosed，宿主 6.6.0（9130300）。

**结论：0.7.2 的跨包资源解析修复验证通过，可提交发版；另发现一个 6.6.0 上的 deferred bind 挂死 bug（非 0.7.2 回归，见下）。**

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 冷启动加载 | ✅ | `Bili2233 module loaded in com.bilibili.app.in`；子进程 `Skip hooks in non-main process` |
| 安装期探针 | ✅ | `hook summary: 32/37 hit`，miss 全部是既录的 IP 属地旧路径（`ip.restInterceptor`/`ip.identityProvider`/`ip.restParams`/`ip.kmpHeaderValue`/`ip.mossScope`），skip 是 `legacyContainer`/`shareQqHostProbe`，与 6.6.0 基线逐键对账一致 |
| 主链路 hook | ✅ 0 miss | director `l0(F0)`、containerBinding、playerTeardown、seekTrack `g#draw`、progressInt `J`×3、progressLong `g0`、timeDeduction、mineAdapter、uriRouter、morePanelRefresh `e0` 全 OK |
| 设置弹窗文案（本版核心修复） | ✅ | 标题「Bili2233」、副标题、SponsorBlock/B 站增强/关于各区全部正常——**不再是 `res/anim/abc_fade_in.xml`**（截图 `.tmp-test/s2.png`） |
| 设置详情页文案 | ✅ | 启用/自动跳过/手动跳过/片段静音、数值输入、9 个分类显示名+说明、标记颜色区、界面显示区全部走资源正常渲染（截图 `.tmp-test/s4.png`/`s5.png`） |
| 拉片段 | ✅ | `segments fetched video=BV14741127BN status=200 count=8`；另一视频 `count=0`（服务端合法空） |
| 自动跳过 | ✅ | `auto-skipped position=0 segment=0-30015 category=intro`；恢复播放落片段内（29.94s）二次跳过也正常；5:00 sponsor 片段 `auto-skipped segment=300019-600014` |
| 跳过 Toast（走修复后的 ModuleStrings 链路） | ✅ | `showToast: 跳过: 赞助/恰饭 (300.0秒)`——资源解析出的正确文案 |

### 发现新 bug：deferred bind 在 6.6.0 上可能挂死（非 0.7.2 回归）

**现象**：20:02:43 重进播放页时 `bindPlayerContainer` 触发但 core 未就绪，走 deferred bind（`pendingBindRef` 挂起）；
之后 **4.7 分钟**内 1:59–3:00 的 selfpromo 片段播完未跳过、`seekTick feed` 零心跳；20:07:25 用户触碰播放器 UI 触发
`seek.v3.g#draw` 爆发 → `ensureDeferredBind` 补完 → 轮询启动 → 立即跳过当时所在的 sponsor 片段。行为与「服务端拉不到片段」无法区分。
**判定为「静默零跳过」第四种形态的依据**（排除替代解释）：视频画面自身走字证明在播且在片段内（连拍帧 77.4s → 142.5s → 176.3s，
后两者均在 119.4–180.0s 片段内）——排除「视频暂停」；窗口期内模块每 30s 仍在打设置日志——排除「进程死了」；
轮询心跳每 25s 一条、窗口内应出 ~11 条实际 0 条，且标记绘制的按实例探针在窗口内同样零命中、20:07:25 恢复——排除「有喂入但决策拒判」。

**根因**：`ensureDeferredBind` 的两个触发器（进度文本控件回调 `hookProgressCallback`、`feedProgressFromSeekDraw`）
在 6.6.0 正常播放期间**都是死的**——前者控件不实例化（0.7.0 已知），后者播放期间不逐帧 draw（仅布局/seek 爆发，
且控件面板不显示时连布局 draw 都没有）。bind 时 core==null 的 pending 没有任何自愈机制。
`probePollerMissingForHandle` 只覆盖「handle 在、poller 不在」，不覆盖「pending 在、没人触发」这个状态。

**修复落地（0.7.3）**：
1. **挂起即拉起自持轮询**：bind 时 core 未就绪，`pendingBindSlot.set` 后立刻 `startProgressPoller`——
   补绑不再等任何宿主信号；`progressPollerTick` 每个 tick 先 `tryCompletePendingBindFromTick`
   （core 三来源与 bind 路径同口径：widget → 容器 → director 服务），补绑成功则同一 tick 落到喂入分支
   （completeBind 里的 startProgressPoller 对活任务幂等，不会双开）。
2. **自停豁免**：tick 的「无 handle ⇒ 自停」放宽为「无 handle 且无本 context 的 pending 才自停」，
   否则挂起路径拉起的轮询会被第一条 tick 误杀；pending 挂超 10s 打 `pendingBindStuck` 探针，
   超 60s 放弃并自停（`pendingBindExpired`，host 八成已死）——不给死 pending 开永久空转的口子。
3. **teardown 清 pending 收窄**：`performTeardown` 改 `pendingBindSlot.clearIfHost(host)`（=== 身份），
   不再误清新页的 pending；`ensureRebindAfterTeardown` 与 controller 重建（applySnapshot）路径
   也补上 pending 的轮询重启，「有 pending ⇒ 有 poller」在全路径成立。
4. **状态机抽纯 JVM 类** `player/PendingBindSlot`（CAS 语义/身份清/sinceMs 不重置，8 例单测），
   另加 3 例 tick-registry 协作契约测试（真实定时器）。单测总数 189 → **192 例 0 失败**。
   **真机复核通过（2026-09-30 晚，6.6.0 宿主，0.7.3 release）**：进页（直接 bind）→ 退页 → 重进，
   复现出 `core not ready at bind time, defer binding context=84847536`，**518ms 后**（恰一个轮询周期）
   tick 内补绑完成 `player bound context=84847536`——期间无任何 seekDraw 爆发，纯自持轮询驱动；
   随后 `seekTick feed #51→#101` 位置连续推进（617s→642s），`pendingBindStuck/Expired` 零触发。
   对照修复前同场景挂死 4.7 分钟。进页首跳也正常（position=599245 落在 sponsor 片段内，
   `showToast: 跳过: 赞助/恰饭 (300.0秒)`，文案继续走 ModuleStrings 链路无误）。
**次生隐患**：`performTeardown` 会无条件 `pendingBindRef.set(null)`，不分 context——旧页延迟清理若落在新页
deferred bind 之后，pending 被抹掉，此后连 seekDraw 也救不回来（本次是 teardown done 恰好先于 deferral 才未触发）。
**严重度口径**：触发频率未量化（今日 ~3 次进页命中 1 次；6.5.0 上该状态每 500ms 自愈，是 6.6.0 特有死路）；
用户一旦触碰进度条 UI 即恢复。修复前可先加「pending 存活时长」探针量化真实频率。

**附带观察**：`ModuleSettings: IPC failed: Unknown authority` 每 30s 一条——模块进程未启动时的已知兜底路径（镜像文件读取成功），非回归。

## 解锁线事故记录：unlock-wip 误推送与撤下（2026-10-02）

文档路径更新时，命令链误带 `git push origin unlock-wip`——解锁实现代码（含 GPL-3
参考实现衍生部分）在公开仓库暴露约 1-2 分钟，随后删除远程分支（`git ls-remote`
确认 0 残留）。

**暴露内容评估**：实现代码 + 真实响应样本（playurl 响应不含任何账号凭据）+
STATUS 记录。**未暴露**：access_key/登录令牌（只在设备本地文件，从未入库）。
**被实际查看的风险**：趋近于零（分支存在约 1 分钟、无通知、个人仓库、无 fork）。

**处置**：远程分支已删除；本地 unlock-wip 完好；master 未受影响。
**不做**：GitHub support 清理孤儿对象（收益趋零）；将 STATUS 记录 cherry-pick
到 master（再推一次就多一次出错面）。

**流程根因**：连续两轮命令链夹带未预期副作用（python 变量笔误写错文件 + push
误串联）。规矩固化：**push/删除/部署类不可逆命令必须独立执行，不与任何其他
步骤串联**；python 批量 replace 必须 assert 命中。

## 解锁番剧 U4.7：真实受限内容端到端（2026-10-02，链路全通至业务层）

**触发条件就位**：用户真实受限场景 =《總之就是非常可愛 第二季（僅限港澳台地區）》
（国际版 App + 大陆网络，aid=784275927 / ep=744353 / cid=1150222464 / season=44960）。

**关键突破（三个旧假设全部被真实数据推翻）**：
1. 国际网关的受限响应是「**可用响应 + view_info.dialog(area_limit)**」而非错误形态
   ——实拍 3456B 样本钉死（dialog.type=area_limit、msg=抱歉您所在地区不可观看！）；
   判定已补 areaLimited 分支（优先于 UGC 短路），TONIKAWA 自然触发 verdict:RESTRICTED ✓
2. 宿主 getAccessKey 返回 **220 字符**长令牌（非 32hex）——服务器 valid_access_key
   曾整体拒绝国际版用户；服务器已补丁放宽并部署上线（th 旁路主站用户验证）
3. 签名身份必须按区域匹配：宿主 LibBili 只签自己体系 appkey，跨区域上游 -3；
   改为按区域本地签名（th=BstarA、cn/hk/tw=Android，凭据为公开客户端常量）

**端到端验证结果（自然触发，无 forceTest）**：
`verdict:RESTRICTED dialog=area_limit` → 运行时令牌 → 真实服务器（154.222.27.148，
BiliRoaming-Rust-Server）→ 上游 B 站正式业务响应 **`-10403 大会员专享限制`** →
降级放行原响应。区域路由已通（hk/tw 出口被上游认可为有效区域），唯一剩余门槛 =
**账号权益**（该内容在港澳台区域为大会员专享，测试账号非大会员）。

**结论**：漫游链路技术层面**全部打通**（判定/令牌/签名/路由/转发/上游业务层），
剩余为账号权益门槛（用户业务决策：大会员或免费内容的区域互换场景）。
unlock-wip 分支保留全部成果；access_key 等敏感配置只在设备本地。

## 解锁番剧 U4.6：最终诊断与收口（2026-10-01 凌晨）

**替换机制 100% 证明**：纯重序列化测试（newBuilder(reply).build() 零修改经钩子替换）
→ 播放器正常播放原始内容（19601B→19601B）。
**内容保真度缺口完整刻画**：合成 vodInfo 三轮保真度递增（最小 DASH → 补 stream_info
→ 真实形状 4 档+3 音频+全元数据+真实 CDN URL）全部被宿主接受但不渲染。
**结论**：6.6.0 播放器对 PGC 流的消费不读任何合成实例——需播放器消费路径的
多日级专项逆向。**解锁线正式收口**，全套基建/样本/测试保留（f5b4424），
复活条件 = 专项逆向排期。

## 解锁番剧 U4.5：真实 CDN 诊断（2026-10-01 凌晨，缺口已定位）

实验设计：正常播放采集真实 CDN URL（unlock:realUrl 实拍 estgcos/hwo1/coso1
四条流）→ mock canned 响应改用真实 URL → 强制受限重放。结果：
重构产物（含真实 B 站 fMP4）播放器接受并拉流，但**仍不渲染**；补充假设
（PGC supplement 载荷携带 video_info）也未解。

**收口结论**：链路七环节全部实证；缺口 = 6.6.0 播放器对 PGC 内容的流消费
不读重构实例的 unite.vod_info / supplement.video_info——需要更深的播放器
消费路径逆向（多日级）。按 Phase 0 预警，这是解锁线从「链路通」到「真能播」
的最后也是最深的一层。解锁线到此冻结（代码/测试全部落地），复活条件：
专项逆向排期或真实漫游服务器对照数据。

## 解锁番剧 U4：真机闭环验证（2026-10-01 凌晨）

按 [`docs/UNLOCK_PLAN.md`](UNLOCK_PLAN.md) 推进至 U4（G1/G2 维持发版前 Gate）。
U1 观测钩升级为解锁钩；U2 proto 管线；U3 漫游客户端+mock 服务器；U4 全部落地。

**链路验证证据链**（真机 Spy Classroom EP1，unlock_test_epid 强制受限路径）：

| 环节 | 证据 |
| --- | --- |
| 强制受限触发 | `unlock:forceTest ep=5189397` |
| 签名借宿主 | mock 收到含 `ts=`+sign 的完整签名查询（LibBili 形状解析生效） |
| 响应探活 | JSON code==0 解析（字符串匹配曾被 json.dumps 空格误判） |
| 响应重构被宿主接受 | `unlock:proxied area=cn quality=80 streams=1 audio=1` |
| 播放器拉流 | mock 日志 `206 /media/sample.mp4 + sample.m4a`（Range 分段双轨） |

**待收口**：canned 媒体在播放器内核不解码（缓冲不渲染）——mock 媒体格式工程问题
（fMP4 已用仍不解，疑播放器对 DASH 轨有额外要求），真实漫游服务器返回 B 站原生
格式时不存在，实播确认归入 U8。

**联调踩坑**（全记录在 commit b5eedd4/60f889b/本节）：主线程网络、宿主类名
playershared 独立包、Builder API 差异（改 wire bytes + parseFrom 路线）、
findMethod 重载通配、探活空格、mock 媒体相对路径/僵尸进程/Range。

## 解锁番剧 U1 观测钩（2026-09-30 深夜，装机验证通过）

按 [`docs/UNLOCK_PLAN.md`](UNLOCK_PLAN.md) 推进（G1/G2 已决策延后至发版前，U8 落地）。
U1 = `PlayerMoss.playViewUnite` 只读观测钩，不改任何行为。

- 装机（0.7.3+U1 release，versionCode 不变）后真机验证：延迟重试命中
  `hook ok: unlock:playViewUnite <- playViewUnite(2 args), executePlayViewUnite(1 args)`
  ——suspend(2 参含 continuation) 与 1 参两种形态都挂上；hook summary 升至 33/38。
- 探针工作正常：`unlock:reqFacts`（cid/season/ep/download）与
  `unlock:verdict:NORMAL_UGC`（UGC 视频请求正确分类，判定链走通）。
- 待验证：PGC（番剧）内容的 `NORMAL_PGC` 判定与受限场景——随日常使用观察探针积累，
  或 U2 proto 管线（area_limit 弹窗级判定）落地后一并验。

## 增强四件套 6.6.0 屏幕复核（2026-09-30 晚，**全部收口**）

背景：四件套自 0.6.3 起只验证过「安装命中」，屏幕效果从未在 6.6.0 复核；本轮同时核对
HostTargets 收编重构（6e2737d）的探针基线。开关经 root 直改三处存储（模块 prefs + 双镜像，
含 chown/chmod/restorecon）后冷启动宿主。

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 收编重构探针基线 | ✅ 逐键一致 | 冷启动 `hook summary: 32/37 hit`，延迟 miss（`ip.kmpHeaderValue`/`ip.mossScope` give up）与 0.7.3 基线相同——纯重构无行为差异 |
| IP 属地（评论区） | ✅ 屏幕+日志双证据 | 评论区「广东」属地标签可见；日志 `reply.v2 scope 武装 → grpc write fired x-bili-device-bin → 改写生效`（21:07:47） |
| IP 属地（空间页） | ✅ 屏幕+日志双证据 | 空间页「IP属地：广东」可见；`space page open -> identity armed (15s window)` + 本会话 `space rest params rewritten mobi_app android_i -> android` |
| 首页不自动刷新 | ✅ 屏幕证据 | HOME 切后台 5s/8s 两次回前台，feed 卡片**文本级逐张一致**（再续一单/网络热门长虫/比电影更夸张/野比大雄…）；`noAutoRefreshBlocked` 探针 0 次 = 宿主本次未发起 AUTO_BACK 尝试（与 2026-09-21 观察一致），用户可见行为符合预期 |
| 分享 QQ | ✅ **用户真机确认** | 面板渠道 + 拉起手 Q 实测没问题（机主自测） |
| 隐藏互动提示 | ✅ 部分 + 两个限制如实记录 | UP 气泡：进页 4s/8s 两帧无气泡（该 UP 未关注、触发条件具备；单侧证据）。三连提示：hook 命中 + 长按中途帧无提示文案；**完整触发会消耗硬币/改账号三连状态，未做**。投票：样本视频无互动弹幕，未复现 |
| 深色模式设置弹窗 | ✅ 可读性通过 | 深色页面上标题粉底白字、内容卡片文字清晰、无「黑底黑字」（此前 code review 修的硬编码色问题确认已修）。弹窗内容区为浅色卡片样式，与深色页面有轻微视觉割裂但完全可读 |

**四件套全部收口**：IP 属地 / 首页不自动刷新 / 分享 QQ / 隐藏互动提示（部分项带如实记录的限制）。
设备状态已还原：深色跟随系统与手动深色开关均关回（恢复联动写入）、`cmd uimode night no`。

测试方式备忘：评论区属地老评论（2022-03 属地上线前）无标签属正常；gRPC 改写探针是
进程内一次性日志，冷启动后第一条评论请求才会打出；B 站不跟随系统 uimode，
深色模式要在应用内「设置→深色设置」开（跟随系统联动会把手动手关写成 on，恢复时两个都要看）。

## 文案国际化（2026-09-30，随下一版发布）

目标宿主是**国际版**，但界面文案长期是硬编码中文字面量。本批抽出「播放器内用户可见」的文案：

| 项 | 内容 |
| --- | --- |
| 资源 | `res/values/strings.xml` + 新增 `res/values-en/strings.xml`，120+ 条（通用/控制中心/增强页/状态区/设置页/分类说明/颜色选择/面板/浮层/Toast） |
| 落点 | `SponsorBlockPlayerSheet`（全部行文案与子弹窗）、`PlayerToastBridge`、`ManualSkipButton`、`SkipCountdownOverlay` |
| 结构 | 新增 `ui/SheetStrings.kt`：`SheetStateFormatter` 保持纯 JVM（可单测），文案经该接口注入；生产实现 `AndroidStrings` |
| 测试 | 新增 `StringsLocalizationTest` 5 例：键集合一致 / 英文无中日韩字符 / 占位符一一对应 / **运行时**按 locale 取到各自语言 |

**顺带修掉一个测试基础设施缺陷（重要）**：`isIncludeAndroidResources` 此前未显式开启，
Robolectric 单测里读不到应用资源 —— 包名是 `org.robolectric.default`、
`getString(R.string.module_name)` 直接抛 `Resources$NotFoundException`。
也就是说**任何**依赖 `getString` 的代码在单测里都会崩；本轮改文案时面板 `show()` 整体失败才暴露。
现已显式打开（AGP 8 默认值本就该是 true，写出来防止被静默改掉）。

单测总数 167 → **175 例 0 失败**。

**第二批（设置页 + 分类显示名）**：`SettingsScreenBuilder` 的 90+ 条文案与 9 个分类显示名
全部改走资源（分类显示名新增 `SponsorCategories.displayName(context, category)`，
规范中文名保留给日志与存储）。新增 `SettingsScreenBuilderTextTest` 做源码级防回归扫描
（设置页不再有用户可见中文字面量 + 各段文案确实引用资源）。

**又发现并修掉一个静默失效**：`SettingsCodec.sanitizeCategory` 用**显示名表**校验
**category 字面量**（`"sponsor" in {赞助/恰饭=sponsor,…}` 恒为 false），
导致用户选择的「默认标记类别」在每次回读时都被静默改回 `sponsor` —— 界面无任何提示。
现在按 `SponsorCategories.ids` 判定。

保留中文（有意）：日志/探针/异常说明、`SheetStateFormatter.defaultStrings` 兜底、
`SettingsKeys` 的 init 守卫报错文案。

## 第二轮 code review 修复（2026-09-14，4 路并行 reviewer，42 条）

> reviewer 分别负责：入口/Hook、业务+网络、设置+构建、UI+测试；共报 42 条真实问题（0 阻塞、4 高、17 中、21 低），已全部修复。

**高**

1. `SettingsWriter.syncSnapshotToModule` 同进程 provider 回写自激死循环（listener 未被 internalWrite 覆盖 + SharedPreferences 值相同也回调）→ provider 写入外层打 internalWrite 标记 + provider 端值相同跳过 apply。
2. `module.prop` 缺 name/versionName/versionCode/author/description → 补全（与 build.gradle.kts 的 0.6.0/6 对齐）。
3. 「我的」页入口用**组内索引**当 RecyclerView 全局 position（多 group 时点击绑错行）→ 按各 group itemList 尺寸累加还原拍平规则计算全局位置，探针记录 `mineMenuFlatPos`。
4. `lastBoundContainer` 死字段强引用宿主容器 → 删除；`MorePanelInjector` 的 `kotlin.Unit` 用宿主 CL 加载可能失败 → 优先模块 CL，加载不到打 probe。

**中**

5. `SkipStatsStore` init 预加载自己 await loadLatch 白等 2 秒（且卡 UI 线程首次统计读取）→ init 直接 loadFromDisk；readFromDisk 的 exists/canRead 纳入 runCatching；loadFromDisk try/finally countDown。
6. `AudioMuteController` 全局 `mutedByUs` 在多播放器场景互相踩（静音反复抖动/泄漏）→ 改按 contextHash 记账（mutedContexts），只有所有 context 都不需要静音才 unmute 流。
7. `SponsorBlockController.onProgress` 无条件回写 state 会用旧视频 state 覆盖新视频（跨线程时序）→ 改 `ConcurrentHashMap.replace(key, old, new)` 条件回写。
8. 200 但解析失败的坏响应（截断/CDN 错误页）被当「无片段」缓存满一个 TTL → FetchResult 加 `parseFailed` 标志，解析失败只返回不缓存。
9. `VideoDirectorListener` 字段扫描兜底把任意 long 字段误当 aid/cid（会拉错视频的片段）→ 加量级校验（1e7~1e15 且 aid≠cid），不合量级宁可放弃。
10. 重看/循环播放同一视频时 skippedSegmentsByVideo 的 key 不清 → 片段永不跳过；onVideoIds 对同一 videoKey 也清空该桶。
11. `manualSkipTo` 在 duration 未知且 endMs<0 时 coerceIn(min>max) 抛异常 → 先夹负数再夹时长。
12. 提交成功判定 `statusCode == 200` 漏掉 201/204 → 改用 `result.isSuccess`。
13. `SubmissionDraftController` 草稿 TTL 用墙钟（回拨后过期永不生效）→ 改单调时钟（与 Repository 一致，JVM 单测退化墙钟）。
14. `BiliSponsorBlockHooks` teardown 只挂第一个命中的 widget 类 → 每个成功挂 bind 的类都挂 detach（onPlayerLeft 幂等）。
15. `ensureDeferredBind` check-then-act 竞态 → `pendingBindRef` 改 `AtomicReference` 抢占式 getAndSet(null)，校验失败放回。
16. `SkipCountdownOverlay` deadline 溢出（超大 totalMs 回绕负数 → 0 秒直接跳过）→ totalMs 饱和夹取（上限 10 分钟）。
17. 抑制窗口/Toast 节流用墙钟会被 NTP 回拨拉长 → 统一 `SystemClock.uptimeMillis`。
18. `SettingsWriter` 每次打开设置页新建（监听器/线程累积）→ BiliSponsorBlockHooks.updateSetting 双检锁单例化；「统计」区块在模块 APK 进程永远显示 0 → 改为提示文案（数据在宿主进程）。
19. `LauncherActivity` 返回键不触发 EditText 失焦，数值改动丢失 → onPause 递归 clearFocus。
20. `SponsorBlockSettingDialog` 「返回」按钮未先 dismiss → 统一 dismiss 后再导航（避免双弹窗叠加）。
21. `SponsorSegment` 含 LongArray，data class equals/hashCode 用引用比较 → 自定义按内容比较。
22. `MaxHeightScrollView.onMeasure` 丢弃父约束 → maxHeight 与父约束取 min。
23. 面板 `editNumber` 只夹下限（1e30 原样回调）→ 夹到 [0, 600]（与 MAX_SKIP_COUNTDOWN_SECONDS 对齐）。

**低**

24. `MineMenuInjector` install 失败只打 e.message → 记类名+堆栈+probe；回调异常静默吞 → 留 warn；`setField` 按字段实际类型转换（Long→Int），失败留日志。
25. 进度文本热路径每次编译 Regex → 提为常量（3 个）。
26. `HookProbe.first` 计数器无界增长 → 记满后移除键。
27. `flushPendingIds` 不校验时效（上一会话残留 id 补发到新 context）→ 附带采集时刻，超 10s 丢弃。
28. `onPlayerDestroyed` 在空 map 时 close() 永久关闭 executor → close 只由显式生命周期入口调用。
29. `refreshSegments` 与 onVideoIds 并发重复请求 → 走同一 inFlight.add 防并发。
30. `MarkerGeometry` 「至少 1px」与实现矛盾（贴右边缘时 0 可视宽度）→ 贴边时改画 [right-1f, right]，测试同步修正。
31. `RemainingTimeFormatter` 按原始长度过阈值、按 clamp 后扣减，边界不一致 → 先 clamp 再过阈值。
32. 数值输入 "60" 回显 "60.0" → 整数值去掉 ".0"。
33. 默认服务器字面量两处 → `SponsorBlockConfig.DEFAULT_SERVER_ADDRESS` 单一来源。
34. 测试文件名 SponsorBlockPlayerSheetTest 名不副实（全测 SheetStateFormatter）→ 改名 SheetStateFormatterTest。
35. CI setup-gradle 指定 gradle-version 后又调 wrapper（冗余）→ 删掉让 wrapper 生效。
36. 其余：`PlayerSheetState.playheadInsideSegment` 注释对齐、ToastThrottle 注释对齐、BiliSponsorBlockHooks 595 注释与容差实现对齐、Entry/HostTargets legacy 兜底注释标注。

## 结论

- 目标宿主 6.5.0 上主链路已经端到端可用：加载 → 取 aid/cid → 拉片段 → 自动跳过 → 进度条标记 → Toast → 我的页入口 → 提交。
- 本轮 code review（4 个 reviewer 按模块并行）共发现 60+ 条问题，按严重度依次修复，
  重点是：**播放器离开时无销毁入口导致的静音泄漏/倒计时越界 seek/状态泄漏**、
  **设置改了不生效（两处独立 bug）**、**提交接口协议不符**、**标记绘制几何越界抛异常**。
- 仍有未在真机验证的功能项（小窗/切集/番剧）。片段静音与提交落库本轮不做。
  Bundle 往返单测已用 Robolectric 真跑。

## 6.5.0 真机验证结论（2026-09-14）

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| M1 作用域/主进程/子进程跳过 | ✅ | `Bili2233 module loaded in com.bilibili.app.in`；`:ijkservice`/`:download` 打印 skip |
| M2 设置链路 | ✅ | `read from file /data/data/com.bilibili.app.in/sponsorblock_settings.json`；设置弹窗可改可存 |
| M3 播放器入口 | ✅ | `bindPlayerContainerCalled: PlayerSeekWidget3 <- PH1.e`；core 延迟补绑成功 |
| M4 进度回调 | ✅ | `PlayerProgressTextWidget#G arg0=30746 arg1=1800000` |
| M5 aid/cid | ✅ | `z()` = `DanmakuResolveParams(avid=98935548, cid=168885122, spmid=united.player-video-detail.0.0)` |
| 拉取 | ✅ | `segments fetched video=BV14741127BN status=200 count=9`（修掉服务端不接受的 `videoID/cid` 参数） |
| 自动跳过 | ✅ | `auto-skipped ... segment=0-30015 intro`、`119447-180017 selfpromo`、`300019-600014 sponsor`、`704591-806903 interaction` |
| M6 进度条标记 | ✅ | `source=progressDrawable+pad rect=Rect(27, 32 - 2466, 40)`，与实测轨道 y 1007–1015 对齐 |
| M7 我的页入口 | ✅ | 注入 + 点击监听 + `Showing Bili2233 settings dialog` |
| 播放器「更多」面板入口行（空降助手） | ✅ | 注入 `morePanelItems size=18` → `morePanelInjected <- size=19`；点击后 `sheetContextHash host=233035869`。UI 几何按截图量值对齐（卡片 16dp 外边距 / 间距 16dp / 图标 20dp / 标题 15sp），18:31 装机复验通过 |
| Toast / 提交 | ✅ | `showToast: 跳过: 开场动画 (30.0秒)`。注:播放器内 SB 提交按钮入口已移除(commit e9c2c32),「标记/取消标记」记录为历史验证,提交链路保留等待新入口 |

### B 站增强 2026-09-21（0.6.3 debug / Xiaomi 23078RKD5C / Android 16）

冷启动宿主主进程后 `hook summary: 33/38 hit`。设置走宿主镜像
`/data/data/com.bilibili.app.in/sponsorblock_settings.json`（模块进程空闲时 IPC `Unknown authority`，属已知兜底路径）。

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| 隐藏一键三连 / UP 气泡 / 投票 | ✅ 安装命中 | `hintTriple:setPrompt` / `hintFollowPopup` / `hintVote` hook ok。回调是否改 UI 未截图复核 |
| 首页不自动刷新 | ✅ 安装命中 | `noAutoRefresh <- PegasusViewModel#y0 (has-content guard)`。HOME→回前台未打出 `auto refresh blocked`（可能未走到 AUTO_BACK，或列表已是空状态放行） |
| 分享到 QQ | ✅ 安装命中 + 历史真机 | `shareQqInject` / `shareQqTauth:shareToQQ` hook ok；此前 tauth→ACTION_SEND 真机通过。本次未再点分享面板 |
| IP 属地（REST 空间参数） | ✅ 运行时改写 | `ip: space rest params rewritten mobi_app android_i -> android` |
| IP 属地（gRPC 头写入） | ✅ 安装命中 | `ip.grpcBinHeaderWrite <- kntr.base.moss.ignet.impl.grpc.c.f`；本次未打出 `grpc write fired` / `改写生效` |
| IP 属地屏幕显示 | 未复核 | 评论/空间页 loc 文案未截图 |
| ip.kmpHeaderValue / ip.mossScope / ip.identityProvider / ip.restInterceptor / ip.restParams | ❌ miss | 6.5.0 上这些旧路径不存在或形状不匹配；属地靠 REST 空间参数 + gRPC 头写入兜 |

明确不再投入：首页顶栏消息入口 / 底栏删「消息」tab / 底栏删「我的」tab。

### M9 主链路边角（2026-09-21）

| 能力 | 结论 | 证据 |
| --- | --- | --- |
| 倒计时取消浮层 | ✅ 真机通过 | 用户手动：进片段出现「N 秒后跳过 [取消]」，点取消后不再 seek |
| 手动跳过按钮 | ✅ 真机通过 | 用户手动：进片段出跳过按钮 / 面板选段，点后 seek 到末尾 |
| 剩余时长扣减显示 | ✅ 真机通过 | 用户手动：进度文本扣减后的剩余时长符合预期 |
| 片段静音 | 本轮不做 | 代码在，未真机验。默认关 |
| 提交落库 | 本轮不做 | 面板两次标记因播放暂停落在同一 `position=1536589`，草稿判零长未发请求。旁路：`bsbsb.top` POST **body** 回 `400 No userID provided`；POST **query** 能进业务层（同分类已投满时 403）。不改降级、不继续验 |

仍未覆盖（也不在本轮）：小窗、切集、番剧/OGV、深色模式、切账号。

## 本轮 code review 修复（按严重度）

> 4 个 reviewer 分别负责：入口/Hook、业务+网络、设置+构建、UI+测试；所有结论都已复核。

**阻塞 / 高**

1. 设置热更新失效（`ensureSettingsLoaded` 先赋值后比较，条件恒 false）→ 改为保存旧快照再比较。
2. 6.5.0 没有销毁入口 → 静音不解除、倒计时离开后仍 seek 并记统计、按钮/浮层残留、状态泄漏。
   新增 `PlayerSeekWidget3#onDetachedFromWindow` 作为**播放器离开信号**（探针 `playerTeardown`）。
3. 缓存未命中时 `onProgress` 提前 return → 清理盲区（跨集静音/倒计时/按钮串味），改为空片段也走清理分支。
4. 观察者回调把 `b(current, previous)` 的 previous 也 dispatch → 用上一集 aid/cid 覆盖状态；改为只处理 `args[0]`。
5. `pendingIds` 补发后不清空 → 上一个视频的 id 被注册到新 context；改为补发即清空 + 离开时清理。
6. 提交接口用 GET + `videoID/cid`（服务端 400）→ 改为 `POST /api/skipSegments`（官方协议，405/501 才降级 GET），
   成功判定 `200..299`，写操作不自动重试。
7. `ProgressMarkerPainter` 的 `coerceIn` 在片段越界时抛异常且被静默吞掉（整帧标记消失）→
   几何计算抽成纯函数 `MarkerGeometry`（任何非法输入都安全跳过），`hookAfter` 失败改为 warn 日志。
8. 我的页 `uriRouter` 误挂 `attachBaseContext`、对原始返回类型返回 null 有崩宿主风险 → 收紧筛选（参数必须 Uri/String、
   写明方法名白名单、按返回类型决定是否拦截），并把探针拆成"装了 hook"与"真拦到 URI"。
9. 我的页注入去重只看当前 List → 改为按 adapter/List 身份去重 + 注入日志带 adapter/list 身份。
10. UI 回调（点击/ticker/长按）无兜底 → 全部 `runCatching` + 主线程 post + `isFinishing/isDestroyed` 保护。

**中**

11. 设置"文件优先于 IPC"导致模块 App 改动被旧镜像遮蔽 → 改为 **IPC 优先、文件兜底**，模块进程也把设置推给 provider。
12. 主线程做同步 IPC + 文件 IO → 挪到单线程 executor；镜像文件改 `tmp + renameTo` 原子写。
13. `putSettings` 无字段校验 + 调用方一致性 → 加 sanitize（userId 32hex、serverAddress http(s)、数值 clamp）与包名/uid 一致性校验。
14. 提交/统计/缓存：草稿过期、统计原子写 + 备份、缓存改单调时钟 + 容量上限、`skippedSegments` 按视频分桶。
15. 片段健全性：过滤零长/负起点/Infinity/超时长片段；`seekTo` 前夹取位置；倒计时结束前校验当前位置避免回跳。
16. `aidToBvid` 对 `aid<=0` 崩、`aid>=2^51` 数组越界 → 加保护 + 补边界真值单测。
17. 进度回调喂 controller 前做参数合理性校验（`0<=pos<=dur` 且 `dur>0`），避免混淆同名重载用错数值。
18. `UserIdentityStore` 每次点击都跨进程 IPC → 进程内缓存 + 同步保护 + `apply()`。
19. `SponsorBlockSettingDialog` 未校验 Activity 状态（BadToken 崩宿主）→ 加保护与防叠加。
20. 深色模式下标题硬编码 `#212121` 不可读 → 改为解析主题色。

**低**：分类/高亮常量重复、Toast 节流、时间扣减文本保留 span、进度文本 miss 探针、`forTarget` 注释与实现对齐、
探针键按类/实例拆分、CI 跑单测并改用 wrapper、删除死代码。

**测试**：从 7 类 18 例扩到 **12 类 97 例（0 失败，1 skip）**，新增纯几何/抑制窗口/节流/sanitizer/边界真值等覆盖。

## 设备现状（2026-09-21 复测）

| 项 | 值 |
| --- | --- |
| 设备 | Xiaomi 23078RKD5C（corot），arm64-v8a，Android 16（SDK 36） |
| 目标宿主 | `com.bilibili.app.in` versionName 6.5.0 / versionCode 9110200 ✅ |
| 模块 | `io.github.ch6vip.bilisb` 0.6.3 / versionCode 9（debug） |
| Root | KernelSU（`me.weishu.kernelsu`）；`adb shell su -c id` → uid=0 |
| LSPosed | ✅ Zygisk 模块 `zygisk_lsposed`（LSPosed IT v2.2.0-it / 7885）。**没有**独立 `org.lsposed.manager` 包，这是寄生式管理器，不是「没装」 |
| 模拟器 | SDK 只带 x86_64 镜像（android-35），而宿主只提供 arm64 库 → 必须真机 |

## 6.5.0 静态分析结论（离线可复现）

### 仍然可用（类名/方法名保留）

- 进度文本三类的 `setText(CharSequence, TextView$BufferType)`
- `getPlayerCoreService()`（→ `tv.danmaku.biliplayerv2.service.D`）
- `D.getDuration():int`、`D.getCurrentPosition():int`、`D.getRealDuration():int`（名字保留）
- `com.bilibili.lib.homepage.mine.MenuGroup` / `MenuGroup$Item` 全部字段（`id:J`/`title`/`uri`/`icon`/`needLogin`/`redDot`/`localShow`…）
- `tv.danmaku.bili.ui.intent.IntentHandlerActivity`、`com.bilibili.lib.blrouter.*` 框架类
- `service.Video$a`（`DanmakuResolveParams`）字段：`a`=avid、`b`=cid、`d`=epid、`e`=seasonId、`f`=page

### 已确认失效 / 改名（迁移必改）

| 旧适配线 | 6.5.0 |
| --- | --- |
| 作用域 `tv.danmaku.bili` | `com.bilibili.app.in` |
| 容器 `be1.j#onCreate/onStart/onDestroy` | 不存在同类；入口改为 `PlayerSeekWidget3#bindPlayerContainer(tv.danmaku.biliplayerv2.f)` |
| `onPlayerProgressChange(int,int)` | 混淆为 `G(int,int)`（另有 `j0(long,long)`） |
| `core.seekTo(int, boolean)` | `o(int, boolean)`；`seekTo(int)` == `o(pos, false)` |
| `VideoDirectorObserver` + `addVideoDirectorObserver` | `service.E0` + `service.A#j0/#z0` |
| `getLogDescription()` | 不存在 |
| `seek.v3.q` / `seek.v3.e` 的 `draw(Canvas)` | `q` 是 LayerDrawable（不覆写 draw）、`e` 是 lambda；覆写 draw 的是 `seek.v3.g`（实色矩形，首选）、`seek.v3.f`（AppCompatSeekBar）、`seek.v3.a`（热度曲线，勿用） |
| `HomeUserCenterAdapter` | 混淆为 `tv.danmaku.bili.ui.main2.mine.d` |
| `blrouter.Router` / `BLRouter` | 类名不存在（框架仍在） |
| 设置镜像/统计路径 `/data/data/tv.danmaku.bili/...` | `/data/data/com.bilibili.app.in/...` |

### 待真机探针确认（探针版已埋点，日志关键字见 docs/DEVICE_PROBE.md）

> 口径：**「语义」已经静态定死，「是否真触发」只能运行时看**。下面全是后者。

- 进度回调 `G(position, duration)` 是否真的被调用 → `[probe] progressCallbackArgs`
  （顺序/单位已由字节码确认：参数直接来自 `core.getCurrentPosition()` / `getDuration()`）
- `bindPlayerContainer` 在真实播放路径上是否被调用、Context 是否拿到 → `player bound context=<非0>`
- `E0#b(current, previous)` 回调是否携带可用 `Video$e` → `[probe] directorCallback` / `directorCurrentVideo`
- 薄轨道实际是哪个类、bounds 多少（`g` / `f` / `q`） → `[probe] seekTrackCalled:<类名>` / `[probe] seekDraw:<类名>:<实例>`
- `bilisb://settings` 在新宿主的拦截落点 → `[probe] uriRouter`（预期 MISS；入口点击本身由我方监听器兜底）

## 本次迁移改动（M1/M2 + 探针版）

- **作用域**：`META-INF/xposed/scope.list` → `com.bilibili.app.in`（构建产物已校验）。
- **入口**：`Entry` 包名与子进程名单改走 `HostTargets`。
- **路径**：`SettingsSyncBridge.HOST_PACKAGE`、`ModuleSettings` 镜像候选、`SkipStatsStore` 统计文件
  统一由 `HostTargets.HOST_DATA_DIRS` 派生（新包优先、旧包兜底）。
- **新增 `host/HostTargets.kt`**：所有宿主类名/方法名候选集中一处（宿主改版只改这张表）。
- **新增 `host/HookProbe.kt`**：`HookResolve`（按候选+签名形状解析，含父类/接口回退）+ `HookProbe`
  （命中/缺失汇总、限频探针日志）。
- **Hook 重定向**：
  - 容器入口 → `PlayerSeekWidget3#bindPlayerContainer(tv.danmaku.biliplayerv2.f)`（旧 `be1.j` 生命周期保留兜底）；
  - 进度回调 → `G(int,int)` 等候选 + `j0/k0(long,long)` 形态，实际回调名与参数由探针打印；
  - aid/cid → `PlayDirectorServiceV3#j0(E0)` + `Video$e#z()` → `Video$a`(`a`=avid/`b`=cid`) + 字段扫描兜底；
  - seek → `o(int,boolean)` 优先，`seekTo(int)` 兜底；
  - 进度条标记 → `seek.v3.g` 优先（`seek.v3.a` 热度曲线已排除），View 目标按整宽绘制；
  - 「我的」页 → `tv.danmaku.bili.ui.main2.mine.d` 候选 + `MenuGroup$Item` 注入逻辑复用。
- **新增 `docs/DEVICE_PROBE.md`**：真机验证 runbook（构建/安装/抓日志/证据清单/回写方式）。

## 历史验证记录（旧目标 tv.danmaku.bili 8.96.0）

以下结论都来自旧目标，**不能当作 6.5.0 的结论**：

- 模块加载进宿主主进程；`:web` / `:download` / `:ijkservice` 等子进程会自动跳过
- `HomeUserCenterAdapter.notifyDataSetChanged` / `onBindViewHolder` 注入成功，点击能弹出 Bili2233
- 设置主页 / 详情页 / 关于区可用；改设置后重进播放页生效，无需重启宿主
- 主链路：aid/cid 获取 → BV → SHA-256 前缀 → `GET /api/skipSegments` → 命中后 `seekTo(endMs, true)`
- 进度条标记、跳过 Toast、剩余时长扣减、统计、提交链路可用

<details>
<summary>旧目标关键日志（保留备查）</summary>

```
Hooked HomeUserCenterAdapter.notifyDataSetChanged
Hooked HomeUserCenterAdapter.onBindViewHolder
Found mine adapter: tv.danmaku.bili.ui.main2.mine.HomeUserCenterAdapter
Injected Bili2233 setting item at position 3
Attached click listener to Bili2233 setting item
Showing Bili2233 settings dialog
ModuleSettings loaded
videoDirector: FOUND aid=... cid=...
segments fetched video=... status=200 count=...
auto-skipped video=... category=...
```

</details>

## 本次变更

- 目标口径切换为 `com.bilibili.app.in` 6.5.0，并新增 `docs/APK_6.5.0_ANALYSIS.md` 记录判定依据。
- 重写 `README.md`、`docs/PROJECT_OVERVIEW.md`、`docs/ROADMAP.md`、`docs/STATUS.md`。
- 新增 `tools/dexscan/`：纯 Python 的 DEX 类/方法/字段索引与查询工具（无 jadx/apktool 也能核对类名）。
- 代码迁移到 6.5.0 候选解析 + 探针（见上节「本次迁移改动」）。

## 本地构建与单测（已验证）

之前记录的「本地缺 Android SDK，无法离线构建」**已不成立**：本机存在 `C:\android-sdk`
（platforms 35/36，build-tools 35.0.0/36.0.0）与 JDK 17，指定 `ANDROID_HOME` 后可正常构建。

```powershell
$env:ANDROID_HOME = "C:\android-sdk"
.\gradlew.bat :app:assembleDebug --no-daemon
# BUILD SUCCESSFUL（首次 10m 18s，增量 45s）
# 产物 app\build\outputs\apk\debug\app-debug.apk（约 1.0 MB）
# 已校验产物内 META-INF/xposed/scope.list = com.bilibili.app.in

.\gradlew.bat :app:testDebugUnitTest --no-daemon
# BUILD SUCCESSFUL；7 个测试类 / 18 个用例，0 失败
#（SponsorBlockClient 2、SettingsCodec 3、SettingsProviderAccess 4、SkipDecision 3、
#  SponsorBlockRepository 3、RemainingTimeFormatter 2、AidBvidConverter 1）
```

> 依赖（libxposed api 101.0.1、AGP 8.7.3 等）走网络拉取即可，本机 gradle 缓存里原本没有。

## 未完成 / 待验证

- M9 主链路边角：倒计时取消 / 手动跳过 / 时长扣减显示 ✅；片段静音 / 提交落库本轮不做
- 增强组屏幕效果：IP 属地 loc 文案、三连/气泡/投票 UI、首页切后台是否真的不刷新、分享面板是否出现 QQ
- 错误诊断面板：设置读取、网络请求、提交失败状态可见化（T5/R8）
- R1 / R2 / R6 / R7 / R9 等结构任务

## 下一步

1. 不要再投入 HomeTab / 底栏删 tab。
2. 不要再投入片段静音真机回归、提交落库 / POST body 降级（本轮明确不做）。
3. 增强组屏幕效果仍缺：评论 loc、三连/气泡/投票 UI、首页切后台、分享面板 QQ。
4. 结构债（设置收敛、Provider 收口、反射缓存）有空再做。

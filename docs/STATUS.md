# 当前状态（STATUS）

> **目标口径（2026-09 起）**：模块只以 `bilibili 6.5.0` / `com.bilibili.app.in` / `<APK目录>\bilibili_6.5.0.apks` 为目标。
> 旧目标（`tv.danmaku.bili` stock 8.96.0 适配线、`Bili-v8.98.0-x1.27.3@bb_show.apk` 行为蓝本）降级为历史记录。

模块版本：0.5.0（Bili2233）——**主链路已在 6.5.0 真机跑通**，并完成一轮全项目 code review 修复
最近变更：4 路并行 code review + 34 项问题修复（生命周期/设置热更新/提交协议/绘制几何/单测）
最近 UI 修复：播放器「更多」面板「空降助手」行左侧对齐 —— 卡片外边距 12→16dp、竖直间距 12→0（宿主已自套 16dp）、
标题 16→15sp、图标改 `TargetIconDrawable` 现画（不再运行期解析矢量 XML）。**APK 已出，待真机复验左沿落在 96~101px。**
最近真机验证：**2026-09-14 在 `com.bilibili.app.in` 6.5.0 上验证通过（见下）**

## 结论

- 目标宿主 6.5.0 上主链路已经端到端可用：加载 → 取 aid/cid → 拉片段 → 自动跳过 → 进度条标记 → Toast → 我的页入口 → 提交。
- 本轮 code review（4 个 reviewer 按模块并行）共发现 60+ 条问题，按严重度依次修复，
  重点是：**播放器离开时无销毁入口导致的静音泄漏/倒计时越界 seek/状态泄漏**、
  **设置改了不生效（两处独立 bug）**、**提交接口协议不符**、**标记绘制几何越界抛异常**。
- 仍有未在真机验证的功能项（手动跳过、静音、倒计时、时长扣减的显示效果、小窗/切集/番剧），
  以及需要 Robolectric 才能覆盖的 Bundle 往返单测。

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
| Toast / 提交按钮 | ✅ | `showToast: 跳过: 开场动画 (30.0秒)`、`标记 赞助/恰饭`、`已取消标记` |

### 仍未在真机验证

- 手动跳过按钮、片段静音、倒计时取消浮层（本轮刚补齐生命周期，待验证）
- 剩余时长扣减的**显示效果**（hook 已装，文本是否符合预期未确认）
- 小窗、切集、番剧/OGV（epid 路径）、深色模式、切账号
- 提交是否真的落库（本轮把 GET 改成 POST，待真机确认服务端接受）

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

## 设备现状（2026-09-14 实测）

| 项 | 值 |
| --- | --- |
| 设备 | Xiaomi （机型略），arm64-v8a，Android 16（SDK 36） |
| 目标宿主 | `com.bilibili.app.in` versionName 6.5.0 / versionCode 9110200 ✅（与 `.apks` 一致） |
| Root | KernelSU 已安装（`me.weishu.kernelsu`）；`adb shell` 直连 `su` 不可用，需在管理器授权 |
| LSPosed | ❌ 未安装（`org.lsposed.manager` 不存在）——**唯一阻塞点** |
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
- 薄轨道实际是哪个类、bounds 多少（`g` / `f` / `q`） → `[probe] seekTrackDrawn`
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

- **M0**：设备装 LSPosed（唯一硬阻塞）
- 真机验证 M3–M7（探针版已就绪，runbook：`docs/DEVICE_PROBE.md`）
- M8 路由拦截点重定位；M9 回归矩阵（小窗、切集、番剧、深色模式、切账号）
- 错误诊断面板：设置读取、网络请求、提交失败状态可见化（T5/R8）
- R1 / R2 / R6 / R7 / R9 等结构任务

## 下一步

1. **M0**：给设备装 LSPosed → 启用模块 → 作用域勾 `com.bilibili.app.in`。
2. 按 `docs/DEVICE_PROBE.md` 跑一次：冷启动进首页 → 我的页 → 播放页 30 秒，抓 `Bili2233` 日志。
3. 把日志结论回写到本文档，并按结果把 `HostTargets` 里对应候选提升为首选、勾掉 M3–M7。
4. 然后做 M8（路由拦截）、M9（回归矩阵），版本升 0.6.0。

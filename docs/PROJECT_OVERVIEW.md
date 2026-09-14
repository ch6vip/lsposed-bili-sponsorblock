# 项目整理总览（PROJECT_OVERVIEW）

> **目标口径（2026-09 起）**：唯一目标宿主是 `bilibili 6.5.0` / `com.bilibili.app.in` /
> 安装包 `<APK目录>\bilibili_6.5.0.apks`。
> 类名、Hook 点、进程名、包名的判定依据见 [`docs/APK_6.5.0_ANALYSIS.md`](./APK_6.5.0_ANALYSIS.md)。
> 本文档描述**当前仓库代码**（commit 64bdc01，versionName 0.5.0）与**目标**之间的差距。

## 1. 项目定位与基本信息

| 项 | 值 |
| --- | --- |
| 项目名 | LsposedBiliSponsorBlock（模块显示名 Bili2233） |
| 目标 | 在 B 站 Android 客户端里复刻 SponsorBlock 片段跳过/标记（拉取、跳过、静音、提交、统计） |
| **目标宿主包** | **`com.bilibili.app.in`** |
| **目标宿主版本** | **6.5.0（versionCode 9110200）** |
| **目标安装包** | `<APK目录>\bilibili_6.5.0.apks`（split：base + arm64_v8a + xxhdpi） |
| 宿主运行环境 | minSdk 24 / targetSdk 36 / compileSdk 36，33 个 dex |
| 模块包名 | `com.ctf.bilisb` |
| 当前版本 | 0.5.0（versionCode 5）——**代码仍停在旧目标，尚未迁移** |
| 形态 | LSPosed 模块 + 可单独启动的设置 Activity |
| Xposed API | io.github.libxposed:api:101.0.1（compileOnly） |
| 构建 | AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.12 / compileSdk 35 / minSdk 23 / JDK 17 |
| CI | `.github/workflows/android.yml`：构建 debug APK 并上传 artifact |
| 分析工具 | `tools/dexscan/`（纯 Python，目标 APK 类/方法/字段索引） |

## 2. 目标口径切换说明

- **现行目标**：`com.bilibili.app.in` 6.5.0。scope、进程过滤、设置镜像路径、Hook 类名全部以它为准。
- **历史目标（已废弃）**：`tv.danmaku.bili` stock 8.96.0（当前代码的适配线）与
  `Bili-v8.98.0-x1.27.3@bb_show.apk`（行为参考）。两者只作为"行为蓝本"保留，不再作为 Hook 依据。
- **兼容性结论（静态分析，未经真机）**：6.5.0 与旧适配线的类名**部分保留、部分混淆**，
  因此不能简单把包名替换掉就完事——详见 APK 分析文档的映射表。

## 3. 目录结构与职责

```
app/src/main/
├── kotlin/com/ctf/bilisb/
│   ├── Entry.kt                        Xposed 入口：进程过滤（包名/子进程走 HostTargets）
│   ├── BiliSponsorBlockHooks.kt        Hook 安装与编排中枢（候选解析 + 探针）
│   ├── host/                           宿主适配层（宿主改版只改这里）
│   │   ├── HostTargets.kt              类名/方法名候选表（6.5.0 优先，旧目标兜底）
│   │   └── HookProbe.kt                HookResolve（候选/签名形状解析）+ HookProbe（命中汇总/限频日志）
│   ├── hook/
│   │   └── MineMenuInjector.kt         「我的」页菜单注入设置入口
│   ├── player/                         播放器桥接层（不感知业务）
│   │   ├── PlayerBridge.kt             从容器取 core / context
│   │   ├── PlayerHandle.kt             容器 + core + contextHash 绑定
│   │   ├── PlayerState.kt              aid/bvid/cid/duration/position 快照
│   │   ├── PlayerActions.kt            seekTo / getCurrentPosition / getDuration 反射封装
│   │   ├── AudioMuteController.kt      片段静音（系统 STREAM_MUSIC 方案）
│   │   └── VideoDirectorListener.kt    代理 service.E0 观察者，解析 aid/cid（6.5.0 链路）
│   ├── sponsor/                        业务控制层
│   │   ├── SponsorBlockController.kt   核心编排：拉取/缓存读取/跳过/静音/倒计时/手动/提交/生命周期
│   │   ├── SponsorBlockRepository.kt   缓存层（TTL + 过期清除）
│   │   ├── SkipDecision.kt             命中判定（lookahead 250ms、min duration、poi/mute 区分）
│   │   ├── SubmissionDraftController.kt 两次取点生成提交
│   │   ├── UserIdentityStore.kt        用户 ID 生成/迁移/校验
│   │   └── SkipStatsStore.kt           跳过统计（进程单例 + JSON 持久化）
│   ├── net/
│   │   └── SponsorBlockClient.kt       SponsorBlock 协议：hash 前缀、拉取、提交、JSON 解析
│   ├── model/                          数据模型 + 分类中文名单一来源 SponsorCategories
│   ├── settings/                       设置存储 + 设置 UI
│   │   ├── SettingsStore.kt            Keys / Writer / Codec / SyncBridge / ProviderAccess
│   │   ├── ModuleSettings.kt           Hook 端读取器（三级 fallback）+ SettingsSnapshot
│   │   ├── SettingsProvider.kt         exported ContentProvider（IPC 读写设置）
│   │   ├── LauncherActivity.kt         模块独立入口设置页
│   │   ├── SponsorBlockSettingDialog.kt 宿主内设置弹窗
│   │   ├── SettingsScreenBuilder.kt    主/详情/关于 UI 构建器（两端共用）
│   │   └── ColorPickerDialog.kt        纯代码颜色选择器
│   ├── ui/                             播放器内 UI 与绘制
│   │   ├── ProgressMarkerPainter.kt    进度条片段标记（实色矩形 / POI 圆点）
│   │   ├── RemainingTimeFormatter.kt   剩余时长扣减文本
│   │   ├── ManualSkipButton.kt         手动跳过胶囊按钮
│   │   ├── SkipCountdownOverlay.kt     倒计时取消浮层
│   │   ├── SubmissionButtonInjector.kt 播放器内 SB 标记按钮 + 类别选择
│   │   └── PlayerToastBridge.kt        原生 Toast 封装
│   └── util/
│       ├── AidBvidConverter.kt         aid -> BV 号
│       ├── HashUtils.kt                SHA-256 -> 4 位前缀
│       └── ModuleLog.kt                Xposed 日志封装
├── res/                                主题、字符串、图标
└── resources/META-INF/xposed/          模块元数据 module.prop / scope.list / java_init.list
                                           → scope.list 当前为 tv.danmaku.bili，需改为 com.bilibili.app.in

app/src/test/kotlin/...                 JUnit 单测（当前 7 个文件）
docs/                                   计划与状态文档
tools/dexscan/                          目标 APK 静态分析工具
```

## 4. 运行时架构

### 4.1 双进程模型

- 模块 APK 进程 `com.ctf.bilisb`：设置 UI（LauncherActivity）、ContentProvider。
- 宿主进程 **`com.bilibili.app.in`**：Hook 代码、播放器内 UI、统计。
- 宿主还有 `:web` / `:download` / `:pushservice` / `:ijkservice` / `:widgetProvider` /
  `:dd_update` / `:heap_analysis` / `:safemode` / `:sandboxed_process0..4` 等子进程，一律不挂 Hook。
- Hook 端读不到模块 APK 的 SharedPreferences → 由 SettingsProvider（exported）+ JSON 镜像文件桥接。

### 4.2 设置同步链路（三级 fallback）

写入端 SettingsWriter（设置 UI 所在进程）：

```
SharedPreferences 变更
  -> mirrorToFile()           写 JSON 镜像
  -> syncSnapshotToModule()   走 ContentProvider putSettings
```

读取端 ModuleSettings（Hook 端，宿主进程）：

1. JSON 镜像文件（候选 `/data/data/com.ctf.bilisb/files/...`、**`/data/data/com.bilibili.app.in/...`**）
2. ContentProvider `call(getSettings)`
3. SettingsSnapshot.DEFAULT

> 现有代码里镜像/统计路径与 `HOST_PACKAGE` 仍写死 `tv.danmaku.bili`，属于 P0 迁移项（见 ROADMAP M6）。

生效语义：修改后 **重新进入播放页生效**（进入播放页时 reload），无需重启宿主。

### 4.3 视频识别与跳过主链路（已按 6.5.0 实现，待真机验证）

```
播放页进入
  |- Hook widget 的 bindPlayerContainer(tv.danmaku.biliplayerv2.f)   ← 6.5.0 入口（旧方案是 Hook 容器生命周期）
  |    |- f.t() 取 Context -> ModuleSettings.reload()
  |    |- core = widget.getPlayerCoreService()   -> PlayerHandle(contextHash, container, core)
  |    |- VideoDirectorListener.noteContextHash(contextHash)（供 aid/cid 回调回落）
  |- aid/cid: Hook PlayDirectorServiceV3#j0(E0) 拿服务实例 -> 注册自己的 E0 代理
  |    |- E0#b(Video$e current, Video$e previous) / c / e
  |    |- Video$e#z() -> Video$a(DanmakuResolveParams) 的 a=avid、b=cid（字段扫描兜底）
  |    |- AidBvidConverter.aidToBvid() -> GET /api/skipSegments/{sha256(bvid)[0..4]}
  |- 进度回调（候选解析）: G(int,int) / onPlayerProgressChange / updateTime / i0，以及 j0/k0(long,long)
  |    |- Controller.onProgress(contextHash, positionMs, durationMs)
  |    |- 命中 -> seek: core.o(int, true)（兜底 seekTo(int)）
  |- 进度条绘制: Hook seek.v3.g#draw(Canvas)（首选）-> seek.v3.f / q / e（候选）
  |- 时间扣减: Hook 进度文本类 setText(CharSequence, BufferType)
```

> 每一条 Hook 都经过 `HookProbe`：命中打 `[probe] hook ok`，缺失打 `[probe] hook miss`，
> 安装结束打印 `[probe] hook summary: N/M hit`。宿主改版后不会再静默失效。
> 已知取舍：director 服务与 contextHash 没有稳定可反射的映射，目前用「最近绑定容器」回落（代码内标注为推测实现）。

### 4.4 播放器销毁

旧目标是 Hook 容器 `be1.j#onDestroy` → 取消静音、隐藏按钮/倒计时、移除 context 引用。
6.5.0 没有等价容器类，清理路径挂在**旧容器 hook（兜底，6.5.0 不生效）**上；
新入口的生命周期清理（widget detach / container 释放）需要在真机确认后补齐（ROADMAP M3/M9）。

## 5. Hook 点清单（当前代码）

> 全部经候选解析（`HostTargets`），下表列「首选目标 / 兜底候选」。

| # | 目标 | 方式 | 作用 |
| --- | --- | --- | --- |
| 1 | `...seek.v3.PlayerSeekWidget3#bindPlayerContainer(tv.danmaku.biliplayerv2.f)`（6.5.0）<br>兜底：`be1.j#onCreate(Bundle)`（8.96） | after | 取 Context/core、加载设置、绑定 handle、挂提交按钮 |
| 2 | `PlayDirectorServiceV3#j0(E0)`（6.5.0）<br>兜底：`be1.j#onStart()` + `addVideoDirectorObserver`（8.96） | after | 拿 director 服务并注册 `E0` 代理 → aid/cid |
| 3 | `be1.j#onDestroy()`（仅 8.96 存在） | after | 清理 context 引用、取消静音 |
| 4 | `playerbizcommonv2...PlayerProgressTextWidget#G(int,int)` 等候选 | after | 触发跳过/静音决策 |
| 5 | `...GeminiProgressTextWidget#G(int,int)` / `k0(long,long)` | after | 同上（Gemini 样式） |
| 6 | `playerbizcommon.widget.control.PlayerProgressTextWidget#G(int,int)` / `i0(int,int)` | after | 同上（旧版控件） |
| 7-9 | 上述三类的 `setText(CharSequence, BufferType)` | intercept | 时间扣减持久化 |
| 10 | `playerbizcommonv2.widget.seek.v3.g#draw(Canvas)`（首选；`f`/`q`/`e` 兜底，`a` 排除） | after | 进度条标记 |
| 11 | `tv.danmaku.bili.ui.main2.mine.d#notifyDataSetChanged()`（经 RecyclerView 基类） | before | 注入 Bili2233 菜单项 |
| 12 | `tv.danmaku.bili.ui.main2.mine.d#onBindViewHolder(ViewHolder,int)` | after | 给注入项绑点击 |
| 13 | `blrouter.Router` / `BLRouter` / `IntentHandlerActivity` | before(highest) | 拦截 `bilisb://settings`（6.5.0 预期 MISS，M8 待重定位） |
| 14 | `tv.danmaku.biliplayerv2.service.E0` 接口 | 动态代理 | 接收 `Video$e` 并解析 aid/cid |

> 所有 hook 都用 `ExceptionMode.PROTECTIVE`，失败只记日志、不崩宿主。

## 6. 设置项清单

| key | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| enabled | bool | true | 总开关 |
| auto_skip | bool | true | 自动跳过 |
| manual_skip | bool | false | 手动跳过（显示按钮） |
| mute_segments | bool | false | mute 片段静音 |
| min_skip_duration | string(float 秒) | 0 | 最小片段时长过滤 |
| skip_countdown | string(float 秒) | 0 | 自动跳过倒计时 |
| server_address | string | https://bsbsb.top | 服务端地址 |
| cache_ttl_minutes | string | 60 | 片段缓存 TTL（0 = 不缓存） |
| user_id | string | (空) | 提交用户 ID（32 位 hex） |
| default_submit_category | string | sponsor | 默认标记分类 |
| cat_* | bool x 9 | true | 各分类开关 |
| show_toast | bool | true | 跳过 Toast |
| show_seekbar_marker | bool | true | 进度条标记 |
| show_time_deduction | bool | true | 剩余时长扣减 |
| show_submit_button | bool | true | 播放器内标记按钮 |
| color_<category> | string hex | 见内置配色 | 各分类标记颜色 |

分类：sponsor / selfpromo / interaction / intro / outro / preview /
music_offtopic / filler / poi_highlight

> 设置项本身与宿主版本无关，迁移期间不变。

## 7. 功能状态

> 判定口径：**旧目标（tv.danmaku.bili 8.96）上的真机结论，不能直接当作 6.5.0 的结论。**

### 已在旧目标上验证过（历史记录，供迁移参考）

- 主进程识别与子进程跳过（旧包名）
- 菜单注入、设置弹窗、跨进程设置同步、统计、提交链路
- aid/cid 获取、BV→SHA-256→前缀、`/api/skipSegments`、命中后 seekTo
- 进度条标记与 Toast

### 6.5.0 目标上的状态

- **全部为「未验证」**：尚未在 6.5.0 真机/模拟器上跑过任何一次 Hook。
- 静态分析已确认：进度文本三类的 `setText`、core 的 `getDuration`/`getCurrentPosition`、
  `MenuGroup`/`MenuGroup$Item` 字段、blrouter 框架、`IntentHandlerActivity` **仍然可用**。
- 静态分析已确认失效：容器 `be1.j` 生命周期、`onPlayerProgressChange`、
  `VideoDirectorObserver`/`addVideoDirectorObserver`/`getLogDescription`、`seek.v3.q/e#draw`、
  `HomeUserCenterAdapter` 类名、`blrouter.Router`/`BLRouter` 类名。

### 明确未做

- unskip / undo（行为蓝本 APK 该构建无此功能）
- mute core 原生音量方法（改用系统 AudioManager 代替）
- 完整设置体验对齐（分类选择、手动时间编辑、预览确认）

## 8. 已知问题与技术债

### 高

1. **全部 Hook 类名硬编码且已过时**：迁移到 6.5.0 必须逐条改；建议同时引入
   「Hook 命中情况」状态面板（哪条 hook 装上/缺失），避免改版后静默失效。
2. **aid/cid 链路在 6.5.0 需重建**：`service.E0` / `service.A#j0` 已定位，但字段语义未证实，
   这是整条业务链的前置条件（blocker）。

### 中

3. 设置 Prefs / JSON / Bundle 三套 Codec 手写重复（R1），加字段要改多处。
4. Provider exported=true，仅靠 isAllowedCaller 校验；putSettings 接收整段 JSON 且无字段级校验（R2）。
5. 播放中设置不即时生效（需重进播放页），属已知取舍。
6. 网络/提交失败对用户不可见（R8/T5）：`SponsorBlockClient` 会返回 statusCode=-1，但调用方只写日志。
7. 多处反射在每个调用点重复 `getDeclaredMethod`，可缓存 Method。
8. `MineMenuInjector` 依赖 `RecyclerView.Adapter#notifyDataSetChanged` 全局方法 + 类名过滤，
   hook 面偏大；6.5.0 建议改挂 adapter 自己的 `onBindViewHolder`。

## 9. 迁移顺序

见 [`docs/ROADMAP.md`](./ROADMAP.md) 的 M1–M9：作用域/进程 → 设置路径 → 播放器桥接 →
进度回调 → aid/cid → 进度条标记 → 我的页入口 → 真机回归 → 结构整理（原 R/T 任务）。

## 10. 构建与验证速查

```powershell
# 本机 Android SDK 位于 C:\android-sdk（platforms 35/36），需要显式指定
$env:ANDROID_HOME = "C:\android-sdk"

# 构建 debug APK
.\gradlew.bat :app:assembleDebug

# 跑单元测试
.\gradlew.bat :app:testDebugUnitTest

# 产物
app\build\outputs\apk\debug\app-debug.apk
```

安装后在 LSPosed 中勾选作用域 **`com.bilibili.app.in`**，日志 TAG 为 `Bili2233`。

静态分析（不需要设备）：

```powershell
python tools\dexscan\extract_apks.py <APK目录>\bilibili_6.5.0.apks <工作目录>
python tools\dexscan\dexindex.py <工作目录>\dex <工作目录>\index.tsv
$env:DEX_INDEX = "<工作目录>\index.tsv"
python tools\dexscan\q.py '^M\t\S+\tLcom/bilibili/playerbizcommonv2/widget/base/PlayerProgressTextWidget;'
```

## 11. 关联文档

- `README.md` — 项目定位、构建方式、快速开始
- `docs/APK_6.5.0_ANALYSIS.md` — **目标 APK 事实、类名保留情况、Hook 映射与证据**
- `docs/ROADMAP.md` — 迁移任务（M0–M9）与优先级
- `docs/STATUS.md` — 当前验证状态（真机结论回写处）
- `docs/DEVICE_PROBE.md` — 真机验证 runbook（安装/抓日志/证据清单）
- `tools/dexscan/README.md` — 静态分析复现方法

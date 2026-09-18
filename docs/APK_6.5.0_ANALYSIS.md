# 目标 APK 分析：bilibili 6.5.0（`com.bilibili.app.in`）

> 本文档是 **Hook 适配的唯一事实来源**。所有条目都注明是「实测/静态证据」还是「推测」。
> 结论基于离线静态分析（`tools/dexscan`），**尚未在真机上验证**；真机结论请回写 `docs/STATUS.md`。

## 1. 目标包事实（静态实测）

| 项 | 值 |
| --- | --- |
| 目标文件 | `<APK目录>\bilibili_6.5.0.apks`（212,167,349 B，split APK bundle） |
| 包内成员 | `base.apk`(326,826,237 B)、`split_config.arm64_v8a.apk`(126,017,662 B)、`split_config.xxhdpi.apk`(153,133 B) |
| 应用包名 | `com.bilibili.app.in` |
| versionName / versionCode | `6.5.0` / `9110200` |
| minSdk / targetSdk / compileSdk | `24` / `36` / `36`（platformBuildVersion 16） |
| 启动 Activity | `tv.danmaku.bili.MainActivityV2`（label `bilibili`） |
| dex 数量 | `base.apk` 内 33 个 `classes*.dex` |
| 主进程 | `com.bilibili.app.in` |
| 子进程（manifest 中 `android:process`） | `:web`(111 处)、`:download`(10)、`:pushservice`(9)、`:widgetProvider`(3)、`:ijkservice`、`:dd_update`、`:heap_analysis`、`:safemode`、`:sandboxed_process0..4` |
| 宿主自带 SponsorBlock | 无（`SponsorBlock` / `skipSegments` 字符串命中 0） |

关键点：**applicationId 换成了国际版包名，但内部类包名依旧是 `tv.danmaku.bili.*` / `com.bilibili.*`**，
所以“旧适配线的类名”不是整体失效，而是**逐个类地被 R8 混淆/保留**，必须逐条核对。

复现命令见 `tools/dexscan/README.md`。

## 2. 类名保留情况总览

| 能力 | 旧适配线（stock 8.96 / `tv.danmaku.bili`） | 6.5.0（`com.bilibili.app.in`） | 状态 |
| --- | --- | --- | --- |
| 进度文本基类 | `com.bilibili.playerbizcommonv2.widget.base.PlayerProgressTextWidget#onPlayerProgressChange(int,int)` | 同类名，方法被混淆为 `G(int,int)` | 改名 |
| Gemini 进度文本 | `com.bilibili.app.gemini.player.widget.progress.GeminiProgressTextWidget#onPlayerProgressChange` | 同类名，方法为 `G(int,int)`；另有 `k0(long,long)` | 改名 |
| 旧版进度文本 | `com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget#updateTime(int,int)` | 同类名，含 `G(int,int)` / `i0(int,int)` / `F0()` / `N0()` | 改名（待定具体回调） |
| 进度文本 setText | `setText(CharSequence, TextView$BufferType)` | 三个类均声明该方法 | **可复用** |
| 播放器 core 服务 | `getPlayerCoreService()` → core | `getPlayerCoreService()` → `tv.danmaku.biliplayerv2.service.D` | **可复用**（接口名混淆） |
| 时长 / 位置 | `getDuration()` / `getCurrentPosition()` | 同名保留，返回 `int`（另有 `getRealDuration()`） | **可复用** |
| seek | `seekTo(int, boolean)` | `seekTo(int)V` 是默认方法，抽象实现是 `o(int, boolean)V` | 改名 |
| seek 服务 | — | `tv.danmaku.biliplayerv2.service.Z extends Q`；`e/f(r0)` 注册进度回调 | 新链路 |
| 播放器容器 | `be1.j#onCreate/onStart/onDestroy` | **不存在同类**（全包无类同时声明这三个方法）；容器接口是 `tv.danmaku.biliplayerv2.f`，由 `PlayerSeekWidget3#bindPlayerContainer(f)` 传入 | 失效，需换入口 |
| 视频信息观察者 | `tv.danmaku.biliplayerv2.service.VideoDirectorObserver` + `addVideoDirectorObserver` | 接口名不存在；观察者接口为 `tv.danmaku.biliplayerv2.service.E0 extends z0`，注册/注销为 `service.A#j0(E0)` / `#z0(E0)` | 改名 |
| 日志描述取 aid/cid | `getLogDescription()` | **全包不存在**（`getInlineLogDescription` 属其它模块 bean，无关） | 失效 |
| Video 数据对象 | `service.Video` 的 `a` 字段（String aid） | `service.Video$a` = `DanmakuResolveParams`（`a:J`=avid、`b:J`=cid、`d:J`=epid、`e:J`=seasonId、`f:I`=page…，已实测）；`service.Video$e` 为接口，`z()` 返回 `Video$a` | 需重新确认回调携带对象 |
| 进度条薄轨道绘制 | `com.bilibili.playerbizcommonv2.widget.seek.v3.q` / `v3.e` 的 `draw(Canvas)` | 6.5.0 里 `v3.q` 是 `LayerDrawable`（**不覆写 draw**）、`v3.e` 是 Kotlin lambda；覆写 `draw(Canvas)` 的只有 `v3.g`、`v3.a`、`v3.f` | 失效，需换目标 |
| 「我的」页 adapter | `tv.danmaku.bili.ui.main2.mine.HomeUserCenterAdapter` | 外层类被混淆为 `tv.danmaku.bili.ui.main2.mine.d`（Fragment `HomeUserCenterFragment` 保留；`HomeUserCenterAdapter$collectPageVisibility$1` 仅作为内部 lambda 名残留） | 改名 |
| 菜单数据模型 | `com.bilibili.lib.homepage.mine.MenuGroup` / `MenuGroup$Item` | **完整保留**：`MenuGroup.itemList` 等 19 字段、`Item` 的 `id:J/title/uri/icon/needLogin:I/redDot:I/localShow:Z/type:I/visible:I` 等 24 字段 | **可复用** |
| 路由框架 | `com.bilibili.lib.blrouter.Router` / `BLRouter`、`tv.danmaku.bili.ui.intent.IntentHandlerActivity` | `com.bilibili.lib.blrouter.*` 存在（447 个类），但 `Router` / `BLRouter` 类名不存在；`IntentHandlerActivity` 存在 | 部分失效 |
| 设置弹窗宿主 Context | 宿主进程 `tv.danmaku.bili` | 宿主进程 `com.bilibili.app.in` | 需改常量 |

## 3. Hook 点映射（含证据）

### 3.1 进度与时长（最关键、证据最完整）

实测（`dexindex.py` 索引）：

```
M classes17.dex Lcom/bilibili/playerbizcommonv2/widget/base/PlayerProgressTextWidget;  G(I,I)V   public|final|code
M classes17.dex Lcom/bilibili/playerbizcommonv2/widget/base/PlayerProgressTextWidget;  j0(J,J)V  public|final|code
M classes17.dex Lcom/bilibili/playerbizcommonv2/widget/base/PlayerProgressTextWidget;  getPlayerCoreService()Ltv/danmaku/biliplayerv2/service/D;
M classes12.dex Lcom/bilibili/app/gemini/player/widget/progress/GeminiProgressTextWidget; G(I,I)V  public|final|code
M classes17.dex Lcom/bilibili/playerbizcommon/widget/control/PlayerProgressTextWidget; G(I,I)V / i0(I,I)V / F0()V / N0()V
M classes26.dex Ltv/danmaku/biliplayerv2/service/D;  getDuration()I / getCurrentPosition()I / getRealDuration()I / seekTo(I)V / o(I,Z)V
```

结论：

- 进度回调是 `G(int,int)`，**语义已由字节码实测确认**（不再是猜测）：
  `G` 是接口 `tv.danmaku.biliplayerv2.service.r0#G(int,int)` 的实现（widget 实现该接口），
  全 dex 唯一派发点在 classes26，参数直接取自 core：

  ```
  invoke-virtual {v0}, LRH1/p;.getCurrentPosition:()I   -> v2
  invoke-virtual {v0}, LRH1/p;.getDuration:()I          -> v0
  invoke-interface {v4, v2, v0}, Ltv/danmaku/biliplayerv2/service/r0;.G:(II)V   // G(position, duration)
  ```

  即 `G(position, duration)`，单位与 core 一致（毫秒）。
- `j0(long,long)`（v2 基类）/ `k0(long,long)`（Gemini）**不是**进度派发：它们不是 `r0`/`g` 的接口方法，
  体内有 `const/16 999` 一类的定时/格式化逻辑。代码里仍然挂上但**只打日志、不喂 controller**（探针专用）。
- `getPlayerCoreService()` / `getDuration()` / `getCurrentPosition()` 名字保留，`PlayerBridge`、`PlayerActions`
  的这两个反射调用可原样用。
- `seekTo(int, boolean)` 在 6.5.0 拆成 `seekTo(int)`（默认方法）与 `o(int, boolean)`（抽象实现）。
  字节码实测 `D.seekTo:(I)V` 的方法体只有两条指令：

  ```
  0000: const/4 v0, #int 0
  0001: invoke-interface {v1, v2, v0}, Ltv/danmaku/biliplayerv2/service/D;.o:(IZ)V
  ```

  即 `seekTo(pos)` == `o(pos, false)`。旧代码用的 `seekTo(pos, true)`（平滑 seek）在 6.5.0 应调 `o(pos, true)`。

### 3.2 播放器容器与 context（旧入口失效）

- `analyze.py and "onCreate(Landroid/os/Bundle;)V" "onStart()V" "onDestroy()V"` → **hits: 0**，
  即 6.5.0 没有任何类同时声明旧容器 `be1.j` 的三个生命周期方法。
- 容器接口：`Ltv/danmaku/biliplayerv2/f;`（`extends d`），方法 `t()Landroid/content/Context;`、`u()LiI1/a;`、`v()Ltv/danmaku/biliplayerv2/j;`、`w(LAF/m;)V`。`t()` 即取 Context。
- 传入时机：`Lcom/bilibili/playerbizcommonv2/widget/seek/v3/PlayerSeekWidget3;#bindPlayerContainer(Ltv/danmaku/biliplayerv2/f;)V`（`public|final|code`），
  同为 widget 的 `com.bilibili.playerbizcommon.widget.control.PlayerProgressTextWidget` 也声明了 `bindPlayerContainer`。
- 推测实现：把入口从「Hook 容器生命周期」换成「Hook widget 的 `bindPlayerContainer(f)`」，
  在回调里拿 Context + 绑定 `contextHash`，比旧方案更稳（不依赖混淆后的容器类名）。

### 3.3 aid / cid（链路必须重建）

- `VideoDirectorObserver`、`addVideoDirectorObserver`、`getLogDescription` 在 6.5.0 中**均不存在**（字符串命中 0）。
- 观察者接口：`Ltv/danmaku/biliplayerv2/service/E0;`（`extends z0`）
  方法：`a()V`、`b(Ltv/danmaku/biliplayerv2/service/Video$e;,Ltv/danmaku/biliplayerv2/service/Video$e;)V`、`c(Video$e)V`、`e(Video$e)V`，
  均为 `public|code`（接口默认方法）。`b(current, previous)` 形态与旧 `onStart(current, previous)` 对应。
- 注册/注销：`Ltv/danmaku/biliplayerv2/service/A;` 的 `j0(Ltv/danmaku/biliplayerv2/service/E0;)V`、`z0(Ltv/danmaku/biliplayerv2/service/E0;)V`。
- 数据对象：`service/Video$a;` 是数据类，构造签名含 `(J,J,Ljava/lang/String;,J,J,I,…)`，字段 `a:J`、`b:J`、`c:String`…；
  `service/Video$e;` 是接口（`z()LVideo$a;`、`D()Ljava/lang/String;`、`J()Ljava/lang/String;` 等）。

**aid/cid 字段语义已实测确认**（`dexdump -d` 反汇编 `Video$a.toString`）：

```
const-string "DanmakuResolveParams(avid="  + a:J
const-string ", cid="                      + b:J
const-string ", spmid="                    + c:String
const-string ", epid="                     + d:J
const-string ", seasonId="                 + e:J
const-string ", page="                     + f:I
const-string ", from="                     + g:String
const-string ", link="                     + h:String
const-string ", isRealTime="               + i:Z
const-string ", language=" / ", productionType=" + k:I
const-string ", extension="                + l:Object
```

即 `Video$a` = `DanmakuResolveParams`：

| 字段 | 类型 | 语义 |
| --- | --- | --- |
| `a` | `J` | **avid（aid）** |
| `b` | `J` | **cid** |
| `c` | `String` | spmid |
| `d` | `J` | epid（番剧） |
| `e` | `J` | seasonId |
| `f` | `I` | page（分 P） |
| `g` / `h` | `String` | from / link |
| `i` | `Z` | isRealTime |
| `l` | `Object` | extension |

取用路径（推测，待真机确认）：`service.E0#b(current: Video$e, previous: Video$e)` 回调里对 `current` 调 `z()`
得到 `Video$a`，再读 `a` / `b`。

### 3.4 进度条标记（绘制目标变化）

| 类 | 形态 | 是否覆写 `draw(Canvas)` | 判断 |
| --- | --- | --- | --- |
| `seek.v3.g` | `Drawable`，字段 `RectF g` + `Paint f`，构造 `(I,I,F,F)` | 是 | **首选**：实色矩形轨道层 |
| `seek.v3.a` | `Drawable`，字段 `Path c/d` + `Matrix e` + `Paint a/b`，被 `HeatPeakView.<init>` new 出来存入 `HeatPeakView.b` | 是 | 热度曲线（高 h），**不要挂** |
| `seek.v3.f` | `AppCompatSeekBar` 子类，`getSeekbarProgressDrawable()Lseek/v3/j;`，内部 `n:Lseek/v3/j;` | 是（还有 `onDraw`） | 备选：整条 seekbar |
| `seek.v3.q` | `LayerDrawable` | 否 | 旧目标，6.5.0 失效 |
| `seek.v3.e` | Kotlin lambda (`Function1`) | 否 | 旧目标，6.5.0 失效 |
| `seek.v3.j` | 进度 drawable（`f.n`），内部持有 `C:[Lseek/v3/g;` 数组 | — | 容器，可作上下文线索 |

证据：`dexdump -d` 交叉引用显示 `HeatPeakView.<init>` new `seek.v3.a`（`iput-object → HeatPeakView.b`），
`seek.v3.j` 的 `C` 字段是 `[Lseek/v3/g;`，并在循环里 `new-instance ... seek/v3/g`。

### 3.5 「我的」页设置入口

- adapter：`Ltv/danmaku/bili/ui/main2/mine/d;`（`extends androidx.recyclerview.widget.RecyclerView$Adapter`），
  声明方法：`onBindViewHolder(RecyclerView$C,int)`、`onBindViewHolder(RecyclerView$C,int,List)`、`onCreateViewHolder(ViewGroup,int)`、
  `getItemCount()`、`getItemViewType(int)`、`onViewAttachedToWindow(RecyclerView$C)`；构造 `(<HomeUserCenterFragment>, HomeUserCenterFragment$e)`。
- **`notifyDataSetChanged` 不在该类里覆写**：现有 `adapterClass.getMethod("notifyDataSetChanged")` 实际命中的是
  `RecyclerView.Adapter` 的实现（对所有 RecyclerView 生效，靠类名过滤）。6.5.0 上仍可这么用，但建议改挂
  `onBindViewHolder(VH,int)` 或 `onCreateViewHolder`，减少全局 hook 面。
- `MenuGroup` / `MenuGroup$Item` 字段完整保留，`createSettingItem()` 里 set 的
  `id/title/uri/icon/needLogin/redDot/localShow` **全部存在** → 注入逻辑可复用。
- 点击链路：`SETTING_URI = "bilisb://settings"` 的拦截依赖 blrouter；`Router`/`BLRouter` 类名在 6.5.0 不存在，
  需要重新定位拦截点（`tv.danmaku.bili.ui.intent.IntentHandlerActivity` 仍存在，可作为候选）。

## 4. 尚未确认 / 需要真机探针

> 也就是说：**「语义」基本都能静态定死，「是否真的触发」只能运行时看。**

1. `bindPlayerContainer` 在真实播放路径上是否被调用（类存在 ≠ 一定会走到）。
2. `E0#b(current, previous)` 回调是否真的携带可用的 `Video$e`（能否 `z()` 拿到 `DanmakuResolveParams`）——
   字段语义已确认，回调负载未确认。`Video$e#z()` 的默认实现是 `return null`，依赖实现类覆写。
3. 容器 `f.t()` 返回的 Context 与旧 `contextHash` 语义是否等价（是否同一实例贯穿播放页）。
4. 薄轨道最终目标：`g` vs `f` vs `q`（需要在真机上 dump 实际 drawable 类型与高度）。
5. `PlayDirectorServiceV3` 实例与具体播放器实例的对应关系（多实例/小窗场景）。
6. 设置入口点击后的路由拦截落点。

## 5. 对代码的影响清单

> 状态（2026-09）：**已全部落地**，实现落点是 `app/src/main/kotlin/com/ctf/bilisb/host/HostTargets.kt`
> 的候选表 + 各 Hook 文件；真机验证步骤见 `docs/DEVICE_PROBE.md`。

| 文件 | 改了什么 |
| --- | --- |
| `app/src/main/resources/META-INF/xposed/scope.list` | `tv.danmaku.bili` → `com.bilibili.app.in` ✅ |
| `host/HostTargets.kt`（新增） | 类名/方法名候选表 + 子进程名单 + 宿主数据目录 ✅ |
| `host/HookProbe.kt`（新增） | `HookResolve` 候选解析 + `HookProbe` 命中汇总/限频探针日志 ✅ |
| `Entry.kt` | 包名与子进程过滤改走 `HostTargets` ✅ |
| `BiliSponsorBlockHooks.kt` | 容器 Hook → `bindPlayerContainer`；进度回调 → `G` 等候选（含 long 形态）；seek 绘制 → `seek.v3.g` 优先 ✅ |
| `player/VideoDirectorListener.kt` | 观察者 → `service.E0`、注册 → `PlayDirectorServiceV3#j0`、`Video$e#z()` 取 aid/cid + 字段扫描兜底 ✅ |
| `player/PlayerActions.kt` | `o(int,boolean)` 优先，`seekTo(int)` 兜底 ✅ |
| `player/PlayerBridge.kt` | Context 取法 `t()`/`getContext()` 候选 ✅ |
| `hook/MineMenuInjector.kt` | adapter 候选 `…mine.d`；路由拦截候选化 + 探针 ✅ |
| `settings/SettingsWriter.kt`(原 SettingsStore.kt,已按类型拆分) / `settings/ModuleSettings.kt` / `sponsor/SkipStatsStore.kt` | 路径改由 `HostTargets.HOST_DATA_DIRS` 派生 ✅ |
| `ui/ProgressMarkerPainter.kt` | 新增 `drawInBounds`（支持非 Drawable 的 SeekBar 本体） ✅ |
| `README.md` / `docs/*` | 目标口径、状态、路线图、真机 runbook ✅ |

## 6. 6.5.0 真机踩坑清单（2026-09-14 实测，全部已修）

这些是"静态分析看不出来、只有在真机上跑才会暴露"的坑，按踩到的顺序记录：

| # | 坑 | 现象 | 结论/修法 |
| --- | --- | --- | --- |
| 1 | 探针日志的 lambda 非空参数 | `E0` 回调里出现 `null` 实参 → `joinToString { it -> ... }` 触发 `checkNotNullParameter` → **宿主进程崩溃**（B 站闪退） | 探针/代理回调必须整体 try/catch，且不要用非空 lambda 参数遍历宿主实参（改用数组下标读取） |
| 2 | 容器取 Context 的方法名 | 旧代码 `getDeclaredMethod("getContext")` 在 6.5.0 抛异常 → Toast/静音/手动按钮/倒计时/提交按钮**全部静默失效** | 6.5.0 容器是 `t()`；统一走 `PlayerBridge.context()`，且取不到时必须留日志 |
| 3 | `progressDrawable.bounds` 坐标空间 | 标记整体左移 27px、上移 9px（"浮在轨道上方"） | `ProgressBar` 会先 `canvas.translate(paddingLeft, paddingTop)` 再画 drawable，所以 View 路径要补 `(paddingLeft, paddingTop)`；Drawable 路径不需要 |
| 4 | 服务端 query 参数 | 拉取 `?videoID=..&cid=..&actionType=skip` → **HTTP 400** | `bsbsb.top` 只接受空前缀查询（`?actionType=skip` 或空）；`videoID/cid` 由客户端按 hash 前缀结果自行过滤 |
| 5 | 没有播放器销毁入口 | 退出播放页后：静音不解除、倒计时到点仍 seek 并记统计、按钮/浮层残留、按 contextHash 的容器引用永不释放 | 6.5.0 用 `PlayerSeekWidget3#onDetachedFromWindow` 作为"播放器离开"信号（旧目标 `be1.j#onDestroy` 在 6.5.0 不存在） |
| 6 | 提交接口协议 | `GET /api/skipSegments?userID=...` 与服务端/官方协议都不符 | 改为 `POST /api/skipSegments`（官方协议，`userAgent` 必填），仅 405/501 才降级 GET |

复现/验证方式见 `docs/DEVICE_PROBE.md`（真机 runbook）。

## 7. 播放器「更多」面板注入（6.5.0 实测，已实现）

播放器右上角「⋯」弹出的「更多」半屏面板（分享行 + 快捷操作行 + 播放设置列表）是一个
**DialogFragment**，不是新 Activity：

| 项 | 值 | 依据 |
| --- | --- | --- |
| 面板宿主 | `com.bilibili.ship.theseus.united.page.toolbar.MenuService` | 面板全部行文案只在该 dex 字符串池命中；内部类 `MenuService$doMorePlayerSetting$1` |
| 弹出方式 | `MenuService.doMorePlayerSetting` 协程里 `DialogFragment.show(fm, "player_setting_dialog")` | classes21 反汇编 |
| 内容容器 | RecyclerView + 适配器 `com.bilibili.app.gemini.ui.f`，全量刷新 `f0(List)` | 真机探针命中，列表 size=18 |
| 行模型 | 条目接口 `com.bilibili.app.gemini.ui.i`（**interface**），holder 接口 `i$b`（**interface**，只有 `getRoot()`） | 索引 + 反汇编 |
| 行数据/实现 | `playerbizcommonv2.widget.setting.channel.x`（开关行）/ `s`（值行）/ `n`（多行值） | classes12/17 |

**关键契约（决定注入方式）**：

```java
// 1) 视图类型按 item.a()（默认实现返回 getClass()）动态分配 —— 新条目类会自动拿到新 type
static int i$a.a(i item) { return registry.putIfAbsent(item.a(), nextType++); }

// 2) 创建行时反查列表里 type 匹配的条目，然后「让条目自己造视图」
onCreateViewHolder(parent, viewType) {
    for (item in d) if (i$a.a(item) == viewType) return new o(item.b(context, parent));
    throw new NoSuchElementException(...);
}
```

因此注入方式是：在 `f0(List)` **proceed 之前**往列表里 append 一个用 `Proxy` 实现的 `i` 条目
（`a()` 返回代理类、`b()` 返回自绘行视图的 `i$b` 代理、`e()` 返回 `Unit`），宿主就会用我们的行视图渲染它。

注意事项：
- 同一个 adapter `f` 也被**详情页**复用（实测详情页 47 项、播放器面板 18 项）→ 必须用内容判据
  （列表里出现 `com.bilibili.playerbizcommonv2.widget.setting.*` 包下的行）才注入；
- `f0` 内部先判断"传入 List 是否就是字段 `d`"，所以要在 `proceed` 之前插入，否则本次刷新看不到；
- 面板里我们那一行的 Context 是 Dialog 的 `ContextThemeWrapper`，`hashCode` 与播放器容器不同，
  打开自研面板时要按"controller 真的有状态"的 contextHash 选择（实现见 `MorePanelInjector`）。

## 8. 关联文档

- `docs/PROJECT_OVERVIEW.md` — 架构 / Hook 清单 / 设置项 / 风险总览
- `docs/ROADMAP.md` — 迁移任务（M1…）与验收标准
- `docs/STATUS.md` — 当前验证状态（真机结论回写处）
- `tools/dexscan/README.md` — 上述结论的复现方法

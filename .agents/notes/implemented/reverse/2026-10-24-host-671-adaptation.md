# Agent Note: B站国际版 6.7.1 适配——core service 改名、tick 派发器重组、aid/cid 载体迁移

Status: implemented
Date: 2026-10-24
目标: `com.bilibili.app.in` 6.7.1（versionCode 9161100），样本 `bilibili_6.7.1.apks`
关联: `2026-09-29-host-660-adaptation.md`（上一版的六点重定位）、`docs/APK_6.7.1_DRIFT.md`（事实文档，本机）

## 背景

宿主 6.6.0 → 6.7.1。这次改版比前两次都深：**不只是名字漂移，是三条主链路的入口类型整体换了**。
旧候选表在该版本上是「加载得到类、但类型对不上」的静默失效——比类名消失更难查，
因为探针只报 MISS 而不报原因。

分析方法：apktool 2.11.1 全量 baksmaling（311,983 文件）+ jadx 1.5.5 **按 dex 分片**
反编译（34 个 dex 合计 252MB，一次性载入需 >4GB 堆，本机 7.9GB 直接 OOM）+
`tools/dexscan/dexindex.py` 索引（283,876 类 / 604,755 字段 / 909,637 方法）。

## 六个点的最终结论

1. **IPlayerCoreService 改名 `service.A`** —— 最关键的断裂点。
   `PlayerSeekWidget3#getPlayerCoreService()`、v2 `PlayerProgressTextWidget#getPlayerCoreService()`、
   容器 `f#m()`、`PlayDirectorServiceV3.W7()` 全部改返 `service.A`。
   旧列表 `[service.D, service.f0]` 因此全部落空 → `PlayerBridge.coreService()` 恒为 null
   → bind 永久 defer、poller 空转读不到 pos/dur → **整条跳过链路静默失效**。
   - `service.D` 没消失，但降级成 `Function1` 包装类（构造参数 `service.A` + `service.E`），
     **类型名不再代表 core**。这正是「类还在但语义变了」的典型。
   - 教训：**取 core 这类「靠返回类型匹配」的反射，必须把候选类型按新版本走一遍**，
     不能只看类名是否还在。

2. **进度 tick 派发器整体重组** —— 6.6.0 的结论被完全推翻。
   - 6.6.0：`D0$c.run`（`D0` = SeekService 实现类）→ 遍历监听器 → `s0#J(JJ)`。
   - 6.7.1：`D0` **变成观察者接口**（`a()`/`b(Video$e,Video$e)`/`c(Video$e)`/`e(Video$e)`），
     `D0$c` 内部类不存在；SeekService 实现类改为 **`service.B0`**（`B0 implements service.Y`），
     tick 派发器是 **`B0#k(II)`**（遍历 `sK1.e.b` 里的 `service.k` 调 `k#f(II)`），
     监听器接口 `s0#J(JJ)` → **`q0#H(II)`**。
   - 旁证：`PlayerProgressTextWidget$bindToView$2$1$a implements service.q0`，
     其 `H(I,I)` 体内是 `int-to-long` 后转调 `PlayerProgressTextWidget#l0(JJ)`。
   - 顺带发现 **`SEEK_TICK_DISPATCHER_CLASSES` 定义了但从未安装到宿主**（仓库里的死常量）。
     本次补 `hookSeekTickDispatcher` 接入 `install()`，作为第二喂入源提供交叉验证心跳——
     主喂入源仍是模块自持的 500ms 轮询（6.6.0 起就没有可依赖的宿主 tick）。

3. **director 观察者与当前条目访问器**。
   - 观察者接口 `F0` → **`D0`**；注册 `l0(E0)` → **`B0(D0)`**、注销 `z0` → **`m0(D0)`**。
   - 当前条目访问器：`P()`/`D()`/`F()` 在 `PlayDirectorServiceV3` 上**全部消失**，
     唯一返回真 `Video$e` 的是 **`C()`**。注意该类里还有 `D(Z)Z`（无关布尔方法），
     所以 `DIRECTOR_CURRENT_VIDEO_METHODS` 里 `C` 必须排最前，否则会误命中。

4. **director 服务取法**：`PlayerSeekWidget3` 不再暴露任何返回 director 类型的方法。
   唯一入口是**容器** `cK1.e`（`extends tv.danmaku.biliplayerv2.h`）的 `B()` → `service.y`。
   缺 `B` 与 `service.y` 就是 `directorFromHost` 在 6.7.1 上恒 MISS 的根因。
   （该探针 MISS 本身不致命——主链路走 `directorService` hook 已命中。）

5. **aid/cid 载体迁移** —— 语义靠宿主自己的装配代码实证。
   `Video$e` 的 `D()` 从 `Video$b` 改返 **`KL1.f`**。判定字段语义不靠猜：
   宿主 `KL1.b` 装配 `P2PManuscriptInfo` 时逐字段赋值（smali 实证）——
   `f.c` → `setAvid`、`f.d` → `setCid`、`f.b` → `setEpisodeId`。
   `Video$a`（`a`/`b`）仍是 aid/cid 的兜底（`gemini.base.player.a#w()` 用 aid/cid 喂它，
   但该方法 `@Nullable`）。
   - 教训：**新载体的字段语义优先从「宿主如何消费它」反推**，比对字段名更可靠。

6. **moss 方法描述符换包**：`MossInterceptor$e` 仍在，但字段类型
   `b`=`xr1.g` → **`Is1.g`**、`a`=`xr1.k` → **`Is1.k`**。
   `Is1.g` 布局与 `kr1.g` 一致（`a`=packageName，`b`/`c`=service/method），
   13 个字段含 `KClass`/`SerializationStrategy` 两个 kotlinx 特征字段，形状可辨识。
   缺这项时 `computeWantedService()` 的 svc 恒 null → IP 属地评论区限定改写全部落空。

## 真机验证暴露、静态分析没覆盖到的三处

这三处**都不是本轮改动引入**，是宿主自身漂移，但只有真机跑起来才看得到：

1. **`GRIPPER_ACCOUNT_CLASS`**：`account.d` 只剩 `SuspendProducer` 三件套
   （`create`/`invokeProducer`/`prepareParams`），不再声明 `getAccessKey()`。
   6.7.1 上实现 `GAccount` 的**只有 `account.f`**。
   → 原代码「取第一个能加载的类」会加载到 `d` 然后静默 MISS。改为按候选顺序取
   第一个**真的声明了无参 `getAccessKey`** 的类。
   - 教训：**候选表遍历不能只判「类存在」，要判「目标方法存在」**。

2. **`MORE_PANEL_LAYOUTS` holder 接口**：`gemini.ui.i` 变成 `RecyclerView.n`（ItemDecoration），
   item/holder 接口迁到 **`gemini.ui.j` / `gemini.ui.j$b`**。三元组整组校验失败 →
   `morePanelAdapter` MISS。补 6.7.1 三元组（adapter 仍是 `gemini.ui.f`）。

3. **`ROUTER_CLASSES`**：`blrouter.Router`/`BLRouter` 不存在，框架重构为
   `internal.bridge.*`（`AbstractChainBridge` 链式桥）+ `compat.generated.*`（KSP 生成）。
   但**顶层模型仍是真名**，且有唯一收口点：
   `t40.c#m(Context, RouteRequest) -> RouteResponse`——14 个公开跳转方法（`i`/`o`/`c`/`d`/`l`/`p`…）
   内部全部汇聚到它，且被 8+ 个包的业务代码直接调用。
   - `t40.c` 是纯混淆名，原启发式关键词（uri/route/open/handle/jump/navigate）一个都不命中，
     必须显式定位。
   - `RouteRequest` 不实现 `CharSequence`/`Intent`，取 Uri 要按 `a`/`f`/`h`/`j` 四个候选
     **且返回类型必须是 `android.net.Uri`**（同名 `g()` 返回 `ArrayList`，只按名字会误命中）。
   - 门面返回 `RouteResponse`，拦截后必须造 `Code.OK` 实例截断：返回 null 会被调用方
     当失败走错误分支，`proceed` 又会真的路由一次。

## 待继续

- `k:send` 未命中：K/gRPC 发送入口已迁出 `xr1.j`/`kntr.base.moss.api.c`。
  主 MOSS `PlayerMoss.playViewUnite` 正常，**番剧解锁与下载不受影响**。
  定位成本高（`onNext` 是通用回调模式，需从 `PlayViewUnite` 请求特征反向找）。
（`morePanelRefresh` 已在本轮后续解决，见下节。）

## 验证证据

```
探针：主进程 53/60（改前同宿主 52/60），下载进程 5/6

idsPrimary #1: aid=98935548 cid=168885122 (typed host path)   ← KL1.f 路径命中
rebindFeedIds #1: context=51134176 aid=98935548 cid=168885122
segments fetched aid=98935548 video=BV14741127BN count=8
seekTick feed #1 pos=50927 → #51 pos=51915 dur=1800000
auto-skipped segment=0-30015 category=intro                     ← 真实跳过片头 30.015s
unlock:akCapture #1: len=220                                   ← access_key 捕获恢复
Hooked URI router: t40.c.m  →  uriRouter : OK 3 methods
「我的」页 Bili2233 菜单项注入正常、设置面板正常弹出
```

## 复现

见 `docs/APK_6.7.1_DRIFT.md` §4（本机文档，含 apktool `-r` 与 jadx 分片的完整命令）。
关键点：**apktool 必须加 `-r`**（全量资源表 G1 提交内存失败）、
**jadx 必须按 dex 分片 + `-Xmx1500m`**（34 dex 一次性载入 OOM）。

## 后续：morePanel 面板注入迁到 Compose 时代的渲染收口点（已解决）

`morePanelRefresh` 那条路在 6.7.1 上**不是改名，是架构没了**：
面板整体 Compose 化（`PlayerSettingComposeKt` / `PlayerSettingFunctionWidget2`），
`gemini.ui.f` 降级成 `EmptyViewComponent`（占位空视图），`MenuService` 只负责分享面板。
Compose 没有「adapter 持有 List → 全量刷新」这种结构。

但渲染仍收口在一个静态方法上：

```
MenuService.`showNewMenuInternal$2`
  ├─ `menu$1`     往 List<h> 加分享行
  ├─ `setting$1`  往 List<h> 加设置组（含 VideoSettingButtonComponent implements gemini.ui.j）
  └─ `$3`         channel.c.a(Context, CoroutineScope, List<h>) -> RecyclerView   ← 新 hook 点
```

`h` = `com.bilibili.app.gemini.ui.h(j, int, Function1)`，字段 `a` 就是 `gemini.ui.j`。
所以注入方式变成：**hook `channel.c#a`，proceed 之前往 List 里追加我们自己构造的 `h(j=代理)`**。

两个必须注意的点：

1. **必须带内容判据**。`channel.c#a` 被 5+ 处复用（下载清晰度面板、弹幕反馈、
   StoryMenuService、AI 音频切换），无条件注入会污染这些面板。判据 = 列表里出现
   `...setting.channel.VideoSetting*Component`（只有播放器面板用这个前缀）。
2. **旧 adapter 仍在**。`gemini.ui.f` 在 6.7.1 能加载（只是语义变了），所以
   不能只在「类找不到」时回退 Compose 路径 —— 必须在「刷新方法找不到」时也回退，
   否则注入静默失效。

复用点：`gemini.ui.j` 与模块已在用的 holder 接口 `gemini.ui.j$b` 是同一套，
`buildRowItem` 的四条 when 分支（`b(Context,ViewGroup)` 建行 / 2 参绑定 /
`a()` 返回 viewType key / toString-hashCode-equals）全部原样可用，行视图代码零改动。

真机验证：
```
morePanelCompose : OK   com.bilibili.playerbizcommonv2.widget.setting.channel.c#a
morePanelInjectedCompose <- size=19
morePanel row clicked
```
播放页「⋯」面板底部出现「空降助手 → 打开面板」，点击后模块面板正常弹出。

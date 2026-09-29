# 目标 APK 分析：bilibili 6.6.0（`com.bilibili.app.in`）

> 本文档是 **6.6.0 适配的 Hook 事实来源**，格式对照 `docs/APK_6.5.0_ANALYSIS.md`。
> 结论基于离线静态分析（`tools/dexscan` + build-tools 36.0.0 dexdump，2026-09-29），
> **尚未在真机上验证**；真机结论请回写 `docs/STATUS.md`。
> 只记录 6.5.0 → 6.6.0 的**增量漂移**；未提到的 hook 点沿用 6.5.0 分析文档的结论。

## 1. 目标包事实（静态实测）

| 项 | 值 |
| --- | --- |
| 目标文件 | `E:\ctf-aaa\bili\bilibili_6.6.0\`（adb 真机拉取：base.apk 336MB + arm64 + xxhdpi） |
| 应用包名 | `com.bilibili.app.in` |
| versionName / versionCode | `6.6.0` / `9130300` |
| dex 数量 | 34 个 `classes*.dex`（6.5.0 为 33 个） |
| 反编译索引 | `E:\ctf-aaa\bili\decompiled-660\index.tsv`（2,700,976 行 / 329MB） |

**dex 大迁移**（相对 6.5.0）：播放器族 classes17 → **classes18/28**，「更多」面板 classes21/12 → **classes11**，
moss/gRPC 族 classes25/26 → **classes25**。按 dex 号找类的老经验全部作废，一律走索引。

## 2. 6.5.0 → 6.6.0 漂移总览

| 能力 | 6.5.0 | 6.6.0 | 处置 |
| --- | --- | --- | --- |
| 观察者注册 | `PlayDirectorServiceV3#j0(E0)` / `#z0(E0)`；`E0 extends z0`，回调 `a()/b(Video$e,Video$e)/c(Video$e)/e(Video$e)` | 同类同名同形，只是 **j0→l0**、**E0→F0**（`F0 extends A0`，回调 a/b/c/e 形状不变）；`z0` 名字保留且仍是注销 | 加候选 `l0`；观察者接口按方法反推（见 §3.1） |
| 当前视频访问器 | `PlayDirectorServiceV3#D()→Video$e` | **D() 消失**，改 `#F()→Video$e`（两个 director 服务都有） | 候选 `{D, F}` |
| 播放器容器接口 | `bindPlayerContainer(Ltv/danmaku/biliplayerv2/f;)V` | 参数变 **`h`**（`h extends f`，`f` 仍存在；`h#t()` 返回 Context） | 容器类型候选 `{f, h}` |
| 「更多」面板刷新 | `gemini.ui.f#f0(List)` | **e0(List)**（f0 不存在；6.5.0 上 e0 也不存在，无歧义） | 候选 `{f0, e0}` |
| 进度回调（int） | `G(I,I)V`（三个 widget 同名） | **J(I,I)V**（base/control/Gemini 三个类同名同参） | 加候选 `J` |
| 进度回调（long，探针专用） | `j0(J,J)`（base）/ `k0(J,J)`（Gemini） | base **g0(J,J)**；control/Gemini 无 long 形态 | 加候选 `g0` |
| 时间扣减落点 | `setText(CharSequence, TextView$BufferType)` | 保留（base widget 23 个方法实测在列） | 不变 |
| moss 方法描述符类型 | `kr1.g`（字段语义 a=包名/b=服务名/c=方法名）/ `kr1.k` | **xr1.g / xr1.k**（字段布局同构；挂在父类 `MossInterceptor$e` 的字段 b/a 上） | 类型提示 +`xr1.g`/`xr1.k` |
| KMP moss 发送入口 | `ip1.h`（4 参 a，6.3.0 形态） | **6.5.0 起就已不存在**（650 索引同为 0 命中） | 不处理，见 §3.4 |
| blrouter | `Router`/`BLRouter` 类名不存在，`IntentHandlerActivity` 在 | 同 6.5.0（blrouter 包 445 类，Router/BLRouter 仍 0 命中，IntentHandlerActivity classes28） | 不变，真机确认 |
| 菜单数据模型 | `MenuGroup$Item` 字段完整 | 完整保留（id:J/title/uri/icon/needLogin:I/redDot:I/localShow:Z 等 24 字段） | 不变 |
| protobuf 身份类 | `com.bapis.bilibili.metadata.KMetadata` / `device.KDevice` | 保留（classes9） | 不变 |
| grpc.c 主改写路径 | `kntr.base.moss.ignet.impl.grpc.c#f(String,[B)` | **保留**（classes25，真名未漂移；grpc.c 字段 f:String/g:grpc.d 同 6.5.0） | 不变 |
| common-headers 拦截器 | `kntr.base.moss.ignet.impl.header.b#b(MossInterceptor$b, ContinuationImpl)` | 保留；`MossInterceptor$b#a()` 仍返回 `MossInterceptor$e` | 不变 |
| kr1.a 提供者兜底 | `kr1.a`（final，形状校验 isHeaderProviderBase） | `kr1.a` 仍在（kr1 包只剩 a/b/c 三类） | 不变 |

## 3. 关键证据（字节码实测）

### 3.1 观察者注册链（主链路）：j0→l0、E0→F0，z0 名字未变

索引（classes28）：

```
M classes28  Ltv/danmaku/biliplayerv2/service/F0;  a()V / b(Video$e,Video$e)V / c(Video$e)V / e(Video$e)V  public|code
C classes28  Ltv/danmaku/biliplayerv2/service/F0;  super=Object  ifaces=Ltv/danmaku/biliplayerv2/service/A0;
M classes28  Ltv/danmaku/biliplayerimpl/videodirector/PlayDirectorServiceV3;  l0(F0)V / z0(F0)V  public|final|code
M classes28  Ltv/danmaku/biliplayerimpl/videodirector/VideosPlayDirectorService;  l0(F0)V / z0(F0)V  public|final|code
M classes28  Ltv/danmaku/biliplayerv2/service/E0;  （interface，无父类、无任何方法 —— 空标记接口）
```

l0=注册 / z0=注销 的语义由**全部调用点**确认（d28.txt）：每一对 l0/z0 调用都落在
`onStart(m)` / `onStop()` 里（EJ1.b、service.D0、RemoteServiceHandler.t/u、interact.biz.p、
StatisticsService.onStart）——服务启动注册、停止注销，与 6.5.0 j0/z0 语义完全镜像。

**交接文档 §4.2-1 的 D0 假设被推翻**：`service.D0`（implements Z，含 J6/i7）字节码实测是
**SeekService**（`D0.e()` 遍历 `g:LinkedList` check-cast E0 后查「needHideSimpleProgressNormalCase /
getSegmentSwitchValue」，字段含 ControlContainerService；getSeekService() 返回的就是 Z）：

```
D0.J6(E0): iget g:LinkedList; LinkedList.remove(E0)          → J6 = 注销
D0.i7(E0): if (!g.contains(E0)) g.add(E0)                    → i7 = 注册（方向与交接文档假设相反）
```

它与视频切换分发无关，**不挂**。真机探针若发现主链路仍缺回调，再回来查这条线。

**F0/E0 跨版本撞名**（不能按名字排序选接口）：6.5.0 也有一个 `service.F0`
（classes26，媒体资源接口：`j(I,I,I)→MediaResource`、`f()→service.D`…，与观察者无关），
而 6.5.0 的观察者叫 E0；6.6.0 反过来。所以运行时**不能**用「F0 优先」的固定顺序，
必须按「候选接口能解析出注册方法」反推：对每个候选接口 iface 试
`forTarget(服务, ADD 候选, iface)`，能解析出方法的那个 iface 就是当前版本的观察者接口，
代理也实现它（见 §5 对 VideoDirectorListener 的改动）。

### 3.2 当前视频访问器 D()→F()

```
M classes28  Ltv/danmaku/biliplayerimpl/videodirector/PlayDirectorServiceV3;  F()  Ltv/danmaku/biliplayerv2/service/Video$e;
M classes28  Ltv/danmaku/biliplayerimpl/videodirector/VideosPlayDirectorService;  F()  Ltv/danmaku/biliplayerv2/service/Video$e;
```

6.6.0 全索引无任何 `D()`；两个 director 服务的 `F()` 是唯一返回 `Video$e` 的无参方法。
`Video$e#z()→Video$a`（a=avid/b=cid）不变，aid/cid 提取链路其余部分无恙。

### 3.3 容器接口 f→h

```
M classes18  Lcom/bilibili/playerbizcommonv2/widget/seek/v3/PlayerSeekWidget3;      bindPlayerContainer(Ltv/danmaku/biliplayerv2/h;)V
M classes18  Lcom/bilibili/playerbizcommon/widget/control/PlayerProgressTextWidget;  bindPlayerContainer(Ltv/danmaku/biliplayerv2/h;)V
C classes28  Ltv/danmaku/biliplayerv2/h;  super=Object  ifaces=Ltv/danmaku/biliplayerv2/f;  public|abstract
M classes28  Ltv/danmaku/biliplayerv2/h;  t()  Landroid/content/Context;
```

`h` 是 `f` 的子接口，`t()` 取 Context 保留。按名字列表 `{f, h}` 匹配参数类型即可双版本兼容
（同一版本内两个名字不会都命中同一方法：参数只能是其中之一）。

### 3.4 ip1.h（KMP moss 发送入口）：不需要重定位

`ip1.h` 在 **6.5.0 索引同样是 0 命中**——它是 6.3.0 时代形态，交接文档 §4.2-4 的担忧不成立。
6.5.0 真机基线（STATUS.md 2026-09-21）里 `ip.mossScope` 本来就是 5 个 MISS 之一，
评论区限定改写靠 `header.b#b`（common-headers scope 标记）+ `grpc.c#f` 主改写路径覆盖，
真机已验证足够。0.7.0 保留 ip1.h 旧候选（供旧宿主兜底）即可，不为 6.6.0 找替代。

### 3.5 moss 方法描述符：kr1.*→xr1.*，挂在 MossInterceptor$e

```
C classes25  Lkntr/base/moss/ignet/impl/grpc/c;  super=Lkntr/base/moss/MossInterceptor$d;   （$d 又 extends $e）
F classes25  Lkntr/base/moss/MossInterceptor$e;  a  Lxr1/k;    b  Lxr1/g;    c  [B    d  Lkntr/base/moss/MossInterceptor$a;
F classes25  Lxr1/g;  a/b/c = String（+KClass、序列化策略等 13 字段）
F classes25  Lxr1/k;  a = String（服务名）、b = I、c = Lxr1/e;
M classes25  Lkntr/base/moss/ignet/impl/grpc/c;  f(Ljava/lang/String;,[B)  V  public|final|code   ← 主改写路径完好
M classes25  Lkntr/base/moss/ignet/impl/header/b;  b(Lkntr/base/moss/MossInterceptor$b;,Lkotlin/coroutines/jvm/internal/ContinuationImpl;)  Object
```

6.6.0 的 `grpc.c` 自身字段只有 f(String)/g(grpc.d)（与 6.5.0 相同），描述符在**父类** `$e` 上
（b=xr1.g 方法描述符、a=xr1.k 服务名兜底）——`fieldTypedAnyHint` 本来就沿类层级向上找，
只需给类型提示数组补 `xr1.g`/`xr1.k`。`MossInterceptor$b#a()` 返回类型仍是 `MossInterceptor$e`，
`resolveCtx` 的第一条 callNoArg 提示继续有效。

### 3.6 进度回调与面板（抽查结果）

- 三个 widget（base / control / Gemini）的 int 进度回调全部为 `J(I,I)V`（6.5.0 是 G）；
  base 另有 `g0(J,J)V`（6.5.0 的 j0）。`setText(CharSequence, BufferType)` 保留。
- `gemini.ui.f` 在 6.6.0 只有 `e0(List)`（6.5.0 只有 `f0(List)`），无重载歧义。
- MenuGroup$Item / KMetadata / KDevice 字段与方法形状均未漂移（见 §2 表）。

## 4. 方法论教训（本轮新增）

1. **index.tsv 列分隔是 TAB**：方法名列与方法返回列之间是 `\t` 不是空格。
   写 `名\(参数\)\t返回` 型正则时把分隔符写错会得到「全索引 0 命中」的假阴性
   （本轮 `D()`→差点误判 F() 链路）。宁可用 `q.py "^M\t...;\t名\("` 先确认行存在，
   再单独查返回类型。
2. **dexdump 大 dex 会丢 class_data**（§6 老坑，classes28 的 PlayDirectorServiceV3/D0 类块都缺失），
   但**方法体清单仍在**：dexdump 末尾按 `[addr] class.method:(sig)` 逐个列出有 code 的方法，
   `grep -n "类名.方法名:"` 定位后 sed 取块即可，不必换工具。
3. **同名类跨版本角色互换**（E0/F0）：任何「按候选名顺序取第一个能加载的类」的解析，
   在撞名场景都会装错接口。改为「方法能解析 ⇔ 接口正确」的自洽校验。
4. 交接文档的静态结论也可能有方向性错误（J6/i7 的注册/注销方向），
   一切以**方法体字节码 + 全部调用点的上下文**为准。

## 5. 对代码的影响清单（0.7.0 实际改动）

| 文件 | 改动 |
| --- | --- |
| `host/HostTargets.kt` | `PROGRESS_CALLBACK_INT_METHODS` +`J`；`PROGRESS_CALLBACK_LONG_METHODS` +`g0`；`CONTAINER_INTERFACE`(const) → `CONTAINER_INTERFACES`{f,h}；`DIRECTOR_ADD_OBSERVER_METHODS` +`l0`；`DIRECTOR_OBSERVER_INTERFACE`(const) → `DIRECTOR_OBSERVER_INTERFACES`{E0,F0,legacy}（顺序无关，配 §5 视频监听器改动）；`DIRECTOR_CURRENT_VIDEO_METHOD`(const) → `DIRECTOR_CURRENT_VIDEO_METHODS`{D,F}；`MORE_PANEL_REFRESH_METHOD`(const) → `MORE_PANEL_REFRESH_METHODS`{f0,e0}。全部旧候选保留 |
| `BiliSponsorBlockHooks.kt` | `hookDirectorService` 形状校验的参数类型谓词改用接口候选集合；`hookContainerBinding` 的参数类型谓词改用容器候选集合 |
| `player/VideoDirectorListener.kt` | `registerDirectorService` 改为「对每个候选接口解析注册方法，取自洽对」；`currentVideoOf` 用新候选列表 |
| `hook/MorePanelInjector.kt` | 刷新方法按候选列表匹配 |
| `hook/IpLocationHooks.kt` | `computeWantedService` 两处类型提示数组 +`xr1.g` / +`xr1.k` |

版本号两处同步：`app/build.gradle.kts`（0.7.0 / versionCode 10）+ `META-INF/xposed/module.prop`。

## 6. 尚未确认 / 需要真机探针

1. ~~HookProbe summary 是否 ≥ 33/38 基线、主链路（director/ids/progress/seek/container）0 MISS。~~
   **已确认（2026-09-29 真机）**：安装期 32/37 + 2 延迟 MISS，逐键对账与基线等价（差值 =
   +cleartextPolicy 新 OK − Gemini k0 − seekTrack:f，后两者为 6.6.0 宿主侧消失），
   主链路 0 MISS；详见 STATUS.md「6.6.0 真机验证」。
2. ~~`bilisb://settings` 在 6.6.0 是否仍能经 IntentHandlerActivity 打开~~：`uriRouter <- 2 methods`
   安装命中（IntentHandlerActivity 在 classes28）；运行时拦截待详情页恢复后复验（直绑是主路径）。
3. `l0` 注册的 F0 代理能否真收到 b/c/e 回调（即 6.6.0 的视频切换仍经 PlayDirectorServiceV3 派发）
   —— 待宿主详情页恢复后补验（当前宿主详情页黑屏为宿主侧问题，模块禁用对照已确认）。
4. MenuService → `doMorePlayerSetting` → gemini.ui.f 面板链路是否漂移（§2 未列，索引未见异常）
   —— 待详情页恢复后补验。
5. seek.v3.g 的 draw(Canvas)、XA0.a、PegasusViewModel 等增强 hook 的运行时命中情况
   —— 安装期全 OK；运行时回调待详情页恢复后补验。

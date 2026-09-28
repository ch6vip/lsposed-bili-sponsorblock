# Agent Note: 第二轮全量审查交叉确认修复

Status: implemented

## Problem

四路审查交叉核对后，上一轮已修项大多仍在，但又确认一组会让跳过叠请求、观察者泄漏、局域网 HTTP 静默失败、STREAM_MUSIC 卡死或 seek 回绕的缺陷：

- `scheduleSegmentFetch` 无条件 `inFlight.add`，TTL 过期重拉与 `onVideoIds` 并发各打一枪；key 还用 `bvid:cid`，和仓库的 bvid 缓存键对不上。
- `VideoDirectorListener.unregister` 只从弱表 `remove(widget)`，键其实是 director 服务；`DIRECTOR_REMOVE_OBSERVER_METHODS`（z0）从未调用。
- 模块 `targetSdk 35` 未声明 cleartext / NSC，自定义 `http://` 实例在模块进程被系统拦截。
- unmute 拿不到 AudioManager 仍清账并返回 true，后续 mute 短路，STREAM_MUSIC 卡静音。
- 倒计时浮层 Activity 销毁时停 ticker 却不调 `onCancel`，skipped 标记留着，该片段再也不跳。
- `seekTo` 直接 `Long.toInt()`，超 `Int.MAX_VALUE` 折成负数把播放头拽回片头。
- `parseCacheTtlMs` 用 `Float * Long`，上限 10080 分钟可能溢出成 0。
- 面板卡片硬编码浅底，深色主题标题走主题浅色，对比度不够。
- `UserIdentityStore` 日志打 userId 前 8 位，与其它路径的 4 位脱敏不一致。

## Decision

inFlight 按 bvid 去重：非强制刷新已在途则复用；刷新先抬 `fetchGeneration` 再开新任务，旧 finally 用 `compute` 对 generation 摘键。离开播放页从 host 解析服务（或 lastService）调 z0，接口类型用注册时记下的 `E0`，成功后才从表摘掉。模块进程 NSC 放行 http；宿主进程只 hook `isCleartextTrafficPermitted(String)`，且仅匹配当前自定义 http host。unmute 失败时进度路径把 hash 放回，teardown/close 清账并用 Application AudioManager 兜底。倒计时宿主销毁只停 ticker，不走用户 `onCancel`（那会把 skipped 钉死）。seek 夹到 `[0, Int.MAX_VALUE]`。TTL 用 Double 换算。面板按 night 换深色卡片。userId 日志一律前 4 位。

## Alternatives considered

- **inFlight 继续按 videoKey**：切 P 会重复打同一 hash 前缀接口；仓库已经按 bvid 缓存，去重必须对齐。
- **只靠 WeakHashMap 回收观察者、不调 z0**：服务长期活着时代理一直收到切集回调，跨页串台。
- **z0 失败也从表摘掉**：下次 register 会再挂一个代理，同片重复 onVideoIds、清 skipped。
- **禁止 http**：局域网自建实例是明确支持的；模块 NSC 管不了宿主进程，必须另 hook 策略。
- **hook 无参 isCleartextTrafficPermitted**：会放开整个 B 站进程的明文流量，只开带 hostname 的重载。
- **unmute 失败仍清账、指望下次 mute 重试**：mute 见账就短路，流再也打不开。
- **宿主销毁走 onCancel**：与用户点取消共用回调，会把 skipped 钉死；`!isShown` 还会误伤。

## Consequences

- 强制刷新会叠一条在途请求，旧写入被仓库 generation 丢掉。
- z0 找不到时观察者仍挂在服务上，表里也还在，不会重复挂；依赖下次成功 z0 或 WeakHashMap。
- 模块进程允许任意 http；宿主进程只放行当前设置里的那一个 http host。
- 倒计时宿主销毁不清 skipped；离开片段仍由 controller 的 `cancel()` + `skipped.remove` 处理。
- teardown 没 AM 时仍可能留下 STREAM_MUSIC 静音，Application 兜底覆盖 detach widget 的常见路径。

## Verification

`:app:testDebugUnitTest` 覆盖 TTL 上限、过期 lastKnown、seek 夹取、cleartext host 匹配。

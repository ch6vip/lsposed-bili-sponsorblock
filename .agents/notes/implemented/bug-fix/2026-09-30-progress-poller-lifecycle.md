# Agent Note: 6.6.0 进度轮询的「静默零跳过」——三处失效形态与抽表重构

Status: implemented
Date: 2026-09-30
关联: docs/STATUS.md「发布前收口」、docs/ROADMAP.md M13、.agents/notes/implemented/reverse/2026-09-29-host-660-adaptation.md

## 为什么动它

6.6.0 的自动跳过**完全依赖**模块自持的 500ms 轮询：那一轮真机排查已经确认宿主侧没有任何
可依赖的进度 tick（三个 `PlayerProgressTextWidget` 不实例化、`seek.v3.g#draw` 播放期间不逐帧走、
`D0$c.run` 只在 seek 后打一炮）。也就是说，这段轮询一旦停摆：

- 现象 = **零跳过 + 零标记**；
- 它与「服务端拉不到片段」在日志上**完全无法区分**（拉取失败那条线有 `status=` 日志，
  但轮询停摆只有 `seekTick feed` 心跳的沉默）。

上一轮就是靠整晚加心跳逐层排查才定位到「宿主没有 tick」，所以对这条命脉的要求不是「能跑」，
而是**停摆必须可观测、且不能因为一次瞬时状态变化永久停**。

## 三处失效形态（都读代码发现，不是真机复现）

1. **两次读 `sponsorBlockController`**。任务体里守卫读一次（非 null 才继续），喂入前又读一次。
   `applySnapshot` 重建 controller 是「先换引用、再 close 旧的」，两次读之间可能拿到 null；
   旧实现那条 `coreForContext == null` 的**自停**路径会 `remove + cancel` 摘掉自己的表项 ——
   而该 context 之后不一定还有 bind 来重启它（`ensureRebindAfterTeardown` 走的正是没有 bind 的路径）。
   结果：本会话剩余时间静默零跳过。

2. **「表项在、任务已停」把后续起表请求全部挡掉**。旧写法
   `pollerFutures.remove(h)?.cancel(false)` 里 `remove` 返回 null 时安全调用短路，
   `cancel` 根本不执行；再加上自停留下的死表项，`startProgressPoller` 的 `containsKey` 守卫
   会静默失败 —— 起表请求被「一个已经死掉的登记」挡掉。

3. **观测盲区**。只有心跳的沉默能反推喂入停了；轮询异常还被 `catch (t: Throwable) {}` 静默吞掉
   （注释理由是「外抛会静默杀死周期任务」，理由对，但吞掉异常同样静默）。

## 放弃的方案与取舍

- **不用锁去堵竞态，而是抽表**：`ProgressPollerRegistry` 把「起表/停表/自停」的语义集中，
  用 `@Synchronized` + 自引用持有者 `arrayOfNulls<ScheduledFuture<*>>(1)` 解决
  「任务体要拿自己、而 lambda 在 future 生成前就构造好」的鸡生蛋问题。
  自停只摘「自己」那一项（`thisFuture` 身份比对），避免误杀一次新起表装上的任务。
- **不用 `computeIfAbsent` 递归重试**：写过一版，发现「任务自停」与「start 装新任务」在同一
  同步块外交错时会互相取消，不如显式同步 + 身份比对直白。
- **不把自停条件改成「controller 为 null」**：那样 controller 重建窗口又会变成自停理由。
  最终自停条件收窄成**只看 handle 还在不在**（controller 为 null ⇒ handle 必然不在）。
- **保留「回调期间 controller 重建」的窗口**：一次 tick 只读一次 controller 后，
  最坏结果是那 500ms 的一次喂入被丢弃，而不是永久停摆 —— 可接受。

## 落地改动

- 新增 `player/ProgressPollerRegistry.kt`（纯 JVM）+ 6 例单测（真实定时器，不 mock 调度）。
- 新增 `player/ObserverRegistrationGate.kt` + 6 例单测：把「登记必须先于 invoke」的顺序契约
  从 `VideoDirectorListener` 里抽出来。那是 2026-09-29 详情页黑屏 + 输入 ANR 的根因，
  且**已经被一次审查改动破坏过一次** —— 顺序在代码里看不出来，只有造一次重入才能证明它。
- `BiliSponsorBlockHooks`：起表路径补 `ensureRebindAfterTeardown`；`applySnapshot`
  关 controller 时 `stopAll()`、按新 controller 的 handle 表重启；新增
  `pollerMissingForHandle` / `progressPollerError` 探针；`feedTickProgress` 收 controller 参数。
- `SponsorBlockController.activeContextHashes()`：供「按 handle 表重启」用（不能用旧表项反推）。

不变式：**有 handle ⇒ 有 poller**（三条起表路径：bind / 补绑 / controller 重建；
两条停表路径：handle 消失 / controller 关闭）。

## 验证状态

- `:app:testDebugUnitTest` 167 例 0 失败（新增 34 例）；`:app:assembleRelease` 出签名包（0.7.1/11）。
- **真机未复核**：0.7.1 装机后需要看的探针是 `pollerMissingForHandle`（不该出现）、
  `seekTick feed #n` 心跳是否持续、以及改设置瞬间（controller 重建）之后是否仍在喂入。

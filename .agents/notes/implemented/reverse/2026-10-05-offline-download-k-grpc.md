# Agent Note: 离线下载解锁的根因定位（K/gRPC 播放链路）— 实现已暂停

Status: paused —— 调查完成，实现未做（用户 2026-10-05 决定暂停该功能）

## Problem

「缓存/下载解锁」在受限番剧上表现为：权限位补丁生效、「缓存」入口出现、任务能建
（`/sdcard/Android/data/com.bilibili.app.in/download/s_91755/` 下 80 个 `entry.json`），
但列表立刻显示「已暂停：缓存失败，请删除重试」，`download/` 下没有任何媒体文件。

## 根因（静态确证，非推测）

宿主 6.6.0 有**两套互相独立**的播放传输：

| 传输 | 类 | 用于 |
| :--- | :--- | :--- |
| MOSS（`com.bilibili.lib.moss.api`） | `com.bapis.bilibili.app.playerunite.v1.PlayerMoss` | 播放器链路（我们一直只挂这条） |
| K/gRPC（`grpc.biliapi.net`） | `com.bapis.bilibili.app.playerunite.v1.KPlayerMoss` → `xr1.j` | **离线下载引擎** |

下载引擎在 `:download` 进程里**自己取流**：`video.biz.offline.base.infra.download.TaskGroup`
（`TaskGroup.java:644-648`）`new KPlayerMoss().playViewUnite(req, continuation)`；
`KPlayerMoss.java:42-45` 方法描述符 `bilibili.app.playerunite.v1 / Player / PlayViewUnite`，
`:101-103` 建 `xr1.j("grpc.biliapi.net", …)`。该调用**不经过我们挂的 MOSS 类**，因此无人拦截 →
引擎拿到宿主自己的区域受限响应 → 流为空 → `TaskGroup.c()` 汇总为空 →
`TaskGroup.java:400-403` 置 `ADDRESS_EMPTY`（异常路径见 `TaskGroup.o(Exception) :702-722`，
最多重试 3 次）→ UI 显示「缓存失败，请删除重试」（该文案是 arsc 资源 `0x7F123822`）。

引擎对响应的**硬性要求**（`TaskGroup.c()`，`:232-403`）：`reply.vod_info` 非空 →
`vod_info.stream_list(5)` 非空 → 至少一条 Stream 的 `dash_video(2)` 带
`base_url(1)`/`backup_url(2)`；`quality ≤ max(qn,16)`；写任务用 `md5(5)/size(6)`、
`dash_audio(6)`（可缺）、`timelength(3)`。**不读** `play_arc_conf`、不读 supplement/PGC Any、
不读 `play_arc.right`（`playershared.PlayArc` 里本就没有 `right` 字段）。

对比参考实现：BiliRoaming **没有 hook 任何离线下载类**（全源码 grep
`video.biz.offline|KPlayerMoss|VideoDownloadService` 零命中），它只挂 `PlayURLMoss`；其下载能力只有
`R0.j`（回包 rights）与 `R0.p`（season JSON）两处。也就是说这条链参考实现也覆盖不到。

## Decision（未实施，仅记录方案）

拦截点选 **`xr1.j.b(descriptor, req, ser, deser, callback, h, protoBuf)`** —— K 侧唯一发送入口，
一次拿到方法描述符、请求对象与响应回调（`xr1.m` 由它内部 new 出来，回调可换成代理）。
响应替换两种形态待定，取决于真机实测：受限时是 `onError` 还是「成功但 `streamList` 为空」。
字节层可复用现有 `ResponseReconstructor`（java proto 对象 → `toByteArray()`）：
`com.bapis.bilibili.app.playerunite.v1.d`（KPlayViewUniteReply）的 `@ProtoNumber`
与 `app/src/main/proto/bilisb_unlock.proto` **逐项一致**，K 侧解码器按同号 schema 能吃。

## Verification（已完成的实测）

- 真机 `JJHAV8BENRPNBICM`：`:download` 进程里 `hook ok: k:send <- b(7)`（主进程同）—— 
  说明拦截点可挂；但**未观察到 `k:req`**，因为本轮下载任务处于失败态未重试，没能触发引擎的 K 调用。
- 已排除：请求能力位（与 BiliRoaming `D0/E0` 逐项一致：`fnval=4048` + `fourk` + `download=0`）、
  响应 rights 字段、`playershared.ArcConf` 缺下载位（该消息只有
  is_support=1/disabled=2/extra_content=3/unsupport_scene=4/unsupport_state=5）。
- **环境变化**（2026-10-05 晚）：同一标题 `season 91755` 的宿主响应从 `RESTRICTED(dialog=area_limit)`
  变成 `NORMAL_PGC`（`viewTab:skipHasPanel`、`realUrl` 已可用），设备出口未变
  （WiFi `192.168.6.136`、移动数据关闭、无代理/VPN、`biliintl.com` DNS 钩子未触发 rewrite）。
  因此该样本当前无法复现「受限」，下载链路的验证需要另找仍受限的样本或在受限网络下进行。

## Consequences

- 已落地的相关修复（与本功能无关，均已验证）：缓存页渲染（`show_title`/每集 `rights`/
  `section.id` 取索引/不写 `episode_ids`）、`:download` 进程最小 Hook 集、
  宿主同步取地址抛异常时的漫游兜底 + cid→ep_id 反查。
- 工作树里保留了 `KPlayViewHook`（**只观测、不改行为**）及其在两处进程的安装；
  若确定不再继续，可整体回退该文件与 `BiliSponsorBlockHooks` 里的两行安装。
- 恢复该功能时的第一步：让下载任务真正重试一次，抓 `k:req` / `k:resp` / `k:respErr`，
  据此决定替换形态。

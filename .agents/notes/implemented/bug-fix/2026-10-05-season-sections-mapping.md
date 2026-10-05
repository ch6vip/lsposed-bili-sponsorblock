# Agent Note: 选集分区映射修正（缓存页加载失败）与真机验收

Status: implemented

## Problem

解锁线的搜索/缓存改动此前只有 JVM 单测证据，没有真机证据；「缓存/下载解锁」这一头条能力从未在宿主上跑通。

真机验收时，受限标题（哆啦A梦 第五季，`season_id=91755`）的缓存页显示「页面加载失败，请重试」。
同一模块下非受限标题（The Eternal Strife，`NORMAL_PGC`）缓存页原生正常（正片 1-16 话、缓存全部、
查看缓存、剩余 58.2GB），构成 A/B 对照 —— 说明失败来自模块注入的内容，而不是缓存页本身。

根因：`SeasonParser.parseSections` 按 `result.seasons[]` 建分区，而 CN `pgc/view/web/season` 的真实形状是
**正片在 `result.episodes[]`**（该标题 80 话），花絮/PV 在 `result.section[]`，`result.seasons[]` 只是
「其他季」摘要条目（无 episodes）。于是注入得到「42 个分区 / 0 条剧集」，缓存页判定无内容。

## Decision

分区分两类构造：**正片**由 `result.episodes[]` 构造（`section_id` = `season_id`），**花絮**由
`result.section[]` 构造（`section_id` = `section.id`，`type` 取接口值）；`result.seasons[]` 降级为
「两者皆空」时的形态漂移回退（独立函数 `parseSeasonSummaries`）。

注入字节不按「猜对」，按**宿主原生实拍**对齐（2026-10-05 抓取非受限标题 The Eternal Strife 的
`seasonSectionsForCache` 回复：`id=1, section_id=581472, type=0, title="选集", episodes=16`，且
`episode_ids(6)` 为空）。故 `buildSeasonSectionsReplyBytes` 以 1 基索引写 `id`、不再写 `episode_ids`；
`buildSeasonEpisodeBytes` 补 `rights(22){allow_download=1,allow_dm=1}`、`section_index(32)=1`、
`show_title(44)`，以及 `bvid(24)/link(26)/pub_time(29)`。其中 `section_id` 是服务端不透明 id
（既非 season_id 也非 media_id），宿主在 `pageSectionEpisodes` 里原样回传，本模块按它自查即可自洽。

字段号不凭记忆，用 jadx 从宿主 6.6.0 `base.apk` 取权威值：`SeasonSectionsReply.sections=1`；
`SectionData` id=1/section_id=2/title=3/episode_ids=6/episodes=7/type=13；
`viewunite.common.ViewEpisode` 1..46（与本模块既有映射逐项一致；其 `rights(22)` 为
`viewunite.common.Rights`：allow_download=1/allow_review=2/can_watch=3/allow_dm=5/area_limit=7）。
另确认 `ViewMoss.seasonSectionsForCache` 与 `seasonSections` 返回同一 `SeasonSectionsReply`，
排除「硬编码 reply 类导致宿主强转失败」这一假设。

保留原生基线探针：`seasonMoss:shape` / `seasonMoss:capture`（落盘原生回复）与
`seasonMoss:captureOut`（落盘本模块重建字节），用于逐字段比对。

## Alternatives considered

- 沿用 `seasons[]` 再补一个正片分区：42 个空分区已经进了响应，且仍需猜字段，治标不治本。
- 改用 BiliRoaming 的 JSON 层做法（拦截 `pgc/view/web/season` 的 HTTP 响应做 JSON 补丁）：能绕开 protobuf 构造，
  但要在宿主自有 DNS/gRPC 栈之外新增 HTTP 拦截点，改动面远大于修一个映射函数；放弃。
- 只修映射、不加探针：省一次构建，但下次形状漂移又要从零摸索；保留探针成本很低（每进程 3 份 bin + 3 行日志）。

## Consequences

正片分区形状以**宿主原生实拍**为基准对齐（`id` 取 1 基索引、`title="选集"`、`type=0`、
不写 `episode_ids`、每集带 `rights{allow_download=1,allow_dm=1}` 与 `section_index=1`、
行标签写 `show_title`），缓存页由此才可渲染；`seasons[]` 回退路径保留，
接口形状漂移时仍有降级而不是空响应。

## Verification

真机 `JJHAV8BENRPNBICM`（corot / Android 16 / KernelSU+LSPosed），宿主 `com.bilibili.app.in` 6.6.0
（versionCode 9130300），release 覆盖安装（签名指纹一致）：

- 钩子装载 + `hook summary: 42/47 hit`；区域页签注入（`nav+1 type=810 label=台`，UI 为 `综合|番剧|台|用户`）。
- 原生番剧 type=7 直通（无 `markerHit`）；区域搜索 `markerHit type=810 page=1` → `rebuilt page=1 7653B` 并渲染。
- 分页游标往返：关键词 "a" 上滑触发 page 1→2→3，`rebuilt` 7343B/7570B。
- 错误路径：拒连 → `fetchFail: ConnectException` → `onError` 成功 → Toast；黑洞 → `SocketTimeoutException`，
  耗时 5.010s / 5.006s（超时上界生效）→「加载失败，请稍后再试」+「再试一次」。
- 设置热重载（同进程下次请求即生效）与恢复；关闭搜索解锁后页签回到 `综合|番剧|用户`，原生响应照常渲染。
- 缓存权限位：受限标题触发 `viewTab:downloadRights`，详情页出现 `id/download_text`=「缓存」入口，`unlock:reqPatched` 生效。
- 本机实测 CN `pgc/view/web/season?season_id=91755` 形状：`episodes=80` / `section=1` / `seasons=42`（映射依据）。

**后续（同日续，设备重连后）**：缓存页由「页面加载失败」变为**正常渲染**——
`画质 1080P / 正片|PV / 1..7 / 缓存全部 / 查看缓存 / 剩余 58.1GB`。过程中又修掉三处：

1. **探针误插导致的宿主崩溃**：`transform()` 里 `captureSections` 把 `transformSections` 整行替换掉，
   回调参数被写成 `kotlin.Unit`，宿主 KTX 续体在 `onCompleted` 恢复时 `ClassCastException`（进程重启）。
   已修，并在回调代理加护栏：**只有新对象与原件同类才替换**，异类/空值一律不写回。
2. **`:download` 子进程此前被 `Entry` 一律跳过** → 下载引擎所在进程没有任何 Hook。
   现该进程只装最小集（`PlayViewHook`：playViewUnite + AK 捕获），其余 UI/进度/搜索类一律不装。
3. **宿主同步取地址直接抛异常**：`PlayerMoss.executePlayViewUnite(req)` 同步返回 `PlayViewUniteReply`
   且声明抛 `MossException`，受限内容上真机抛 `BusinessException: 抱歉您所在地区不可观看！`；
   我们原先 rethrow → 从不进入漫游。现改为 `rebuildAfterHostFailure()`：按「无原始响应」重建
   （`rebuildReply(null,…)` 本已支持），并用 `ViewTabHook.epIdForCid` 由 cid 反查 ep_id
   （PGC 播放接口只认 ep_id，只带 cid 会被上游按 -10403 拒）。

真机证据：`syncFail: download=true ep=0 BusinessException` → `hostFailEp: cid=29045623909 ep=1522361
resolved=true` → `proxied area=hk quality=125 streams=16 audio=3 … from=hostFail`。

**仍未打通**：实际媒体传输。任务能建（`download/s_91755/` 下 80 个 `entry.json`），但下载列表显示
「已暂停：缓存失败，请删除重试」，无媒体文件。已用反编译排除：请求能力位、响应 rights 字段、
`playershared.ArcConf` 缺下载位（该消息只有 is_support/disabled/extra_content/unsupport_scene/
unsupport_state）、`playershared.PlayArc` 无 `right` 字段。下载引擎消费侧的要求仍待查。

本次实测：`:app:testDebugUnitTest` 284 用例通过。其中
`ProgressPollerRegistryTest.自停与重启动交错不会留下死表项` 是**既有偶发**用例（整跑偶失败、
隔离重跑必过，与解锁线无关），建议单独修。`:app:assembleRelease` 通过，apksigner v1/v2 通过，
签名指纹未变。

# 解锁功能复刻完成度评估（2026-10-04）

> 对照物：BiliRoaming `7c792dc8fe`（versionCode 1442）全量反编译
> （`E:\ctf-aaa\bili\BiliRoaming_7c792dc8fe_decompiled`，含隐藏 dex 全部 1128 个混淆类）。
> 本文所有结论以**双方源码逐项核实**为准，不转述项目旧文档（旧文档存在失真，见 §5）。

## 1. 总评

在 `UNLOCK_PLAN.md` 自选范围（解锁番剧播放 / CDN 加速 / 缓存番剧）内：
**播放解锁主线达成协议级对齐并两度真机验证（2026-10-02、2026-10-04）**，
完成度约 90%；CDN 线约 50%（仅响应层替换）；缓存线约 70%（请求侧通，权利位改写未做）。

相对 BiliRoaming **完整解锁功能矩阵**覆盖约 1/4~1/3——缺口大部分来自计划书 §6
明示的非目标（搜索、泰区字幕/评论、播放限制解除等），属范围裁剪而非欠账。

## 2. 已复刻且经源码核实

| BiliRoaming 功能 | 参考实现位置 | 本项目对应 | 核实结果 |
| --- | --- | --- | --- |
| playViewUnite moss 拦截（受限→漫游→重构） | `G0.b` + C0/D0/E0/F0 | `unlock/PlayViewHook.kt` | ✅ 双参回调包装 + 同步返回两形态；6 方法含 ForCache 变体 |
| 受限判定三源（dialog/end_page/is_preview） | `G0.g` | `unlock/PlayViewDecision.kt:61-83` | ✅ 逐源一致，另加 THAI_REDIRECT（cid 重定向）判定 |
| 漫游请求（th→intl 网关 bstar 身份；其余 `/pgc/player/api/playurl`+area） | `p1.g` | `unlock/RoamingClient.kt:54-96` | ✅ 路径/appkey(7d089525d3611b1c)/build(1001310) 完全一致 |
| 响应重构（自备 proto parse→改→宿主 parseFrom） | `G0.k/l` + 自备 proto 类族 | `ResponseReconstructor`+`WireWriter` | ✅ 同技术路线；字段号运行时实测固化在 bilisb_unlock.proto |
| qn_panel 七字段映射 | `G0.q`（jadx G0.java:613-636） | `WireWriter.kt:116-134` | ✅ 七字段逐一比对一致 |
| 缓存解锁请求补参（setDownload(0)+fnval 拉满+fourk） | `D0.java:31-52` | `PlayViewHook.patchRequestForCache` | ✅ 同语义，wire bytes 往返 |
| upos CDN 替换（PCDN/纯 IP/gotcha 跳过） | `Hj`+`G0.r` | `unlock/UposReplacer.kt` | ✅ 响应层等价；差异=单 host 手填 vs 21 候选+zone 自动 |
| 失败放行 | 错误卡片/toast | 放行原响应+8s 超时+HookProbe | ✅ 语义一致，用户可见反馈缺失（见 §6 D1） |

## 3. 未复刻（按参考实现功能面盘点）

- **搜索解锁**整块：`SearchMoss.searchAll/searchByType`、`x/v2/search/type` 注入、
  区域页签、`appintl.biliapi.net` 重路由（计划书 §6 非目标）。
- **详情页 view 主体解锁**：BiliRoaming `R0.i/j` 重建 `ViewPgcAny`（清 rights 受限位、
  改 badge）。本项目只做了选集子区块（`SeasonMossHook`），`view.v1.ViewMoss.view` 未挂。
- **season 数据链健壮性**：BiliRoaming `p1.h` 三级取数（API→网页 `__INITIAL_STATE__`
  →`web/season/section` 补 section）；本项目 `SeasonParser` 单链无兜底。
- **多区域服务器体系**：四区四服务器+标题正则选区（`仅.*台`→tw 等）+持久化区域缓存；
  本项目单服务器单区域+进程内 lastArea。
- **播放器级 CDN**：`force_upos`（IjkMediaAsset segment 替换）、`block_pcdn(_live)`、
  P2P 禁用、UPOS 测速 UI——全部未做；`UposReplacer` 只作用于解锁响应。
- **下载解锁另一半**：`fix_download`（dl_fix/qn=0）、多线程缓存 UI、season/view 响应
  `rights.allow_download=1` 权利位改写（`R0.p`/`R0.j`）——**缓存入口可能仍被权利位挡**，
  U6 复验时注意。
- **播放限制解除**：`play_arc_conf`（arcConfId 2/9/23/36：后台/小窗/投屏/听视频）、
  `allow_mini_play`、免费试看清晰度禁用（`vipQualityTrialService`）、大会员横幅/标识移除、
  简中字幕生成、泰区字幕注入与评论弹幕——全部未做（试看问题已由服务端 `strip_need_vip`
  从另一头解决，路线不同目的同）。

## 4. 超出参考实现的自研部分

1. **intl 6.6.0 适配**（BiliRoaming 面向国服，无对应物）：
   顶层 `view_info.dialogMap` 外科清理（`ResponseReconstructor.kt:102-120`）；
   `Stream.stream_info` 元数据必带（缺失=无限缓冲，`WireWriter.kt:189-205`）——真机字节级对照所得。
2. **按区域本地 appsign**（`HostSigner.kt`）：BiliRoaming 不签名（复用宿主已签名 query）；
   本项目因跨区 -3 改为本地签名（th=BstarA，其余=Android）。
3. **DNS 双层劫持**（`BiliIntlDnsHook.kt`）：BiliRoaming 全 dex 无任何 DNS hook（已 grep 证实）。
   本项目为国际版数据通道问题自建（见 §7 真机验证记录——目标未达，基建保留）。

## 5. 文档失真修正（2026-10-04 随本评估落档）

| 位置 | 原文 | 实际 | 处置 |
| --- | --- | --- | --- |
| UNLOCK_PLAN §2 | 签名「借宿主 libBili 签名静态方法」 | U4.7 起按区域本地签名（跨区 -3 实证） | 已改 |
| UNLOCK_PLAN §1 | 「不做服务器端」 | 解锁链依赖自建 Rust 服务端（`BiliRoaming-Rust-Server`，valid_access_key/strip_need_vip 等关键改动） | 已注明 |
| HANDOVER §3.2/§8 | 选集基建「未提交」 | d50d783 已提交；未提交仅 BiliIntlDnsHook | 已过时，以 git 为准 |
| ROADMAP P3 | 「待 Gate 确认」 | G1/G2 已落地，v0.8.0 已发布含解锁 | 已改 |
| HANDOVER §4 | 「App 的整个国际版数据通道是死的」 | **前提需修正**：详情页元数据可达（渲染正常），死的是部分通道；且页面流程**从未解析 `*.biliintl.com`**（双层 DNS hook 全程零命中，2026-10-04 实证） | 见 §7 |

## 6. 决策登记（待用户拍板）

- **D1 解锁失败的用户可见反馈**：BiliRoaming 有 toast/错误卡片；本项目只有探针日志。
  且 2026-10-04 真机发现：漫游失败时回调线程**阻塞等待 8s** 后才放行，曾伴随 App 进程重启
  （疑似 ANR）——反馈设计与等待/降级策略需一并定（缩短等待？异步二段刷新？）。
- **D2 详情页 view 主体解锁是否补做**：依赖 §7 的调查结论——若选集数据实际走 CN 回退
  端点，需要找到真正的请求点；若走 intl 端点但域名不同，扩展 DNS 后缀即可。

## 7. 2026-10-04 真机验证记录

- **播放解锁复验通过**：深链 `bilibili://bangumi/season/33088`（輝夜姬 僅限台灣）→
  `verdict:RESTRICTED (dialog=area_limit)` → 漫游 GET 1.2s →
  `unlock:proxied area=tw quality=80 streams=12 audio=3`（onNext/return 双形态各一次）
  → 正片画面播放，区域横幅消失。
- **播放解锁回归根因**：本机 Redis 未启动 → Rust 服务端请求深处碰缓存**挂死**（非超时），
  模块侧 8s 超时放行。Redis 拉起（`E:\ctf-aaa\bili-rust\redis-portable`）后同请求 3ms 返回
  code=0。教训：服务端排障先查 Redis（交接文档 §7-3 规矩再次应验，且本次表现为「挂」而非「错」）。
- **签名修复生效**：`PlayViewHook.kt` 原硬编码 `sign("hk",…)`——th 配置下 bstar 身份会被
  Android appkey 覆盖（HostSigner 无条件覆盖 appkey/mobi_app/build）。已改为
  `extra["area"]` 跟随服务器区域；tw 路径行为不变（cn/hk/tw 同用 Android 密钥）。
- **方案 A（DNS 透明出口）目标未达**：`InetAddress` 与 `IgHttpDns` 双层 hook 均安装成功且
  观测到真实解析流量（dataflow.biliapi.com、i2.hdslb.com 走 `IgHttpDns.resolve`），但
  **辉夜姬详情页全程未解析任何 `*.biliintl.com`**，`seasonMoss` 六方法 0 调用（与 2026-10-02
  观测一致）。结论：选集数据通道在大陆网络下走的不是 biliintl.com（疑似 CN 回退端点），
  方案 A 前提（§4 的端点模型）需要修正——下一步应抓定详情页实际的选集/season 请求路径再定
  方案（D2）。中继链路本身已验证可用（TLSv1.3 端到端，防火墙 443 规则生效）。
- **宿主 DNS 栈事实**（ classes13.dex 实证）：6.6.0 宿主自带 `com.bilibili.ignetdns.IgHttpDns`
  （HTTPDNS + JNI `native_resolve`），Java 层入口 `resolve/resolveSync(String): Record`，
  `Record(provider, host, clientIp, clientISP, ips[], ttl, originTtl)` 全 public——
  Java InetAddress 层 hook 对宿主数据通道**天然无效**，已加第二层挂钩。

## 8. 2026-10-04 深夜续：D2 调查完成，选集链路定稿

- **D2 答案（详情页 view 主体解锁）**：不需要照搬 BiliRoaming R0.i/j 的 ViewPgcAny 重建——
  intl 的选集数据在 `ViewReply.tab` 里（§7 根因），缺什么补什么即可（ViewTabHook）。
- 数据源调查链：CN season API（受限标题也有 IP 地区门）→ intl ogv season 端点（th 目录
  无台限番）→ **服务端 `/pgc/view/web/season` 路由 + socks5h 台湾出口**（定稿，实测可用）。
- 真机通道归因：面板/播放打通由 PC dns_relay.py（53，昨日已建）+ relay.py（443）网络层
  通道达成，模块 DNS hook 与 ViewTabHook 本轮均为兜底未触发；手机侧断 DNS 通道后复验
  注入路径 = 剩余验证项。
- D1（失败反馈 + 8s 等待）仍未做。

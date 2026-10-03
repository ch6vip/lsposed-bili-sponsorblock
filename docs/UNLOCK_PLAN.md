# 解锁番剧 / CDN 加速 / 缓存番剧 —— 实施规划

> 前置材料：Phase 0 可行性报告 [`docs/UNLOCK_FEASIBILITY.md`](UNLOCK_FEASIBILITY.md)（链路实证与本报告的技术依据）。
> 参考实现：[BiliRoaming](https://github.com/yujincheng08/BiliRoaming)（**GPL-3**）。
> **复刻完成度评估（2026-10-04，对照 7c792dc8fe/1442 反编译逐项核实）：
> [`docs/UNLOCK_ASSESSMENT_2026-10-04.md`](UNLOCK_ASSESSMENT_2026-10-04.md)**。

## 0. 两个 Gate（✅ 2026-10-02 已落地，随 v0.8.0 发布）

> 决策轨迹：2026-09-30 定义 → 延后至发布前 → 2026-10-02 G1/G2 已执行（下方），解锁实现随 v0.8.0 合并入主干发布。

| Gate | 内容 | 产出物 | 时机 |
| --- | --- | --- | --- |
| **G1 定位反转** | 免责声明改写：项目范围扩展为「含受限内容接入」；账号风控风险由用户自担；解锁功能**默认关闭** | README / 免责声明 / module.prop 描述修订稿 | 发版前 |
| **G2 许可证转 GPL-3** | LICENSE 替换为 GPL-3；README 徽章与许可节更新；BiliTamer 移植段（MIT）兼容并存；RELEASING.md 说明许可变更 | LICENSE + 文档修订 | 发版前 |

> 用户决策（2026-09-30）：先实现看效果，G1/G2 推迟到发布前完成。
> **硬约束：包含解锁代码的版本在 G1/G2 完成前不得发布**——不得以 MIT 名义打 tag / 发 Release /
> 同步镜像。开发期提交与本地产物不受此限；此约束登记为发版检查项（U8 / RELEASING.md）。
> 缓解措施：实现基于协议事实与本项目 dex 实证，用自己的代码风格与结构书写，不逐行复制 GPL 参考实现；
> 若最终决定不发布解锁功能，G1/G2 无需发生。

## 1. 范围

| 功能 | 定义 | 不做什么 |
| --- | --- | --- |
| 解锁番剧 | Hook `PlayerMoss.playViewUnite`，受限请求转漫游服务器，重构响应 | 不做服务器端**于本仓库**（实际由配套仓库 `ch6vip/BiliRoaming-Rust-Server` 承担：valid_access_key/strip_need_vip 等）；不做区域自动探测之外的选路优化 |
| CDN 加速 | 解锁响应 `Stream` 的 upos 域名替换（ali/cos/hw/ov/hk 按地理择优） | 不做测速 UI（后置可选） |
| 缓存番剧 | 解锁后放开客户端自带缓存（`setDownload(0)` + fnval 拉满） | 不做自研下载管理器 |

## 2. 总体架构

```
PlayerMoss.playViewUnite(req, handler?)          ← HostTargets 新增「解锁」段（bapis 真名类）
  ├─ before: req 补参（fnval/qn/download，仅缓存与画质相关开关开启时）
  ├─ after/suspend/async 三形态: 受限判定
  │     （req.vod.cid≠resp.playArc.cid / supplement typeUrl 非 PGC / extraContent 有 ep_id）
  │     ├─ 未受限 → 放行
  │     └─ 受限且开关开 → RoamingClient.getPlayUrl(四区择优)
  │           ├─ 签名：**按区域本地 appsign**（U4.7 定稿——借宿主签名跨区得 -3：
  │           │   th=BstarA 密钥，cn/hk/tw=Android 密钥，见 unlock/HostSigner.kt）
  │           └─ 响应（经典 playurl JSON）→ VideoInfo → 重建 PlayViewUniteReply → param.result
  └─ 后处理：upos 域名替换（CDN 加速开关）
探针：unlock: proxy/failed/skip 沿用 HookProbe 限频，失败必须留名
```

新增 `unlock/` 包：`PlayViewHook`（拦截+判定）、`RoamingClient`（协议客户端）、
`ResponseReconstructor`（响应重构，纯 JVM 可单测）、`UposReplacer`。

## 3. 里程碑

| 里程碑 | 内容 | 验收标准 | 估时 |
| --- | --- | --- | --- |
| ~~**U0**~~ | ~~G1/G2 决策落地~~ → **延后为发版 Gate**（见 §0），开发直接从 U1 开始 | — | — |
| **U1** | 拦截点基建（只读）：三形态钩子 + 受限判定 + 探针，**不改任何行为** | 真机播受限/非受限/非番剧三类内容，日志判定全部正确 | 1-2 天 |
| **U2** | protobuf 管线：pgc playview + playurl.v1 的 .proto 定义入库，protobuf-lite 生成接入构建 | 序列化 round-trip 单测绿（宿主实拍字节 ↔ 自备类） | 1-2 天 |
| **U3** | 本地 mock 漫游服务器（`tools/mock-roamer/`，canned playurl JSON）+ `RoamingClient` 四区择优/签名借宿主/超时降级 | 对 mock 的客户端单测绿；**真机零账号暴露** | 2 天 |
| **U4** | 最小闭环：受限 → mock → 响应重构 → 播放 | 对 mock 真机播通一段受限内容（画面/声音/进度条正常，我们的跳过功能在解锁内容上仍工作） | 2-3 天 |
| **U5** | CDN upos 替换 | 替换后真机起播，探针记录替换明细 | 1 天 |
| **U6** | 缓存解锁 | 真机受限内容出现缓存入口并可完成缓存 | 1 天 |
| **U7** | 设置 UI + 管线：`unlock_*` 设置项（总开关/四区地址/accessKey/CDN/缓存）进控制中心，复用三级 fallback | 设置页可用；设置改动按现有生效语义工作 | 1-2 天 |
| **U8** | 真机回归 + 文档收口 + **G1/G2 落地**：用户自配真实服务器全链路；STATUS/README/RELEASING 回写；许可与声明修订合入 | 全部真机验证项通过并记录证据；G1/G2 完成后方可发布 | 1-2 天 |

**合计约 10-13 个工作日**（U1-U3 可并行起步；U8 含发版 Gate，未过 Gate 不发布）。

## 4. 关键设计决策

1. **protobuf 操作方式**：自备 schema 字节往返（BiliRoaming 模式）——`toByteArray → 自备类 parseFrom/copy → param.result`。
   不走反射操作宿主 bapis 类：wire format 一致即兼容，类型安全且可单测。proto 定义在 GPL-3 前提下可基于参考实现修订。
2. **三形态钩子**：宿主为 suspend 形态（`KPlayerMoss$playViewUnite$$inlined` 实证）。U1 先实测确认形态，再按
   BiliRoaming 的 sync/suspend/async-handler 三分支裁剪实现。
3. **失败语义**：漫游失败**放行原响应**（宁可不解锁不黑屏），探针留名 + 节流 Toast——与本项目
   「PROTECTIVE、不崩宿主、静默失效零容忍」的既有约定一致。
4. **与跳过功能共存**：片段拉取按 bvid→SHA-256 前缀，与 cid 无关；但解锁后的 episode cid 变化需在
   U4 真机验证跳过决策与标记仍正确（列入验收）。
5. **默认状态**：解锁总开关默认**关**；开启后设置页明示账号风险。U4 之前真机验证一律走 mock，不配真实服务器。

## 5. 风险登记

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 漫游服务器协议漂移（签名/字段变更） | 解锁失效 | 协议客户端集中在 `RoamingClient` 单文件；探针区分「服务器问题/协议问题」 |
| 宿主更新致 `PlayerMoss` 方法形变 | 拦截失效 | bapis 真名类较稳；HookProbe summary 快速定位；候选表兜底 |
| 账号风控 | 用户账号受限 | 默认关 + 风险明示 + 用户自配服务器（凭据不经过本项目任何基础设施） |
| GPL-3 传染范围 | 全项目许可变更 | G2 一次性完成；后续新增 GPL 依赖须记录 |
| 下载格式变化 | 缓存不可用 | U6 单独验收，失败不影响解锁/CDN |

## 6. 非目标

- 漫游服务器本体、服务器测速与选路优化、泰区字幕/搜索（无需求场景）、概念版/国内版宿主适配。

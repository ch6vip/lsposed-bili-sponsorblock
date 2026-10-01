# 解锁番剧 / CDN 加速 / 缓存番剧 —— Phase 0 可行性报告

> 2026-09-30。基于 [BiliRoaming](https://github.com/yujincheng08/BiliRoaming)（GPL-3）源码研读 +
> 本项目 6.6.0 dex 索引（`E:\ctf-aaa\bili\reverse\decompiled-660\index.tsv`）验证。
> 结论供决策：**继续实施前须先拍板 §4 的两项决策。**

## 1. 结论

**技术上可行，且比预想顺利**：解锁链路的全部关键类在我们宿主（国际版 6.6.0）里是**真名类**，
核心拦截点不需要猜任何混淆名。工程量集中在协议客户端与响应重构，而不是逆向。

但该功能与项目现有定位（"不绕过任何服务端限制"）直接冲突，且存在账号暴露的固有风险——
是否实施是定位级决策，不是技术决策。

## 2. 宿主链路事实（本轮验证，均为 6.6.0 实证）

### 2.1 播放链路已 gRPC 化

- 旧 REST 端点 `pgc/player/api/playurl` 与 `intl/gateway` 在宿主 dex 字符串池中**不存在**——
  播放地址已全面迁到 moss/gRPC + protobuf。
- 索引与字符串池实证的协议栈：
  - gRPC 服务：`bilibili.app.playurl.v1`（PlayURL/Stream/ArcConf/PlayArc/VipRisk 等消息）、
    `bilibili.app.playerunite.v1`（聚合层）
  - 客户端 moss 服务类：**`com.bapis.bilibili.app.playerunite.v1.PlayerMoss`**（真名），
    方法 `playViewUnite`（Kotlin suspend，`KPlayerMoss$playViewUnite$$inlined$suspendCall$1` 在索引中）
  - 请求/响应 protobuf：**`com.bapis.bilibili.app.playerunite.v1.PlayViewUniteReq` / `PlayViewUniteReply`**
    （真名，索引实证）
- `bapis.*` protobuf 族在宿主内**不混淆**（与本项目 IP 属地已用的 `com.bapis.bilibili.metadata` 同族），
  跨版本稳定性远好于混淆短名。

### 2.2 拦截点（BiliRoaming 的现代链路模式，可直接套用）

```
PlayerMoss.playViewUnite(req: PlayViewUniteReq, handler?)   ← before / after 双钩
  before: 补参（fnval 拉满、qn、setDownload(0) 解锁缓存入口）
  after:  判定"受限"（响应 supplement 的 typeUrl 非 PGC 模型 / req.cid ≠ resp.playArc.cid）
          → 序列化 req → 请求漫游服务器 → 解析响应 → reconstructResponseUnite 替换 param.result
  7.41.0+ 还有 async 变体（playViewUnite + mossResponseHandler，包装 handler 回调）
```

我们宿主的 `KPlayerMoss` 是 suspend 形态，实现时按 suspend/async 分支处理（LSPosed 可挂，
注意 continuation 语义——BiliRoaming 对三种形态都提供了分支，可参考其判定逻辑）。

### 2.3 签名：借用宿主

BiliRoaming 不实现签名，而是反射调用宿主内的 appkey/secret 签名静态方法给漫游请求签名
（`libBiliClass.signQuery`）。我们已具备同款基建：`HostTargets` 候选 + `HookProbe`。

### 2.4 漫游服务器是用户自配的

模块不含内置服务器。用户按区域配置四组地址 + accessKey：
`tw_server / hk_server / cn_server / th_server` + `{area}_server_accessKey`。
服务器实现与 B 站同路径（`/pgc/player/api/playurl`、`/intl/gateway/v2/ogv/playurl` 等）。
社区有共享服务器，自建也有开源实现——**服务器不在本项目范围内**，模块只做协议端。

### 2.5 两个子功能的归入

- **CDN 加速** = 在解锁响应的 `Stream` 字段上替换 upos 域名（BiliRoaming 的
  `UposReplaceHelper`：ali/cos/hw/ov/hk 按地理择优），是解锁链路内的一个后处理步骤，无独立拦截点。
- **缓存番剧** = 解锁后把 `setDownload(0)` + fnval 拉满让**客户端自带缓存**可用
  （`fixDownloadProtoUnite`），不是自研下载器。工作量小，但下载触发条件、fnval 组合需要真机调。

## 3. 工作量分解（客户端侧）

| 阶段 | 内容 | 估时 |
| --- | --- | --- |
| P1 | `PlayerMoss.playViewUnite` 钩子 + 受限判定 + PlayViewUniteReq/Reply 序列化与重构（自备 protobuf 生成类，bapis 真名使生成脚本可固化） | 3-5 天 |
| P2 | 漫游服务器协议客户端（四区服务器配置、accessKey、签名借宿主、超时/降级/错误呈现） | 2-3 天 |
| P3 | CDN upos 替换 + 缓存解锁 | 1-2 天 |
| P4 | 设置 UI（服务器/分区/开关）+ 设置管线（复用现有 Provider/镜像三级 fallback） | 1-2 天 |
| P5 | 真机回归（受限内容播放、区域探测、缓存触发；**此步起账号凭据真实经过服务器**） | 1-2 天 |

合计约 **2 周全职当量**，另加长期跟版成本（协议/服务器生态漂移时）。
新增依赖：protobuf-lite 生成类（构建脚本固化）。

## 4. 实施前的两项决策（未决）

1. **定位反转**：免责声明「不绕过任何服务端限制」需改写；项目从「改本机播放行为」扩展为
   「含受限内容接入」。账号风控风险由用户自担的表述同步进文档。
2. **许可证**：BiliRoaming 为 **GPL-3**。本轮已研读其源码，严格意义的 clean-room（未接触 GPL 代码）
   已不成立——实施阶段若构成演绎，项目需整体转 GPL-3（MIT 部分可并入，BiliTamer 移植段与 GPL 兼容）。
   协议事实/端点/类名不受版权保护，但代码层面的参考边界建议实施前明确。

## 5. 与现有架构的对接点

- 拦截点登记：`HostTargets` 新增「解锁」段（`PlayerMoss` / `PlayViewUniteReq` 等真名类，天然稳定）。
- 开关与配置：复用 `SettingsKeys` / 三级 fallback 设置管线；新增服务器地址等字符串项。
- 可观测性：沿用 HookProbe——`unlock: playViewUnite proxied/failed` 探针，失败必须留名
  （本项目对「静默失效」的一贯零容忍）。
- 进度轮询/跳过决策不受影响：解锁只改播放地址来源，不改本地跳过逻辑。

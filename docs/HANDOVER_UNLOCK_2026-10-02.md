# 交接文档：解锁线（2026-10-02）——播放解锁已收口，选集面板待续

> 写于 2026-10-02 17:20。接手者从 §6「怎么继续」看起即可。
> 本文覆盖：今日已交付并推送的成果、选集面板（进行中）的全部证据链与卡点、
> 本机测试环境的起停方式、以及这一轮踩过的坑（§7，务必先读，能省几小时）。

---

## 1. 一句话现状

| 能力 | 状态 |
|---|---|
| 区域受限番剧**播放解锁** | ✅ 真机验证通过，已推送（模块 `9c48731`） |
| **免费画质**（非大会员剥离会员画质只发免费档） | ✅ 服务端已推送（`9c4c205`） |
| **清晰度选级面板**（qn_panel） | ✅ 真机验证通过，已推送（模块 `7132bac`） |
| **选集面板**（剧集列表区块） | ⚠️ 基建全部就位、**卡在最后一环**（App 在大陆网络下整个国际版数据通道是死的，见 §4） |

设备当前**未连接**（USB 又掉了，今天第 N 次）；本机服务端 2662 与台湾出口 7899 在跑。

---

## 2. 今日已交付（已 commit + push，接手前先拉最新）

### 模块仓库 `ch6vip/lsposed-bili-sponsorblock`（master）

| commit | 内容 |
|---|---|
| `9c48731` | **真机全链路修复**：① SettingsCodec 补 `unlock_server_area`/`unlock_test_epid` 键（44→46，此前不进通道的键会被设置同步抹掉→运行时空配置）② 判定三源（BiliRoaming G0.g 对齐：`dialog.type`/`end_page.dialog`/`business.is_preview`）③ **顶层 `view_info` 外科清理**（清 dialogMap+toasts、保留 expSwitch——区域弹窗的真载体，推翻 U4.6「播放器不消费重构实例」收口结论）④ 片段失败重拉 30s 冷却 ⑤ 诊断日志 |
| `7132bac` | **qn_panel 选级面板重建**：三层嵌套 `VodInfo.qn_panel(12) → QnPanel{qn_items(1) → QnItem{stream_info(1)=StreamInfo}}`，七字段映射照 BiliRoaming G0.q |

### 服务端仓库 `ch6vip/BiliRoaming-Rust-Server`（main）

| commit | 内容 |
|---|---|
| `9c4c205` | ① `valid_access_key` 放行 base64url 长令牌（国际版 220 字符含 `-`/`_`，旧纯 alnum 校验把国际版用户全拒为 -101）② **`strip_need_vip_qualities`**：非大会员响应剥离 need_vip 画质只发免费档（原逻辑见任何会员画质轨就整体 -10403，限免番剧连免费档请求都被拒） |

### 真机验收证据（自然受限场景，非强制）

《總之就是非常可愛 第二季》EP1（ep=744345，僅限港澳台）：
```
unlock:verdict:RESTRICTED (dialog=area_limit)
→ Bili2233URL GET http://<pc>:2662/pgc/player/api/playurl?...area=tw
→ unlock:proxied area=tw quality=80 streams=12 audio=3
→ 播放器渲染播放，无弹窗（截图见 STATUS.md 2026-10-02 节）
```

---

## 3. 选集面板：已完成的基建（**未提交**，见 §5）

### 3.1 数据链（jadx 源码级实证，非猜测）

```
ViewMoss.seasonSections(SeasonSectionsReq)      → SeasonSectionsReply   选集分区（第二季/第一季/OVA）
ViewMoss.pageSectionEpisodes(PageSectionEpisodesReq) → PageSectionEpisodesReply  分区内剧集卡片
```
- 服务类：`com.bapis.bilibili.pgc.gateway.view.v1.ViewMoss`（classes9/18 dex）
- 方法共 6 个：`seasonSections`/`executeSeasonSections`/`seasonSectionsForCache`/`executeSeasonSectionsForCache`/
  `pageSectionEpisodes`/`executePageSectionEpisodes`（**ForCache 变体必挂**，首屏很可能走它）

**字段号（全部 static_values 静态实证）**：
```
SeasonSectionsReq:   aid=1 season_id=2 ep_id=4
SeasonSectionsReply: sections=1（repeated SectionData）reserve=2 section_pagination=3
SectionData:  id=1 section_id=2 title=3 episode_ids=6 episodes=7 type=13 ...
ViewEpisode:  ep_id=1 badge=2 badge_type=3 duration=5 status=6 cover=7 aid=8
              title=9 long_title=12 cid=14 ep_index=31 section_index=32 show_title=44 ...
PageSectionEpisodesReq:  section_id=9 page_index=20 cursor_ep_id=21 aid=22 cid=23
PageSectionEpisodesReply: episodes=1（repeated ViewEpisode）pagination=2 section_id=3
```
类型细节：`SectionData.id/section_id/type`=int32、`ViewEpisode.ep_id/aid/cid`=int64、title 系=String。

### 3.2 已落地代码（未提交）

| 文件 | 内容 |
|---|---|
| `unlock/SeasonMossHook.kt`（新） | ViewMoss 6 方法挂钩（形态与 PlayViewHook 同构：双参包回调代理/单参变换返回值）；注入器：reply 的 `sections/episodes` 为空即拉数据重建；`section_id→分区`/`aid→season` 内存缓存供 pageSectionEpisodes 复用 |
| `host/HostTargets.kt` | 新增 `VIEW_MOSS_CLASS`/`SEASON_MOSS_METHODS`/两个 REPLY 类常量 |
| `unlock/WireWriter.kt` | `buildSeasonSectionsReplyBytes` / `buildPageSectionEpisodesReplyBytes` / 剧集条目构建 |
| `unlock/PlayurlParser.kt` | `SeasonParser`（CN season JSON → 中间模型 `SeasonSection`/`SeasonEpisode`） |
| `proto/bilisb_unlock.proto` | `SeasonSectionsReply`/`SeasonSection`/`SeasonViewEpisode`/`PageSectionEpisodesReply` |
| `BiliSponsorBlockHooks.kt` | 注册 `SeasonMossHook.install` |

**数据源**（已实测可用）：`https://api.bilibili.com/pgc/view/web/season?season_id=<id>`
——大陆直连、匿名免签、返回全量季+剧集（含 cid/aid/badge/duration）。**无需经服务端**。

### 3.3 实测到的行为（重要）

- 钩子安装成功且**已生效**（日志 `hook ok: seasonMoss <- seasonSections(2), executeSeasonSections(1), pageSectionEpisodes(2), executePageSectionEpisodes(1), ...ForCache`）
- **但 App 从不调用它们**（探针已挪到配置门之前，任何调用都会留痕；滚动页面也不触发）
- 反编译佐证：`seasonSections` 在全 dex 的引用只有一处（cheese 课程目录类），OGV 详情页的调用点在混淆的 theseus 页面模块里，静态追不到

---

## 4. 卡点根因（已用网络实证钉死）

页面加载期间对手机做 `/proc/net/tcp6` 采样（25 次 × 0.4s），远端连接统计：

```
grpc.biliintl.com (103.151.151.x)   → 0 次
app.biliintl.com  (Akamai 23.195.x) → 0 次
（全部流量集中在 *.13.111 的国内 CDN 边缘）
```

**即：大陆网络下 App 对国际版端点（app/grpc.biliintl.com）根本连不上**（Akamai 被墙、
103.151.x 不可达）。App 的整个国际版数据通道是死的 → 选集数据无从获取 → 页面直接不渲染
选集区块 → 更不会发起 seasonSections 调用。这解释了全部观测，也说明**钩子注入路线
（方案 B）在数据源层面就缺上游**。

---

## 5. 两条候选路线（择一继续）

### 方案 A（推荐）：全局透明出口 —— 让 App 的国际版流量经本机台湾出口

```
模块 DNS hook：把 *.biliintl.com 解析到本机（IP 取自 unlock_server_url 的 host）
本机中继 relay.py：读 TLS ClientHello 的 SNI → 经 sing-box(7899) 连真实主机:443 → 双向搬字节
App 的 TLS 端到端（真证书，证书锁定不受影响）→ 选集/评论/一切原生台湾呈现（=用户开代理时的状态）
```

**已完成**：`E:\ctf-aaa\bili-rust\tw-exit\relay.py`（透明中继，SNI 解析 + socks5 拨号 + 管道）；
防火墙 443 入站规则已加（`Bili2233-Relay-443`）。
**待做**：① 重启 relay（`python relay.py`，443 监听；**注意它当前被我 taskkill 了**）
② 写模块 DNS hook（hook `java.net.InetAddress.getAllByName(String)` / `getByName(String)`，
后缀 `biliintl.com` → 返回本机 IP；探针记录每次改写）
③ 真机验证：不开手机代理 → 开 TONIKAWA 页 → 选集应出现
**风险**：PC 中继挂掉时 App 国际版全断（但现状本来就是断的，无回归）；grpc 若走 QUIC/UDP 443 需另配。

### 方案 B（BiliRoaming 参考路线）：hook 响应层 + 合成数据

BiliRoaming 的解法（`biliroaming/p1` 类，dump 可查）是给**国服**用的：hook 宿主 season REST
的参数类/序列化器，按标题正则（`僅.*台`/`僅.*港`）判区域，从 `{area}_server` 拉数据，
**手工合成 CN 形态 season JSON**（`modules/data/episodes`，剧集条目带 `泰区会员` #FB7299 角标、
`area_limit=false`）喂回宿主序列化器。
对本项目：宿主 6.6.0 国际版没有对应的 REST 路径（字符串池无 season REST 端点），走的是
moss——即 §3 已建的钩子。**该路线的数据源问题同 §4**，需先解决国际端点可达性（→ 绕回方案 A）。

---

## 6. 怎么继续（接手步骤）

```bash
# 1) 设备（USB 不稳定，插上后确认）
"C:\android-sdk\platform-tools\adb.exe" devices            # JJHAV8BENRPNBICM

# 2) 本机服务（两个后台进程，重启电脑后需重拉）
#    a. 台湾出口（sing-box 独立实例，7899 + clash api 19099）
cd E:\ctf-aaa\bili-rust\tw-exit
"C:\Users\ch6vip\AppData\Roaming\com.satelite.proxy\bin\sing-box.exe" run -c config.json
#    验证出口：curl -s -x http://127.0.0.1:7899 https://api.bilibili.com/x/web-interface/zone
#    期望 {"country":"台湾", ... cht.com.tw}
#    b. 漫游服务端（2662）
cd E:\ctf-aaa\bili-rust\BiliRoaming-Rust-Server
./target/release/biliroaming_rust_server.exe
#    c.（方案 A 用）透明中继（443）
cd E:\ctf-aaa\bili-rust\tw-exit && python relay.py

# 3) 设备侧配置（当前已是此状态）
#    镜像文件：/data/data/com.bilibili.app.in/sponsorblock_settings.json  ← 运行时真读这个路径（数据目录根，不是 files/）
#      unlock_enabled=true / unlock_server_url=http://192.168.6.179:2662 / unlock_server_area=tw / unlock_test_epid=0
#    同步源：模块 prefs 与宿主 prefs 的 sponsorblock_settings.xml（见 §7-6）

# 4) 诊断命令
adb logcat -d | grep 'probe.. seasonMoss'      # 选集钩子（注意别被 adbd 回显自匹配，见 §7-5）
adb logcat -d | grep 'unlock:' | tail           # 播放解锁链
adb shell "logcat -d | grep 'unlock:proxied'"   # 成功路径
```

**建议的下一步（方案 A）**：
1. 重启 relay，先在本机自测中继链路：
   `python -c "import socket,ssl; s=ssl.create_default_context().wrap_socket(socket.create_connection(('192.168.6.179',443)),server_hostname='app.biliintl.com'); print(s.version())"`
   —— 应打印 TLSv1.3（此测试此前因防火墙失败，规则已补，未复测）
2. 模块加 DNS hook（半小时量）→ 装机 → 不开代理开页验证选集出现
3. 若 DNS hook 不生效（App 用自己的 DNS 栈）：退而 hook okhttp 的 `Dns` 接口实现类

---

## 7. 本轮踩过的坑（**先读，能省几小时**）

1. **static_values 只给「字段号」，不给「层级」**。qn_panel 首版按 StreamInfo 字段号拍平写进
   QnItem 层，宿主 parseFrom 抛 `invalid tag (zero)`——QnItem 的字段全是消息类型，wire type 不匹配。
   **结构层级必须以 jadx 源码为准**（`jadx --single-class <类> --single-class-output out.java <dex>`）。
2. **别用 python 直连 B 站接口做诊断**：python 的 TLS 指纹会被风控拦，返回**假**的
   `-10403 地区不可观看`。我据此得出过「B 站封路」的错误结论。服务端(rustls)同参数同出口正常。
   要验上游就用服务端打，或看服务端日志。
3. **Redis 陈旧缓存会骗人**：playurl 缓存按 deadline（可 4 小时）、`-10403` 错误缓存 6480s。
   排查时先 `FLUSHDB`（本机 Redis 仅测试用）：
   `python -c "import socket;s=socket.create_connection(('127.0.0.1',6379));s.sendall(b'FLUSHDB\r\n')"`
4. **服务端 `-10403` 可能死循环**：非大会员 + 上游 need_vip 画质 → 拒绝并尝试刷新
   `ep_need_vip`；而 EP_INFO 上游已死（`-2333/-404`）→ 刷新永远失败 → 每次请求都 -10403。
   本轮已用「剥离会员画质」绕过（`9c4c205`），但 EP_INFO 死是独立问题。
5. **logcat 自匹配陷阱**：`adb shell "logcat -d | grep seasonMoss"` 会把**你自己这条命令**的
   adbd 回显也匹配进去，计数虚高。用 `grep 'probe.. seasonMoss'` 或排除 `adbd`。
6. **镜像真路径**：运行时读 `/data/data/com.bilibili.app.in/sponsorblock_settings.json`
   （**数据目录根**），不是 `files/` 子目录（那是旧版本遗留文件，会误导排查）。
   手改配置要三处同步：该镜像 + 宿主 prefs XML + 模块 prefs XML（后两者是同步源，
   `unlock_server_area`/`unlock_test_epid` 已进 Codec 才不会被同步抹掉）。
   改完需 `chown/chmod/restorecon`（宿主 uid `u0_a352`，模块 uid `u0_a369`）。
7. **adb reverse 极不稳定**：USB 重枚举后控制面在、数据面断（请求静默挂起）。
   更稳的做法：改用局域网 IP + Windows 防火墙放行。防火墙规则要管理员：
   `powershell Start-Process netsh -ArgumentList 'advfirewall','firewall','add','rule','name=...','dir=in','action=allow','protocol=TCP','localport=443' -Verb RunAs -Wait`
8. **/proc/net/tcp6 解析**：v4-mapped 地址形如 `0000000000000000FFFF0000<4字节hex>:<端口hex>`，
   最后 4 字节按 hex 直读即为 IPv4（如 `1B280D6F`→`27.40.13.111`）。
9. **服务端 th 通道代理**：`ReqType::ThSeason` 复用 `th_proxy_playurl_*` 配置（types.rs:576），
   不是独立开关。th playurl 从大陆直连返回的 `-404 非东南亚区番剧` 是**业务错**（说明出口通了），
   不是网络错。
10. **BiliRoaming 参考资产**：反编译转储在 `E:\ctf-aaa\bili\BiliRoaming-dec\dump.txt`；
    选集方案在其 `p1` 类（字符串表即它的合成字段清单）；playurl 方案在 `G0` 类。

---

## 8. 未提交改动清单（接手后先决定提交或继续）

**模块仓库**（5 改 + 1 新，全部为选集面板基建；单测通过、装机验证过钩子生效）：
```
M app/src/main/kotlin/com/ctf/bilisb/BiliSponsorBlockHooks.kt   （注册 SeasonMossHook）
M app/src/main/kotlin/com/ctf/bilisb/host/HostTargets.kt        （ViewMoss 常量）
M app/src/main/kotlin/com/ctf/bilisb/unlock/PlayurlParser.kt    （SeasonParser）
M app/src/main/kotlin/com/ctf/bilisb/unlock/WireWriter.kt       （两个 reply 构建器）
M app/src/main/proto/bilisb_unlock.proto                        （选集 schema）
?? app/src/main/kotlin/com/ctf/bilisb/unlock/SeasonMossHook.kt  （新，钩子+注入器）
```
Rust 仓库无未提交源码改动（`config.local.json`、`biliroaming_rust_server_patched` 为本地文件，勿提交）。

---

## 9. 工具速查

```bash
# 反编译索引（6.6.0 全量，270 万行）
INDEX="E:/ctf-aaa/bili/reverse/decompiled-660/index.tsv"
grep -E "^M\t\S+\tL<类>;" "$INDEX"        # 行格式: C/M/F <dex> <类> <名/签名> <返回> <flags>
# 字符串池扫描（找接口路径/常量）
python tools/dexscan/dexstrings.py <classesN.dex> | grep -i season
# 单类反编译（结构层级/字段名以它为准）
jadx --single-class <全限定类名> --single-class-output out.java E:/ctf-aaa/bili/reverse/decompiled-660/dex/classesN.dex
# 字段号静态提取：见本轮会话脚本（解析 class_def static_values，注意 null 条目只占 1 字节）
```

**关键路径**：反编译 `E:\ctf-aaa\bili\reverse\decompiled-660\`（dex/ + index.tsv）；
BiliRoaming 转储 `E:\ctf-aaa\bili\BiliRoaming-dec\`；测试环境 `E:\ctf-aaa\bili-rust\tw-exit\`
（relay.py / mirror.json / 截图与抓包样本）；服务端 `E:\ctf-aaa\bili-rust\BiliRoaming-Rust-Server\`。

# 解锁线测试环境 runbook

> 接棒自已删除的 `HANDOVER_UNLOCK_2026-10-02.md`（交接已被
> [`UNLOCK_ASSESSMENT_2026-10-04.md`](UNLOCK_ASSESSMENT_2026-10-04.md) 取代，其 §4 端点模型
> 已被 2026-10-04 真机实证修正）；本文保留其中**仍然有效**的环境起停与坑清单，并补入
> Redis 挂死教训。真机/模块通用运维见 [`DEVICE_RUNBOOK.md`](DEVICE_RUNBOOK.md)。

## 1. 本机服务起停（重启电脑后都要重拉）

| 服务 | 启动 | 验证 |
| --- | --- | --- |
| **Redis**（**必开**） | `cd E:\ctf-aaa\bili-rust\redis-portable && redis-server.exe --port 6379` | `python -c "import socket;s=socket.create_connection(('127.0.0.1',6379));s.sendall(b'PING\r\n');print(s.recv(64))"` → PONG |
| 漫游服务端（2662） | `cd E:\ctf-aaa\bili-rust\BiliRoaming-Rust-Server && ./target/release/biliroaming_rust_server.exe` | `netstat -ano | grep 2662` LISTENING |
| 台湾出口（sing-box，7899） | `cd E:\ctf-aaa\bili-rust\tw-exit && "C:\Users\ch6vip\AppData\Roaming\com.satelite.proxy\bin\sing-box.exe" run -c config.json` | `curl -s -x http://127.0.0.1:7899 https://api.bilibili.com/x/web-interface/zone` → `country":"台湾"` |
| **DNS 重定向（53）** | `cd E:\ctf-aaa\bili-rust\tw-exit && python dns_relay.py`（把 *.biliintl.com 应答为本机） | `nslookup grpc.biliintl.com 192.168.6.179` → 192.168.6.179 |
| 透明中继（443，方案 A 用） | `cd E:\ctf-aaa\bili-rust\tw-exit && python relay.py` | `python -c "import socket,ssl;s=ssl.create_default_context().wrap_socket(socket.create_connection(('192.168.6.179',443)),server_hostname='app.biliintl.com');print(s.version())"` → TLSv1.3 |

服务端仓库另有未提交本地件（`config.local.json`、`biliroaming_rust_server_patched`），勿提交。

## 2. 设备侧配置

镜像文件：`/data/data/com.bilibili.app.in/sponsorblock_settings.json`（宿主**数据目录根**，
非 `files/` 子目录）。当前：`unlock_enabled=true / unlock_server_url=http://192.168.6.179:2662 /
unlock_server_area=tw / unlock_test_epid=0`。

手改配置要**三处同步**：该镜像 + 宿主 prefs XML + 模块 prefs XML（后两者是同步源；
`unlock_server_area`/`unlock_test_epid` 已进 SettingsCodec 才不会被同步抹掉）。
改完需 `chown/chmod/restorecon`（宿主 uid `u0_a352`，模块 uid `u0_a369`）。

## 3. 诊断命令

```bash
adb logcat -d | grep 'unlock:' | tail           # 播放解锁链（verdict/proxied/roamFailed）
adb logcat -d | grep 'probe.. seasonMoss'       # 选集钩子——别用裸 grep seasonMoss：
                                                # 会匹配你自己的 adbd 回显，计数虚高
adb shell "logcat -d | grep 'unlock:proxied'"   # 成功路径
adb logcat -d | grep 'biliIntlDns'              # DNS 双层劫持（rewrite/ignet* 探针）
```

深链直开受限样本：`adb shell am start -a android.intent.action.VIEW -d
"bilibili://bangumi/season/33088" com.bilibili.app.in`（33088=輝夜姬 僅限台灣，
ep=318304；TONIKAWA S2 = intl ep 744345，CN season API 查不到）。

## 4. 坑清单（每条都真踩过）

0. **服务端挂死先查 Redis**（2026-10-04）：Redis 未启动时服务端请求深处碰缓存**无响应**
   （不是报错），模块侧只看到 8s 超时。排障顺序：Redis → 服务端日志 → 上游。
1. **static_values 只给字段号不给层级**。wire 结构层级必须以 jadx 源码为准
   （`jadx --single-class <类> --single-class-output out.java <dex>`）。
2. **别用 python 直连 B 站接口做诊断**：python TLS 指纹被风控拦，返回**假** `-10403 地区不可观看`。
   服务端（rustls）同参数同出口正常。要验上游就用服务端打或看服务端日志。
3. **Redis 陈旧缓存会骗人**：playurl 按 deadline 缓存（可 4 小时）、`-10403` 缓存 6480s。
   排障先 `FLUSHDB`（本机 Redis 仅测试用）。
4. **服务端 `-10403` 可能死循环**：非大会员 + 上游 need_vip 画质 → 刷新 `ep_need_vip`；
   EP_INFO 上游已死（`-2333/-404`）→ 刷新永远失败。已用「剥离会员画质」（`strip_need_vip`）绕过。
5. **logcat 自匹配陷阱**：见 §3，用 `probe..` 前缀或排除 `adbd`。
6. **镜像真路径**：数据目录根（见 §2）；三处同步缺一处就会被设置同步抹掉。
7. **adb reverse 极不稳定**：USB 重枚举后控制面在、数据面断。改局域网 IP + Windows 防火墙放行
   （规则要管理员；现有规则：`biliroaming_rust_server` 2662、`Bili2233-Relay-443`、DNS 53）。
8. **/proc/net/tcp6 解析**：v4-mapped 地址最后 4 字节 hex 直读即 IPv4（`1B280D6F`→27.40.13.111）。
9. **服务端 th 通道**：`ReqType::ThSeason` 复用 `th_proxy_playurl_*` 配置（types.rs:576）；
   th playurl 从大陆直连返回 `-404 非东南亚区番剧` 是业务错（说明出口通了）。
10. **主路径=网络层 DNS 通道，其余全是兜底**：手机 DNS 链路把 *.biliintl.com 解析到 PC 的`dns_relay.py`（53，\tw-exit）→ relay.py（443，SNI）→ 台湾出口；国际版服务器按出口 IP 服务全量数据（2026-10-04 真机实证：面板/播放全通，模块 hook 零参与）。通道失效时才轮到 ViewTabHook 注入（服务端 /pgc/view/web/season 取数）与 playview 漫游。
11. **宿主 DNS 栈**：6.6.0 走 `com.bilibili.ignetdns.IgHttpDns`（HTTPDNS+JNI），
    `java.net.InetAddress` hook 对宿主数据通道无效——DNS 类劫持必须两层
    （见 `unlock/BiliIntlDnsHook.kt`）。

# 交接文档：B站 6.6.0 适配（模块 0.6.3 → 0.7.0）

> 写于 2026-09-29。上一会话已完成「反编译 6.6.0 + 全量 hook 点静态核对」，适配代码**尚未开始**。
> 接手者按 §5 执行即可，无需重做 §4 的核对。

## 1. 目标

模块升级到 **0.7.0（versionCode 10）**，在宿主 **B站 6.6.0（com.bilibili.app.in，versionCode 9130300）** 上完整恢复功能，**不破坏 6.5.0**。

验收标准（全部满足才算完成）：
1. `:app:testDebugUnitTest` 全绿（现状 133 例）；`:app:assembleRelease` 产出签名包
2. 真机 HookProbe summary ≥ 6.5.0 基线（2026-09-21 记录为 33/38 hit，见 docs/STATUS.md），**主链路（SponsorBlock）0 MISS**
3. 真机功能回归：自动跳过（倒计时/可取消）、手动跳过、片段静音、进度条 9 分类彩色标记、剩余时长扣减、跳过 Toast、统计；「⋯」面板 +「我的」页两个入口；增强四件套（IP 属地/隐藏互动提示/首页不自动刷新/分享 QQ）
4. 文档同步：新建 `docs/APK_6.6.0_ANALYSIS.md`（格式对照 APK_6.5.0_ANALYSIS.md），更新 STATUS.md / README.md / module 版本号

## 2. 资产与环境

| 资产 | 路径 / 说明 |
| --- | --- |
| 模块仓库 | `E:\ctf-aaa\bili\lsposed-bili-sponsorblock`（master == origin/master @ d75ab87，未 push） |
| 6.6.0 反编译 | `E:\ctf-aaa\bili\decompiled-660\`：dex/ 34 个、index.tsv（2,700,976 行 / 329MB）、解码版+二进制 AndroidManifest、README |
| 6.6.0 原始包 | `E:\ctf-aaa\bili\bilibili_6.6.0\`（adb 真机拉取：base.apk 336MB + arm64 + xxhdpi） |
| 6.5.0 参照 | `E:\ctf-aaa\bili\decompiled-650\`（index.tsv 2,623,866 行；dexdump/d12,d14,d17,d21,d26.txt）、`bilibili_6.5.0.apks` |
| 查询工具 | 仓库 `tools/dexscan/`：`q.py`/`analyze.py`/`dexindex.py`/`dexstrings.py`/`axml.py`。用法：`$env:DEX_INDEX="E:\ctf-aaa\bili\decompiled-660\index.tsv"` 后 `python tools\dexscan\q.py "正则"`（DEX 描述符大小写敏感） |
| dexdump | `C:\android-sdk\build-tools\36.0.0\dexdump.exe -d <dex> > out.txt`（单 dex 约 200MB 文本） |
| adb | `C:\android-sdk\platform-tools\adb.exe`；设备 Xiaomi 23078RKD5C（corot，serial JJHAV8BENRPNBICM），Android 16，KernelSU + LSPosed |
| 构建 | 仓库根：`$env:ANDROID_HOME="C:\android-sdk"`（PowerShell）→ `.\gradlew.bat :app:testDebugUnitTest --no-daemon` / `:app:assembleRelease --no-daemon`。JDK 17。Robolectric 首跑需联网下 android-all jar。release 签名齐全（keystore.properties → E:\ctf-aaa\bili\keystore\bilisb-release.jks），可直接覆盖安装 |

## 3. 仓库现状（重要）

- **工作区有 18 个已改文件 + 若干未跟踪文件，主题 = 第二轮全量审查修复**（与 `.agents/notes/implemented/bug-fix/2026-03-22-full-audit-round2.md` 一一对应）：CleartextPolicyHooks（新）、NSC 资源（res/xml/，新）、manifest usesCleartextTraffic、多处 bug 修复（AudioMuteController/VideoDirectorListener/SettingsCodec/UserIdentityStore 等）、docs+README 刷新。实际增量约 +304/−198（`git diff --stat` 的 +1293/−1187 是 CRLF 行尾噪声，用 `--ignore-cr-at-eol` 看）。
- **用户已拍板：先把这批单独 commit 为基线（本地 master，不 push），再开始适配**。适配改动之后单独 commit。
- 版本号两处需同步改 0.7.0/10：`app/build.gradle.kts:43-44`（MODULE_VERSION_CODE/NAME）+ `app/src/main/resources/META-INF/xposed/module.prop`；`checkModuleProp` 任务会强制一致，不一致构建失败。
- Xposed 新式声明：`META-INF/xposed/`（scope.list 只含 com.bilibili.app.in，staticScope，无需改）。

## 4. 静态核对结论（已对 decompiled-660/index.tsv 逐类验证，2026-09-29）

Hook 点架构背景：libxposed 新 API，全走 `BiliSponsorBlockHooks.hookAfter()`；类/方法解析按「候选名列表 + 签名形状匹配」（`host/HookResolve.kt`），命中/失效由 `host/HookProbe.kt` 记 summary。类名集中在 `host/HostTargets.kt`；四个文件有内联字面量：`hook/IpLocationHooks.kt`（最多）、`hook/InteractHintHooks.kt`、`hook/HomeNoAutoRefreshHooks.kt`、`hook/ShareQqHooks.kt`。

### 4.1 存活（候选机制自动覆盖；注意 dex 大迁移：播放器 classes17→18/28、面板 21→11）

| 类（6.5.0 写法） | 6.6.0 状态 |
| --- | --- |
| PlayDirectorServiceV3 / VideosPlayDirectorService / service.E0 / service.D / service.Video$e / Video$a | 全在 classes28；`Video$e#z()→Video$a` 仍在（aid/cid 提取链路无恙） |
| PlayerSeekWidget3 / PlayerProgressTextWidget / seek.v3.{g,f,q} | classes18；`draw(Canvas)` 在 |
| GeminiProgressTextWidget / gemini.ui.f / gemini.ui.i / i$b | classes11 |
| bindPlayerContainer | 在 classes18，**但参数类型 f→h**（见失效点 2） |
| mine.d / IntentHandlerActivity / HomeAppBarLayout | classes28 |
| MenuGroup | classes16 |
| VideoTripleLike.setPrompt(Z) | classes11 |
| FollowPopupUtil / InteractDanmakuListWidget.setData(List) | classes18 |
| PegasusViewModel / PegasusFlush | classes17 |
| ShareChannels.getAboveChannels() | classes7；Tencent classes23 |
| kntr.base.moss.ignet.impl.header.b / grpc.c#f(String,[B) | classes25（真名稳定，主改写路径完好） |
| kr1.a / up1.a | classes25 |
| mq0.a / oq0.a / Aq0.a / Cq0.a | classes16 |
| XA0.a | classes17 |
| comm.list.common.api.e / LocalAuthorSpaceActivity / AuthorSpaceActivity | classes11 / 6 / 5 |
| com.bapis.bilibili.metadata.* | 176 个类 |
| be1.j（8.96 旧兜底） | 不存在，预期内，SKIP 即可 |

### 4.2 确认失效 —— 6 个实际改动点

1. **观察者注册迁移（主链路最关键）**：6.5.0 在 PlayDirectorServiceV3 上 hook「1 参 `E0` 的方法」（名候选 j0/addVideoDirectorObserver）。6.6.0 该类已无任何 E0 签名方法；E0 的注册/注销入口迁到 `tv.danmaku.biliplayerv2.service.Z`（接口，abstract `J6(E0)`/`i7(E0)`）与 **`D0`**（final 实现，有 code），都在 classes28。改法：DIRECTOR_SERVICE/观察者注册候选加 `D0` + 方法名 `J6`/`i7`（保留旧候选兼容 6.5.0）。
2. **容器类型 f→h**：`bindPlayerContainer(Ltv/danmaku/biliplayerv2/h;)V`。形状校验若只认 `biliplayerv2.f` 会 MISS → 影响进入播放页的 bind 入口。改 HostTargets 容器类型候选加 `h`。
3. **更多面板注入点改名**：`gemini.ui.f#f0(List)` → **`e0(List)`**（MorePanelInjector 候选名加 e0；行接口 gemini.ui.i / i$b 仍在 classes11）。
4. **ip1.h 消失**：6.5.0 的 KMP moss 评论发送入口（4 参 `a`，ThreadLocal 标记评论 RPC scope）。需按特征重定位（从 classes25 的 kntr/moss 相邻包找 4 参发送方法；或评估仅靠 grpc.c#f 路径是否已覆盖评论场景）。
5. **类型提示描述符失效**：`kr1.g`/`Zq1.g`/`jp1.g` 及 `kr1.k`/`Zq1.k`/`jp1.k` 在 6.6.0 索引全部为 0（IpLocationHooks.kt:497,508 的头包装类型提示）。kr1.a/up1.a 兜底路径的 isHeaderProviderBase 形状校验（IpLocationHooks.kt:386-404）仍在，需在 classes25 重新找头包装类型名补提示。
6. **Router/BLRouter 消失**：MineMenuInjector 的 ROUTER_CLASSES 只剩 `tv.danmaku.bili.ui.intent.IntentHandlerActivity`（classes28，在）。需真机确认 `bilisb://settings` 仍能打开；顺带可查 blrouter 包新名（`grep "^C\t\S+\tLcom/bilibili/lib/blrouter" index.tsv`）。

**执行期待抽查**（本轮未核）：进度回调候选名（int 版 G/onPlayerProgressChange/updateTime/i0、long 版、setText）——形状匹配，探针即可验证；MenuGroup 字段名；protobuf setter 候选（IpLocationHooks.kt:689-697）。

## 5. 执行计划

- **T0 基线提交**：`git add -A` 前先 `git status` 核对清单，commit message 如 `fix: 第二轮全量审查修复（明文策略/TTL/unmute/去重等）+ 文档同步`。不 push。
- **T1 静态重定位**（工具见 §2，产出 `docs/APK_6.6.0_ANALYSIS.md`）：按 §4.2 的 1→6 逐项用 q.py + dexdump（classes28/classes25 为主）定语义，确认新候选；6.5.0 版分析文档是格式模板。
- **T2 代码更新**：只动 `host/HostTargets.kt` 候选表 + 内联常量文件（IpLocationHooks、MorePanelInjector 候选名），**全部旧候选保留**（双版本兼容，不引入 versionCode 分支，除非某 hook 语义冲突）。版本号改 0.7.0/10（§3）。
- **T3 构建**：单测全绿 + assembleRelease。
- **T4 真机验证**：按 `docs/DEVICE_PROBE.md` runbook —— adb install -r → force-stop → 开 B 站进播放页 → 抓模块日志看 HookProbe summary（不是裸 logcat）→ 按 11 条关键字逐项回归。MISS 处理三步：q.py 重查 → 改 HostTargets 候选 → 重装重跑。
- **T5 收尾**：STATUS.md 加 6.6.0 验证证据表、README 支持版本改 6.5.0/6.6.0、.agents 笔记记录失效点方法论；适配单独 commit（本地）。

「明确不做」（与 6.5.0 同口径）：小窗/切集/番剧/深色/切账号的穷举回归；提交链路 POST /api/skipSegments 的真机验证（代码保留）；顶栏消息入口/底栏删 tab 已裁撤**不要复活**（.agents/notes/implemented/simplification/2026-09-19-remove-hometab.md）。

## 6. 方法论与坑（来自 6.5.0 轮，仍然有效）

- 定位顺序：**字符串文案 → q.py 全索引命中 → dexdump 看字节码定参数语义 → 真机探针确认**（范例：`.agents/notes/implemented/reverse/2026-09-14-more-panel-650.md`）。
- 个别 dex 的 dexdump 输出**不含完整 class_data**（6.5.0 的 classes21/14 踩过）：grep 查不到≠不存在，要用字符串池/原始字节二次确认。
- 匹配三重保险：类名 + 方法签名 + 字段类型，防同名误配。
- index.tsv 行格式：`C <dex> <类描述符> <父类> <接口> <flags>` / `M <dex> <类> <名(参数)> <返回> <flags>` / `F <dex> <类> <字段> <类型> <flags>`；flags 带 `|code` 表示有方法体。嵌套类 `$` 用 `grep -F` 固定串查，别用 `-P` 转义。
- HookProbe summary 在模块 install 完成后打一次（BiliSponsorBlockHooks.kt:97 附近）；每条 hook 必须过探针（ROADMAP.md 开发约定：宿主类名只改 HostTargets.kt）。

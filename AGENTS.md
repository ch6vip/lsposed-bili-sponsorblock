# AGENTS.md - Bili2233 开发与协作规范

> 本文件是专为 AI 编码助手（Agent）制定的项目上下文与开发指南。在修改代码、逆向适配、编写测试或执行真机验证前，请务必阅读并严格遵守本指南。

---

## 1. 项目概况与架构定位

### 1.1 基本信息
- **项目名称**：Bili2233（LSPosed 模块）
- **应用包名**：`io.github.ch6vip.bilisb`
- **代码命名空间**：`com.ctf.bilisb`
- **开源协议**：GPL-3.0
- **目标宿主**：哔哩哔哩国际版 `com.bilibili.app.in`，当前适配基准版本为 **6.5.0（9110200）** 与 **6.6.0（9130300）**。
- **运行环境**：Android 6.0+（`minSdk 23`，`targetSdk 35`），arm64-v8a 真机，KernelSU / Magisk Root，支持 **libxposed API 101** 的 LSPosed 框架。

### 1.2 四大核心功能子系统
1. **SponsorBlock 跳过与标记体系** (`com.ctf.bilisb.sponsor`, `player`, `ui`, `net`)
   - 依赖 SponsorBlock 社区数据源（默认 `https://bsbsb.top`）。
   - 自动跳过、手动跳过、倒计时跳过、静音播放、进度条彩色片段标记、剩余时长扣减。
   - 播放器「空降助手」面板交互、片段提交与本地跳过统计。
2. **B 站增强** (`com.ctf.bilisb.hook.EnhanceHook`, `settings.EnhanceFlags`)
   - 评论区与主页 IP 属地展示（改写请求身份字段 `mobi_app`）。
   - 隐藏互动提示（一键三连、UP 主关注气泡、投票组件）。
   - 首页防自动刷新、分享面板补回「分享到 QQ」。
3. **番剧漫游解锁** (`com.ctf.bilisb.unlock`)
   - BiliRoaming 协议深度重构适配国际版，需配合自建/公共漫游解析服务器。
   - gRPC / Moss PlayView 播放地址拦截与多清晰度策略替换。
   - 选集面板详情页（Season / Episode）wire 级定点无损切片拼接。
   - UPOS CDN 域名测速与智能替换（过滤 PCDN / 纯 IP / gotcha 节点）。
   - 离线下载权利位修补与下载请求拦截、TH（泰区）字幕提取。
4. **首页与搜索扩展** (`unlock.HomeTabHook`, `unlock.SearchUnlockHook`)
   - 首页追番页签注入：拦截 6.6.0 新架构 `HomeFrameViewModel.v0` 与 `HomeFrameDataRepo.e`，无缝注入「追番（大陆）」与「追番（港澳台）」页签。
   - 区域搜索增强：解锁影视搜索，番剧卡片自动转换与多区域页签隔离。

---

## 2. 核心铁律与安全原则（Critical Invariants）

在进行任何代码修改时，Agent 必须严格遵守以下红线：

1. **绝对容错，绝不使宿主崩溃 (Never Crash Host)**
   - 宿主运行在数以千万计的混淆和复杂业务逻辑中。所有的 Hook 回调、反射调用、网络数据解析与协议重构必须被 `runCatching` 或专门的 try-catch 包裹。
   - 发生异常时，只允许打印日志（`ModuleLog`），绝对不能向外抛出未捕获异常导致 Bilibili 客户端闪退。
2. **优雅降级，放行原片 (Graceful Degradation)**
   - 解锁失败、漫游服务器超时或数据解析不匹配时，必须**放行原生响应**。
   - 宁可降级为原片播放失败提示或原生界面，坚决不允许引发播放器永久黑屏、无限菊花加载或界面死锁。
3. **严格的多进程感知与隔离 (Multi-Process Awareness)**
   - B 站包含主进程（`com.bilibili.app.in`）、下载进程（`:download`）、解码渲染进程（`:ijkservice`）等。
   - 必须在入口 [`BiliHookInit.kt`](file:///app/src/main/kotlin/com/ctf/bilisb/BiliHookInit.kt) 中依据进程名精准路由 Hook。
   - 主进程挂载 UI、SponsorBlock、搜索和首页页签；下载进程仅挂载下载权限与取地址 Hook；其他子进程立即返回（Skip），严禁跨进程滥挂 Hook。
4. **设置数据源唯一性 (Settings Authority)**
   - 模块 SharedPreferences 及 ContentProvider IPC 是配置的唯一权威源（Source of Truth）。
   - 宿主私有目录下的 JSON 文件仅为镜像缓存，任何配置读写必须走 [`SettingsCodec`](file:///app/src/main/kotlin/com/ctf/bilisb/settings/SettingsCodec.kt) 与 [`SettingsWriter`](file:///app/src/main/kotlin/com/ctf/bilisb/settings/SettingsWriter.kt)。
5. **机密与隐私防护 (Credential Hygiene)**
   - 严禁将 `keystore.properties`、`local.properties`、`*.jks`、签名证书口令或个人漫游 Token 提交入库。
   - 代码中严禁硬编码私人服务器 IP 或敏感凭证。

---

## 3. 逆向工程与 Hook 开发规范

### 3.1 集中管理混淆目标
- 严禁在业务 Hook 逻辑内部硬编码单个混淆类名或方法名。
- 宿主混淆类名、方法名候选列表必须统一收拢在 [`HostTargets.kt`](file:///app/src/main/kotlin/com/ctf/bilisb/host/HostTargets.kt) 或各 Hook 类的声明顶部。
- 必须同时保证 6.5.0 与 6.6.0 的双版本兼容。如新增混淆类名，需通过 `tools/dexscan` 验证两者签名与字段形态。

### 3.2 弹性模型实例化与字段注入
- 宿主 6.6.0 大量采用 Kotlin 内部数据类，其无参构造往往私有甚至被 R8 剥除。
- 构造虚构对象（如 `HomeTabItemData`）时，优先通过 `sun.misc.Unsafe.allocateInstance(targetClass)` 分配，其次回退有参构造反射。
- 字段赋值必须采用「混淆字段 + 语义字段」双重覆盖机制，并遍历所有父类（`Class.superclass`），以兼容不同的 R8 优化形态。

### 3.3 Wire 级定点拼接（Protobuf / gRPC）
- 在处理 Moss / gRPC 协议（如 Season 选集列表、PlayView 响应）时，宿主使用了庞大且随版本演进的 Protobuf 消息。
- 严禁对完整宿主响应进行全量反序列化再序列化（会导致未知 tag 丢失或序列化崩溃）。
- 必须使用 [`WireSplice.kt`](file:///app/src/main/kotlin/com/ctf/bilisb/unlock/WireSplice.kt) 与 [`WireWriter.kt`](file:///app/src/main/kotlin/com/ctf/bilisb/unlock/WireWriter.kt) 在二进制 wire-level 进行定点切片、字段注入与 tag 拼装。

### 3.4 逆向辅助工具链
- `tools/dexscan/dexindex.py`：索引宿主 DEX 中的类、方法、字段，快速定位改版重命名的类。
- `tools/dexscan/axml.py`：解析宿主 APK AndroidManifest.xml。
- `tools/mock-roamer/mock_roamer.py`：本地模拟 BiliRoaming 漫游服务端，用于在无网络或离线状态下验证解锁全链路。

---

## 4. 构建、测试与真机验证流程

### 4.1 常用构建与测试命令
```powershell
# 1. 运行所有 Release 单元测试（任何核心修改必须先跑通单测！）
.\gradlew.bat :app:testReleaseUnitTest

# 2. 编译 Release APK
.\gradlew.bat :app:assembleRelease

# 3. 编译 Debug APK
.\gradlew.bat :app:assembleDebug
```

### 4.2 真机部署与热生效（Device Runbook）
宿主只支持 arm64-v8a 真机（不可使用 x86_64 模拟器）。当设备通过 ADB 连接时：

```powershell
# 1. 覆盖安装 Release APK
adb install -r app\build\outputs\apk\release\app-release.apk

# 2. 杀死宿主旧进程（必须强杀，否则进程仍常驻旧 DEX）
adb shell su -c "pkill -9 -f com.bilibili.app.in"

# 3. 启动宿主客户端
adb shell am start -n com.bilibili.app.in/tv.danmaku.bili.MainActivityV2
```

### 4.3 日志查看与探针诊断
由于 LSPosed 封装机制，模块输出的日志在 logcat 中统一标记为 **`LSPosedFramework`** 标签，而不是普通的应用 Tag。

```powershell
# 抓取包含探针探活与模块行为的日志
adb logcat -d | Select-String -Pattern "probe|Bili2233|unlock|homeTab"

# 查看最新 LSPosed 模块文件日志（需 root）
adb shell su -c "tail -n 100 /data/adb/lspd/log/modules_*.log"
```

**健康基线指标**：
冷启动后检查 `[probe] hook summary: N/M hit`。核心链路（PlayView、AccessKey、HomeTab、Settings）命中数必须为 100%，不允许出现致命的 `MISS`。

---

## 5. 代码质量与 Git 提交规范

1. **测试先行**：修改 `unlock/`、`sponsor/` 或 `model/` 逻辑时，必须在 `app/src/test/kotlin/com/ctf/bilisb/` 补充或更新对应的单元测试，严禁提交破损测试。
2. **Conventional Commits 提交格式**：
   - `feat(scope): 描述新功能`（如 `feat(unlock): 增加泰区字幕自动繁化支持`）
   - `fix(scope): 描述修复内容`（如 `fix(unlock): 适配 6.6.0 首页番剧页签注入`）
   - `refactor(scope): 代码重构`
   - `test(scope): 测试用例更新`
   - `docs(scope): 文档变动`
3. **分支与远程同步**：提交前核对 `git status` 与 `git diff`，确认未残留临时调试文件后再推送到 `origin/master`。

# 真机验证 runbook（6.5.0 探针版）

> 目的：一次真机运行就把 `docs/APK_6.5.0_ANALYSIS.md` 里剩下的「待真机确认」项全部收掉。
> 当前构建是**探针版**：所有候选 Hook 都会尝试挂载，并把命中/缺失和运行时实参打进 `Bili2233` 日志。

## 0. 前置条件

| 项 | 状态（本机实测） |
| --- | --- |
| 设备 | Xiaomi （机型略），arm64-v8a，Android 16（SDK 36） |
| 目标宿主 | `com.bilibili.app.in` 已安装，versionName 6.5.0 / versionCode 9110200 ✅ |
| Root | KernelSU 已安装 ✅（`adb shell` 直连 `su` 不可用，需要在 KernelSU 管理器里给终端/管理器授权） |
| LSPosed | ❌ **未安装**（`org.lsposed.manager` 不存在）——这是唯一阻塞点 |
| 宿主库 | `.apks` 只带 `arm64_v8a`，SDK 里的 x86_64 模拟器镜像跑不了，必须真机 |

装 LSPosed 时注意：Android 16 需要较新的 LSPosed 构建；KernelSU 环境要么开内置 Zygisk 再用 Zygisk 版 LSPosed，
要么装 KernelSU 兼容发行版。装完在 LSPosed 里启用 `Bili2233` 并勾选作用域 **`com.bilibili.app.in`**。

## 1. 构建与安装

```powershell
$env:ANDROID_HOME = "C:\android-sdk"
.\gradlew.bat :app:assembleDebug

$adb = "C:\android-sdk\platform-tools\adb.exe"
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
```

在 LSPosed 中启用模块 → 勾选作用域 `com.bilibili.app.in` → **强制停止**宿主进程
（`adb shell am force-stop com.bilibili.app.in`）→ 重新启动宿主。

## 2. 抓日志

```powershell
$adb = "C:\android-sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am start -n com.bilibili.app.in/tv.danmaku.bili.MainActivityV2

# 另一个窗口抓模块日志；播一个视频（含一个 SponsorBlock 片段）后 Ctrl+C
& $adb logcat -v time | Select-String "Bili2233|probe"
```

建议采集三段：**冷启动进首页** → **进「我的」页** → **进入播放页播放 30 秒**。

## 3. 证据清单（日志关键字 → 待确认问题）

| # | 日志关键字 | 能确认什么 | 期望 |
| --- | --- | --- | --- |
| 1 | `Bili2233 module loaded in com.bilibili.app.in` | M1 作用域/进程过滤 | 只在主进程出现，子进程出现 `Skip hooks in non-main process` |
| 2 | `[probe] hook summary: N/M hit` | 哪条 hook 装上、哪条没找到 | 关注 `directorService` / `containerBinding` / `progressInt` / `seekTrack` / `mineAdapter` |
| 3 | `[probe] containerBinding:*`、`player bound context=...` | M3 新入口 `bindPlayerContainer(f)` 是否触发、Context 是否拿到 | 进播放页后出现，`context=` 非 0 |
| 4 | `[probe] progressCallbackArgs` | M4 真正回调的是哪个方法 | 顺序/单位已静态确认是 `G(position, duration)`（ms）；真机只需确认它确实被调用、以及 `progressLong:*` 是否也回调（那两个是 probe-only，不参与决策） |
| 5 | `[probe] directorService`、`[probe] directorCurrentVideo` | M5 director 服务能否拿到、`D() -> Video$e -> Video$a` 是否可用 | `directorCurrentVideo: video=... ids=(12345, 67890)` |
| 6 | `[probe] directorCallback`、`videoDirector: FOUND aid=... cid=...` | M5 观察者回调是否携带 aid/cid | 有 `b(...)`/`c(...)`/`e(...)` 回调且能提取出 id |
| 7 | `[probe] extractVideoIdsFailed` | M5 兜底也失败时的证据 | 出现即说明需要换提取路径（附 `video=` 类名） |
| 8 | `segments fetched video=... status=200 count=N` | 整条拉取链路（BV→前缀→服务端） | count>0 |
| 9 | `[probe] seekTrackDrawn: <类名> bounds=[...]` | M6 标记实际画在哪个类、bounds 是否薄轨道 | `v3.g` + 高度≈轨道高；若 bounds 高度很大说明画到了 SeekBar 本体 |
| 10 | `[probe] mineAdapterFound`、`Injected Bili2233 setting item at position N` | M7 我的页入口 | 出现在「我的」页刷新时 |
| 11 | `[probe] uriRouter` | M8 路由拦截是否可用 | 大概率 MISS（`Router`/`BLRouter` 类名在 6.5.0 不存在） |

## 4. 回写方式

把上面每条的实测结论写进 `docs/STATUS.md` 的「6.5.0 真机验证」小节，并把 ROADMAP 对应 M 项勾掉/更新。
最重要的是第 4/5/9 条：它们决定 `HostTargets` 里哪几个候选该被提升为首选。

## 5. 如果某个 Hook 没命中

1. 用 `tools/dexscan` 重新确认类/方法还存在（可能是宿主小版本又混淆了）；
2. 把新名字加进 `app/src/main/kotlin/com/ctf/bilisb/host/HostTargets.kt` 的对应候选列表（不要散落到各 Hook 文件里）；
3. 重新构建 → 再跑一次本 runbook。

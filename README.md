# Bili2233 · LSPosed SponsorBlock 模块

在 B 站 Android 客户端里实现 SponsorBlock 片段跳过与标记的独立 LSPosed 模块。

| 项 | 值 |
| --- | --- |
| 模块显示名 | Bili2233 |
| 包名 | com.ctf.bilisb |
| 当前版本 | 0.5.0（**尚未迁移到新目标**） |
| **目标宿主** | **`com.bilibili.app.in`（bilibili 国际版）6.5.0 / versionCode 9110200** |
| **目标安装包** | `<APK目录>\bilibili_6.5.0.apks`（base + arm64_v8a + xxhdpi） |
| 宿主环境 | minSdk 24 / targetSdk 36 / compileSdk 36，33 个 dex，仅 arm64 |
| Xposed API | libxposed API 101 |
| 构建 | AGP 8.7.3 / Kotlin 2.0.21 / Gradle 8.12 / compileSdk 35 / minSdk 23 / JDK 17 |

> ✅ **迁移状态**：已在 **`com.bilibili.app.in` 6.5.0 真机验证通过主链路**——
> 模块加载、aid/cid 获取、拉取片段（`200 count=9`）、自动跳过（intro/selfpromo/sponsor/interaction）、
> 进度条彩色标记（竖屏 + 全屏 padding 已对齐）、Toast、提交按钮、「我的」页设置入口、设置读写。
> 并完成一轮全项目 code review（4 路并行）与 34 项问题修复，单测 97 例全绿。
>
> 目标类名对照与证据见 [`docs/APK_6.5.0_ANALYSIS.md`](docs/APK_6.5.0_ANALYSIS.md)（含 6 个真机踩坑记录），
> 当前状态与待验证项见 [`docs/STATUS.md`](docs/STATUS.md)，真机复现步骤见 [`docs/DEVICE_PROBE.md`](docs/DEVICE_PROBE.md)。
>
> 历史口径（仅作行为蓝本保留）：`Bili-v8.98.0-x1.27.3@bb_show.apk`（原参考 patch 版）与
> `tv.danmaku.bili` stock 8.96.0（原适配线）。

## 功能

- 自动跳过 / 手动跳过 / 片段静音 / 倒计时取消 / 最小片段时长过滤
- 进度条分类彩色标记（颜色可自定义）、剩余时长扣减、跳过 Toast
- 片段提交入口、用户 ID 管理、默认标记类别
- 跳过统计（累计 / 分类 / 重置）
- 设置 UI：模块入口 + 宿主「我的」页弹窗，共用同一套 UI
- 跨进程设置同步（ContentProvider IPC + JSON 镜像 + 默认值三级 fallback）

## 构建

环境：JDK 17、Android SDK（compileSdk 35）、Gradle 8.12。

```powershell
# 需要 Android SDK：设置 ANDROID_HOME 或写 local.properties 的 sdk.dir
$env:ANDROID_HOME = "C:\android-sdk"

# 构建 debug APK
.\gradlew.bat :app:assembleDebug

# 跑单元测试
.\gradlew.bat :app:testDebugUnitTest
```

产物：`app\build\outputs\apk\debug\app-debug.apk`

## 安装

1. 安装 debug APK。
2. 在 LSPosed 中启用模块，作用域勾选 **`com.bilibili.app.in`**。
3. 重启 B 站；日志 TAG 为 `Bili2233`。

## 目标 APK 静态分析

改 Hook 之前先用 `tools/dexscan` 核对类名是否还存在（纯 Python，无需 jadx/apktool）：

```powershell
python tools\dexscan\extract_apks.py <APK目录>\bilibili_6.5.0.apks <工作目录>
python tools\dexscan\dexindex.py <工作目录>\dex <工作目录>\index.tsv
$env:DEX_INDEX = "<工作目录>\index.tsv"
python tools\dexscan\q.py '^M\t\S+\tLcom/bilibili/playerbizcommonv2/widget/base/PlayerProgressTextWidget;'
```

详见 [`tools/dexscan/README.md`](tools/dexscan/README.md)。

## 文档索引

- `docs/APK_6.5.0_ANALYSIS.md` — 目标 APK 事实、类名保留情况、Hook 映射与证据
- `docs/PROJECT_OVERVIEW.md` — 架构、Hook 点、设置项、风险总览
- `docs/STATUS.md` — 当前验证状态、设备现状与关键日志
- `docs/ROADMAP.md` — 迁移任务（M0–M9）与优先级
- `docs/DEVICE_PROBE.md` — 真机验证 runbook（安装 / 抓日志 / 证据清单）
- `tools/dexscan/README.md` — 静态分析复现方法

## 改动 Hook 的方式

宿主类名/方法名集中在 `app/src/main/kotlin/com/ctf/bilisb/host/HostTargets.kt` 的候选表里，
改版时先改这张表，不要在各 Hook 文件里散写类名；每条 Hook 都会经 `HookProbe` 记录命中/缺失
（日志里 `[probe] hook summary: N/M hit`）。

## 参考实现

- BiliRoaming: https://github.com/yujincheng08/BiliRoaming
- YouTube SponsorBlock: https://github.com/ajayyy/SponsorBlock

## 许可

见 LICENSE。

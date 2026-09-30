# 真机恢复说明：0.7.1 装机（2026-09-30）

> 场景：`release` 包 0.7.1 需覆盖安装，但设备上原有的是 **debug 签名** 的 0.7.0，
> 两者签名不同不能互相覆盖 → 走了「卸载旧模块 + 装 release」。
> 卸载/重装过程中把 LSPosed 守护进程的模块表弄陈旧了，我随后又 `kill -9` 了 `lspd`，
> 导致注入链路彻底断开。**重启设备即可恢复**（详见 §3）。

## 1. 当前状态

| 项 | 状态 |
| --- | --- |
| 模块包 | `io.github.ch6vip.bilisb` **0.7.1（versionCode 11，release 签名 `CN=Bili2233`）已安装** |
| 模块设置 | **已从备份恢复**（`shared_prefs/sponsorblock_settings.xml` + `files/sponsorblock_settings.json`，uid 已修正为应用自己） |
| 用户 ID | 已随设置恢复（无需重新生成） |
| LSPosed 库记录 | `modules` 有该模块、`modules_state.enabled=1`；`scope` 表里它那行被卸载清掉了 |
| 宿主 B 站 | **未改动**，正常可用 |
| LSPosed 注入 | ❌ **当前断开**：`lspctl status` 报 `System server: not connected`、`lspctl module list` 为空、模块日志为空文件 |
| 备份位置 | `E:\ctf-aaa\bili\bilisb-backup-0.7.0\`（含 `bilisb-backup\` 数据目录、`sponsorblock_stats.json` 统计、`lspd\` 与 `lspd2\` 的 LSPosed 库副本、`lspd-logs\` 日志） |

统计备份内容（卸载前采集）：`totalCount=44`、`totalDurationMs=4221329`、6 个分类明细 —— 这份统计在**宿主**目录
`/data/data/com.bilibili.app.in/sponsorblock_stats.json`，卸载模块**不会**删它，所以现在应该还在。

## 2. 为什么会断（三条事实，供以后避免）

1. **卸载模块会清掉 LSPosed 库里它的 scope 记录**（`modules` / `modules_state` 会随重装重建，
   `scope` 不会），而守护进程的**模块表在重装当下会变陈旧** —— 崩溃转储里能同时看到
   `Failed to find package info of io.github.ch6vip.bilisb`（16:17:12）与
   `lsposed_module_updated` 通知（16:17:16）。
2. **`lspd` 被 SIGKILL 后不会自愈**：`kill -9` 只留下 `daemon-*-crash-*.log` 与
   `E LSPosed : lspd 1754 exited unexpectedly, code=137`。**以后不要 kill 它**。
3. **手工拉起 daemon 补不回链路**：`service.sh` 里确实是 `daemon --force`，我照做后进程在
   （`pidof lspd` 有值），但 `System server: not connected`、`lspctl module list` 为空、
   模块日志是空文件（21 字节）。这条链只在**开机时**建立，所以只能重启。

## 3. 恢复步骤（重启后）

1. **重启设备**，然后在手机上**解锁一次**（锁屏密码 adb 过不了；`su`/注入在解锁后才完全正常）。
2. 确认框架已连上：

   ```powershell
   $adb = "C:\android-sdk\platform-tools\adb.exe"
   & $adb shell "/data/adb/modules/zygisk_lsposed/lspctl status"
   # 期望：System server: connected，Modules: 1 installed（我们的模块）
   & $adb shell "/data/adb/modules/zygisk_lsposed/lspctl module list"
   ```

3. **如果作用域缺失**（`lspctl scope` 里看不到 `io.github.ch6vip.bilisb → com.bilibili.app.in`）：
   我们用的是新式 `staticScope=true`（声明在 APK 的 `META-INF/xposed/scope.list`），
   通常开机自动生效；若没有，用 lspctl 补：

   ```powershell
   & $adb shell "/data/adb/modules/zygisk_lsposed/lspctl scope --help"   # 看子命令
   # 或直接改库：/data/adb/lspd/config/modules_config.db 的 scope 表插入
   #   (io.github.ch6vip.bilisb, com.bilibili.app.in, 0)
   ```

4. 重启宿主并抓日志：

   ```powershell
   & $adb shell "am force-stop com.bilibili.app.in"
   & $adb shell "am start -n com.bilibili.app.in/tv.danmaku.bili.MainActivityV2"
   Start-Sleep 15
   # 模块日志（TAG 是 LSPosedFramework，不是 logcat 的 Bili2233）
   & $adb shell "su -c 'ls -t /data/adb/lspd/log/modules_*.log | head -1'"
   ```

## 4. 这一版要看的四件事（0.7.1 的未复核项）

| # | 关键字 | 期望 | 对应改动 |
| --- | --- | --- | --- |
| 1 | `Bili2233 module loaded in com.bilibili.app.in` + `hook summary: N/M hit` | 命中数与 0.7.0 基线（34/39）相当，主链路 0 MISS | 注入是否正常 |
| 2 | `pollerMissingForHandle` | **不出现** | 本轮修的轮询生命周期（有 handle ⇒ 有 poller） |
| 3 | `seekTick feed #n pos=… dur=…` | 播放中持续出现（500ms 一条心跳，每 50 次打一条） | 进度喂入没停 |
| 4 | `manualSkipAnchorFallback` / `countdownAnchorFallback` | 出现时看 `reason=`；正常播放页**不该**出现（说明挂到了播放器容器上） | 本轮改的浮层挂载点 |

另外两条人工观察：
- **浮层位置**：开启「手动跳过」或「倒计时」后，按钮/浮层应贴在**播放器右下角**并随播放器 bounds
  （详情页向下滚动时跟着走）；若回落 decorView（日志有 fallback），它会停在整屏右下角。
- **英文界面**：系统语言切英文后，设置页与播放器「空降助手」面板应全英文（日志仍中文，属有意）。

## 5. 回退路径

- 想回到 0.7.0（debug）：重新构建 debug 包 → 卸载 0.7.1 → 装 debug。
  参考 `docs/DEVICE_PROBE.md`；构建命令见 README。
- 设置与统计的备份都在 `E:\ctf-aaa\bili\bilisb-backup-0.7.0\`，回退后可按 §1 的方式重新放回。

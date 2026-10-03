# 真机与模块运维 runbook

> 合并自已删除的 `DEVICE_PROBE.md`（6.5.0 探针版 runbook）与 `RECOVERY_0.7.1_DEVICE.md`
> （0.7.1 装机事故记录）中**仍然有效**的运维知识；探针版构建与 11 条证据清单已随
> 6.5.0 收口作废，历史结论见 `docs/STATUS.md`。

## 1. 环境事实

| 项 | 值 |
| --- | --- |
| 设备 | Xiaomi，arm64-v8a，Android 16（SDK 36），KernelSU root |
| LSPosed | Zygisk 模块 `zygisk_lsposed`。**没有**独立 `org.lsposed.manager` 包——寄生式管理器，不是「没装」 |
| 宿主 | `com.bilibili.app.in`（国际版 6.5.0 / 6.6.0，见 README 兼容声明） |
| 限制 | `.apks` 只带 arm64_v8a，x86_64 模拟器跑不了宿主，**必须真机** |

## 2. 构建、安装与日志

```powershell
.\gradlew.bat :app:assembleRelease          # 或 assembleDebug
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am force-stop com.bilibili.app.in # 改代码后必须 force-stop 再开，否则跑旧代码
adb shell am start -n com.bilibili.app.in/tv.danmaku.bili.MainActivityV2
```

**模块日志不在** `adb logcat -s Bili2233`（常为空），而在 LSPosed 模块日志：
`/data/adb/lspd/log/modules_*.log`，TAG 实际是 `LSPosedFramework`：

```powershell
adb shell "su -c 'tail -50 $(ls -t /data/adb/lspd/log/modules_*.log | head -1)'"
adb logcat -d | grep -E "probe|Bili2233"     # logcat 里 TAG 为 LSPosedFramework 也可抓到
```

健康基线：冷启动后看 `[probe] hook summary: N/M hit`（命中数与上一版基线相当、主链路 0 MISS）。
证据采集三段式：冷启动进首页 → 进「我的」页 → 进播放页播 30 秒。

## 3. Hook 未命中三步

1. `tools/dexscan` 重新确认类/方法还存在（宿主小版本可能又混淆）；
2. 新名字加进 `host/HostTargets.kt` 的对应候选列表（不要散落到各 Hook 文件）；
3. 重构建 → force-stop → 重跑。

## 4. 签名切换 / 模块重装事故恢复（0.7.1 案例）

debug 与 release 签名不同**不能互相覆盖** → 卸载重装会清掉 LSPosed 库里的 scope 行；
`kill -9 lspd` 会彻底断开注入链路。恢复：

1. **重启设备**，解锁一次（锁屏密码 adb 过不了，`su`/注入在解锁后才完全正常）；
2. 验证框架连接：`adb shell "/data/adb/modules/zygisk_lsposed/lspctl status"`
   （期望 `System server: connected`）+ `lspctl module list`；
3. 作用域缺失时：模块用 `staticScope=true`（APK `META-INF/xposed/scope.list`），开机自动生效；
   若没有，`lspctl scope` 补（或改 `/data/adb/lspd/config/modules_config.db` 的 scope 表）；
4. force-stop 宿主重开，按 §2 查 hook summary。

重装/卸载前先备份设置与统计（宿主数据目录 `sponsorblock_settings*` 与 stats 文件）。

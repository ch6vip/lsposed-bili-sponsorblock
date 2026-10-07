# 真机部署与验证规则

## 1. 运行环境前置要求
- 设备：真机（arm64-v8a），Android 16（SDK 36），已获取 KernelSU / Magisk root。
- 宿主：`com.bilibili.app.in`（国际版 6.5.0 / 6.6.0）。
- 框架：Zygisk 模块 `zygisk_lsposed`（寄生式管理器，无独立 Manager APK）。

## 2. 部署标准化命令
```powershell
# 1. 编译 Release 包
.\gradlew.bat :app:assembleRelease

# 2. ADB 安装（保留数据与配置）
adb install -r app\build\outputs\apk\release\app-release.apk

# 3. 彻底杀死宿主全部进程（必须强杀，避免旧内存驻留）
adb shell su -c "pkill -9 -f com.bilibili.app.in"

# 4. 启动宿主客户端
adb shell am start -n com.bilibili.app.in/tv.danmaku.bili.MainActivityV2
```

## 3. 日志抓取与判定标准
1. **LSPosed 日志标签**：
   - 模块在 logcat 中的 TAG 绝大多数为 `LSPosedFramework`，直接搜索 `Bili2233` 可能会丢失重要输出。
   - 标准过滤命令：
     ```powershell
     adb logcat -d | Select-String -Pattern "probe|Bili2233|unlock|homeTab"
     ```
2. **基线判定**：
   - 冷启动日志中寻找 `[probe] hook summary: N/M hit`。
   - 主流程关键探针必须全部命中（0 MISS）：
     - `unlock:akCapture` (AccessKey 捕获)
     - `unlock:playViewUnite` (播放流拦截)
     - `homeTab:vmV0` / `homeTab:repoE` (首页页签注入)
     - `settings:bridge` (设置桥接)

## 4. 界面截图与视觉验收
- 截图命令：
  ```powershell
  adb exec-out screencap -p > <artifacts_dir>\screenshot.png
  ```
- 屏幕分辨率基准：1220x2712（Xiaomi 13T Pro）。
- 视觉验收重点：
  - 首页顶栏是否包含「追番（大陆）」与「追番（港澳台）」。
  - 港澳台追番页内是否正常拉取「咒術迴戰」、「鏈鋸人」、「BLEACH」等仅限港澳台番剧。
  - 播放器进度条是否正常渲染彩色 SponsorBlock 标记。

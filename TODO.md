# LSPosed BiliSponsorBlock TODO

## 已完成功能 (当前)
- [x] 视频 aid/cid 识别 (PlayDirectorServiceV3 + PlayableParams)
- [x] SponsorBlock API 片段获取
- [x] 自动跳过赞助商片段
- [x] Toast 提示 (原生 Android Toast)
- [x] 进度条黄色标记 (ProgressMarkerPainter)
- [x] 标记提交按钮注入
- [x] 防重复跳过 (skippedSegments set)

## 待修复 Bug
- [x] **时间显示位置不对** — 已修复 (commit 4fbe083)
  - 问题: 进度条隐藏后再显示，扣减文本 `(15:05)` 消失
  - 原因: B站显示进度条时重新调用 setText，覆盖了我们在 onPlayerProgressChange 里的修改
  - 解决: hook setText(CharSequence, BufferType)，每次设置文本后重新追加扣减时长，用 ThreadLocal 防递归
  - 验证: 竖屏/半屏模式扣减文本持久显示

## 待实现功能

### 1. 设置界面 (高优先级)
- [x] 基础设置页
- [x] 功能开关:
  - [x] 自动跳过总开关
  - [x] 各类别片段开关 (sponsor/intro/outro/interaction/selfpromo/music_offtopic/poi_highlight)
  - [x] 进度条标记开关
  - [x] 剩余时长扣减开关
- [x] 标记颜色配置:
  - [x] 每个类别可自定义颜色 (设置项 `color_<category>`,hex 存储)
  - [x] 颜色选择器 (`ColorPickerDialog`:预设色板 + hex 手输,两个设置界面共用)
- [ ] 提交配置:
  - [ ] 用户 ID 管理
  - [ ] 默认标记类别
- [x] 基础存储与 Hook 端读取

### 1.1 设置入口稳定性
- [x] 缩窄 `MineMenuInjector` 的 hook 面 — 从全局 `RecyclerView.Adapter.notifyDataSetChanged` 收窄到 `HomeUserCenterAdapter`（commit 912bd4d）
- [x] 数据字段按内容定位 — 取元素为 `MenuGroup` 的 List，不再假设“第一个 List 字段”，兼容字段重排
- [ ] 记录不同 B 站页面布局下设置入口是否稳定出现

### 2. 跳过策略优化
- [x] 支持 manual skip (片段内浮出"跳过"按钮,点按才跳,设置项 `manual_skip` 覆盖自动跳过)
- [x] 支持 mute (设置项 `mute_segments`,对 actionType=mute 片段用 AudioManager 静音/取消静音;core 无保留名音量方法故走系统音频流)
- [x] 最小片段时长过滤 (设置项 `min_skip_duration` 秒,短于阈值不跳过/不显示按钮)

### 3. UI 优化
- [x] 跳过按钮 (手动模式下播放器右下角浮出胶囊按钮 `ManualSkipButton`)
- [x] 倒计时取消 (设置项 `skip_countdown` 秒,进入片段显示 "N秒后跳过 [取消]",`SkipCountdownOverlay`)
- [x] 片段统计 (已跳过时长累计) — `SkipStatsStore` 进程内单例 + 宿主数据目录 JSON 持久化;立即/倒计时/手动三处跳过点记录;in-app 设置对话框显示总计+分类明细+重置

### 4. 性能优化
- [ ] 片段缓存 TTL 可配置
- [x] 基础重复请求去重
- [ ] 内存占用优化

## 技术债务
- [ ] 移除所有调试日志 (seekbar draw triggered 等)
- [ ] 单元测试覆盖
- [ ] 错误处理增强

## 参考实现
- BiliRoaming: https://github.com/yujincheng08/BiliRoaming
- YouTube SponsorBlock: https://github.com/ajayyy/SponsorBlock

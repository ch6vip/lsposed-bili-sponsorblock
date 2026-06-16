# LSPosed BiliSponsorBlock TODO

## 已完成功能 (v0.3.0)
- [x] 视频 aid/cid 识别 (PlayDirectorServiceV3 + PlayableParams)
- [x] SponsorBlock API 片段获取
- [x] 自动跳过赞助商片段
- [x] Toast 提示 (原生 Android Toast)
- [x] 进度条黄色标记 (ProgressMarkerPainter)
- [x] 标记提交按钮注入
- [x] 防重复跳过 (skippedSegments set)

## 待修复 Bug
- [ ] **时间显示位置不对** — 剩余时长调整功能工作了，但显示在了错误的 TextView 上
  - 日志显示: `adjusting duration from 1399085ms to 1314834ms`
  - 应该显示: `05:30 / 30:00 (25:00)` (括号内是扣除片段后的时长)
  - 实际情况: 显示位置不对
  - 需要调试: 找到正确的进度文本 TextView

## 待实现功能

### 1. 设置界面 (高优先级)
- [ ] 创建设置 Activity/Fragment
- [ ] 功能开关:
  - [ ] 自动跳过总开关
  - [ ] 各类别片段开关 (sponsor/intro/outro/interaction/selfpromo/music_offtopic/poi_highlight)
  - [ ] 进度条标记开关
  - [ ] 剩余时长扣减开关
- [ ] 标记颜色配置:
  - [ ] 每个类别可自定义颜色
  - [ ] 颜色选择器
- [ ] 提交配置:
  - [ ] 用户 ID 管理
  - [ ] 默认标记类别
- [ ] 存储: SharedPreferences

### 2. 跳过策略优化
- [ ] 支持 manual skip (显示跳过按钮而非自动跳过)
- [ ] 支持 mute (静音而非跳过)
- [ ] 最小片段时长过滤 (例如 <3秒 的不跳过)

### 3. UI 优化
- [ ] 跳过按钮 (类似 YouTube SponsorBlock 的绿色按钮)
- [ ] 倒计时取消 (显示 "3秒后跳过 [取消]")
- [ ] 片段统计 (已跳过时长累计)

### 4. 性能优化
- [ ] 片段缓存 TTL 可配置
- [ ] 重复请求去重
- [ ] 内存占用优化

## 技术债务
- [ ] 移除所有调试日志 (seekbar draw triggered 等)
- [ ] 单元测试覆盖
- [ ] 错误处理增强

## 参考实现
- BiliRoaming: https://github.com/yujincheng08/BiliRoaming
- YouTube SponsorBlock: https://github.com/ajayyy/SponsorBlock

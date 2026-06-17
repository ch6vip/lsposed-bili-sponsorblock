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
- [ ] 标记颜色配置:
  - [ ] 每个类别可自定义颜色
  - [ ] 颜色选择器
- [ ] 提交配置:
  - [ ] 用户 ID 管理
  - [ ] 默认标记类别
- [x] 基础存储与 Hook 端读取

### 1.1 设置入口稳定性
- [ ] 缩窄 `MineMenuInjector` 的 hook 面，减少对 `RecyclerView.Adapter.notifyDataSetChanged` 的依赖
- [ ] 记录不同 B 站页面布局下设置入口是否稳定出现

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
- [x] 基础重复请求去重
- [ ] 内存占用优化

## 技术债务
- [ ] 移除所有调试日志 (seekbar draw triggered 等)
- [ ] 单元测试覆盖
- [ ] 错误处理增强

## 参考实现
- BiliRoaming: https://github.com/yujincheng08/BiliRoaming
- YouTube SponsorBlock: https://github.com/ajayyy/SponsorBlock

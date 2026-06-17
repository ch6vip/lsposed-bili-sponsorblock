# 当前状态

日期：2026-06-18

## 结论

`Bili2233` v0.5.0 已完成一次可运行验证，`我的` 页入口链路正常，设置弹窗主页 / 详情页 / 关于区可正常打开。

## 已验证

- 模块加载进 `tv.danmaku.bili` 主进程
- 非主进程 `:web` / `:download` / `:ijkservice` 会跳过 hook
- `HomeUserCenterAdapter.notifyDataSetChanged` 已成功 hook
- `HomeUserCenterAdapter.onBindViewHolder` 已成功 hook
- `MenuGroup.Item` 注入成功，位置为 `3`
- 点击监听已绑定成功
- 入口点击后能正常弹出 `Bili2233`
- 主页 `SponsorBlock` 入口可进入详情页，详情页「返回」可回到主页
- 「关于」区已显示版本、作者、更新摘要
- 修改设置后重新进入播放页面生效，无需重启应用
- 已新增默认标记类别设置，提交按钮初始类别跟随设置快照
- 已新增用户 ID 管理和片段缓存 TTL 设置

## 关键日志

- `Hooked HomeUserCenterAdapter.notifyDataSetChanged`
- `Hooked HomeUserCenterAdapter.onBindViewHolder`
- `Found mine adapter: tv.danmaku.bili.ui.main2.mine.HomeUserCenterAdapter`
- `Injected Bili2233 setting item at position 3`
- `Attached click listener to Bili2233 setting item`
- `Showing Bili2233 settings dialog`

## 未完成

- 残留旧名文案/注释清理
- 不同 B 站页面布局下设置入口稳定性进一步观察
- 新功能扩展

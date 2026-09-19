# Agent Note: 全量审查后的交叉确认修复

Status: implemented

## Problem

四路审查（安全 / 跳过业务 / Hook / 设置 UI）交叉核对后，确认一组会让功能静默失效、把 STREAM_MUSIC 卡死、或把提交身份打进日志的缺陷：

- TTL=0 或缓存过期后 `getCached` 返回 null，决策层整集不再跳。
- unmute 不带 contextHash，detach 后 `mutedContexts` 清不到真账。
- `bindPlayerHandle` 每次清 skipped 桶，全屏重绑会再 skip。
- `server_address` 只在 putSettings 做前缀检查；镜像读路径不消毒；HttpURLConnection 默认跟随重定向。
- SettingsSnapshot / 面板改 userId 把完整 32 hex 打进 LSPosed 日志。
- 设置页统计 `packageName != MODULE_PACKAGE` 写反。

## Decision

跳过决策与新鲜 TTL 缓存解耦：仓库另存 `lastKnown`，过期只清新鲜条目。刷新用 generation 丢掉在途写入。unmute 一律带记账 hash。自定义服务器地址用 `URI` 校验（http(s)、有 host、无 userinfo、无空白），Codec 三通道读路径都走 sanitizer；客户端关闭重定向。日志只留 userId 前 4 位。统计展示改成模块进程才显示说明。

## Alternatives considered

- **禁止 http / 禁内网地址**：用户会自建局域网实例，测试也覆盖 `10.0.2.2`，只收口形态不收口拓扑。
- **HMAC 镜像**：对抗模型是同 UID 宿主，签名防不了自己；尺寸上限 + 读路径 sanitizer 更贴威胁。
- **宿主签名钉扎**：会误伤改包 6.5.0，LSPosed 用户常见，未做。
- **删 GET 提交降级**：旧实例仍可能 405，保留但不再跟随重定向。

## Consequences

- TTL=0 语义变成「每次进页都拉、但拉到的结果立刻可用于跳过」，不再是「功能关闭」。
- 非法镜像 `user_id` 会被读成空串，提交走 `UserIdentityStore` 生成/迁移。
- `onDetachedFromWindow` 改走 `getMethod`，未覆写的 widget 也能挂 teardown。
- 未修：director `z0` 注销、IP 属地改写范围、深色主题浅卡片对比度。

## Verification

`:app:testDebugUnitTest` 通过（本机 gradle-8.12 `--offline`）。

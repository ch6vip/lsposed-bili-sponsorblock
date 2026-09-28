# Agent Note: 裁撤 HomeTab / 底栏删 tab

Status: implemented

## Problem

BiliTamer 的「首页顶栏消息入口」和「底栏删消息 / 我的 tab」移植到 6.5.0 后，经嗅探、默认加载器直连、Compose 三漏斗（CachedResourceResolver / MainResourceManager）三轮修复仍不生效。继续砸会把增强组绑在一条已经证伪的 UI 链上，开关和文档也会继续假装这些能力还活着。

## Decision

按需求整组删除 HomeTabHooks 及相关设置键，增强组收敛为现行四件套：IP 属地、隐藏互动提示、首页不自动刷新、分享到 QQ。入口只留 `EnhanceHooks` 这四个 `install`。底栏 / 顶栏 tab 不再作为待办或候选 hook。

## Alternatives considered

- **再补一轮 Compose 资源漏斗**：6.5.0 主 2 类经默认加载器可达，但底栏首建仍早于过滤，三轮后用户可见行为为零。继续投入只会扩大候选表。
- **开关保留、hook 空转**：设置页会继续承诺一项永远不生效的能力，比删除更糟。
- **降级为「仅文档记录、代码留着」**：死代码会在下次改版时被当成可修 bug 重新激活。

## Consequences

- 增强组从「移植一整份 BiliTamer」变成 4 组 hook / 6 个开关，全部默认关。
- 旧镜像里的 `enhance_home_topbar_message` / `enhance_home_tab_remove_*` 被 Codec 忽略，读成不存在。
- 6.5.0 上若将来有人再提「删消息 tab」，先读本 note 和 `EnhanceHooks` 头注释，不要从 git 历史把 HomeTabHooks 捞回来。

## Verification

`16eb587` / `cc74948` 已从代码、设置键、README 设置表删除。2026-09-21 真机 `hook summary: 33/38 hit` 的安装清单里没有 HomeTab / 底栏 tab 项。

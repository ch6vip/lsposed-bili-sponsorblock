package com.ctf.bilisb.hook

import com.ctf.bilisb.host.HookProbe
import io.github.libxposed.api.XposedModule

/**
 * 「B 站增强」功能调度入口(能力移植自 BiliTamer,MIT)。
 *
 * 现行四件套:IP 属地 / 隐藏互动提示 / 首页不自动刷新 / 分享到 QQ。
 * 全部默认关闭,开关在各自 hook 回调内实时读 EnhanceFlags(热生效);
 * 这里不做任何过滤 —— 装上后由开关决定是否干预。各功能独立安装、互不拖垮。
 *
 * 明确不再投入:首页顶栏消息入口 / 底栏删「消息」tab / 底栏删「我的」tab
 * 曾移植为 HomeTabHooks,6.5.0 上经嗅探→默认加载器→Compose 三漏斗仍未生效,
 * 2026-09-19 按需求整体移除。不要复活。
 * Note: 裁撤理由见 .agents/notes/implemented/simplification/2026-09-19-remove-hometab.md
 */
object EnhanceHooks {
    fun install(module: XposedModule, cl: ClassLoader) {
        val groups = listOf(
            "enhance:interactHint" to { InteractHintHooks.install(module, cl) },
            "enhance:noAutoRefresh" to { HomeNoAutoRefreshHooks.install(module, cl) },
            "enhance:shareQq" to { ShareQqHooks.install(module, cl) },
            "enhance:ipLocation" to { IpLocationHooks.install(module, cl) },
        )
        for ((key, block) in groups) {
            runCatching { block() }.onFailure {
                HookProbe.miss(module, key, "install threw: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }
}

package com.ctf.bilisb.hook

import com.ctf.bilisb.host.HookProbe
import io.github.libxposed.api.XposedModule

/**
 * 「B 站增强」功能调度入口(能力移植自 BiliTamer,MIT)。
 *
 * 历史:首页顶栏消息入口 / 底栏删「消息」tab 曾移植(HomeTabHooks),6.5.0 上
 * 经三轮修复(嗅探→默认加载器→Compose 三漏斗)仍未生效,2026-09-19 按需求整体移除。
 *
 * 各功能独立安装、互不拖垮;开关在各自 hook 回调内实时读 EnhanceFlags(热生效),
 * 这里不做任何过滤 —— 装上后由开关决定是否干预。
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

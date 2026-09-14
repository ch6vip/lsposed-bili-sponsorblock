package com.ctf.bilisb.player

import android.content.Context
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets

/**
 * 播放器容器桥接。
 *
 * 只负责从播放器容器上取 core / android context，用于 seek 跳过与 toast 提示。
 *
 * 6.5.0 的容器实现的是 `tv.danmaku.biliplayerv2.f`（由 widget 的
 * `bindPlayerContainer(f)` 传入），取 Context 的方法是 `t()`；
 * 旧目标（8.96）容器类 `be1.j` 用的是 `getContext()`。两者都按候选尝试。
 *
 * video id(aid / cid)不由这里取 —— 见 [VideoDirectorListener]。
 */
object PlayerBridge {
    /** core 服务接口名（6.5.0：`tv.danmaku.biliplayerv2.service.D`）。 */
    private const val CORE_SERVICE_TYPE = "tv.danmaku.biliplayerv2.service.D"

    fun coreService(playerContainer: Any): Any? {
        return HookResolve.invokeNoArg(playerContainer, listOf(HostTargets.GET_CORE_METHOD))
    }

    /**
     * 从 director 服务实例上取 core。
     *
     * 真机实测：`bindPlayerContainer` 触发时 widget 的 `getPlayerCoreService()` 还是 null
     * （core 是稍后注入的），而 `PlayDirectorServiceV3` 里有 `f: service.D` 字段，
     * 所以用「字段类型名匹配」把它读出来，作为 core 的兜底来源。
     */
    fun coreServiceFromDirector(directorService: Any?): Any? {
        if (directorService == null) return null
        return runCatching {
            directorService.javaClass.declaredFields.firstOrNull { it.type.name == CORE_SERVICE_TYPE }
                ?.apply { isAccessible = true }
                ?.get(directorService)
        }.getOrNull()
    }

    fun context(playerContainer: Any): Context? {
        if (playerContainer is Context) return playerContainer
        if (playerContainer is android.view.View) return playerContainer.context
        val value = HookResolve.invokeNoArg(playerContainer, HostTargets.CONTAINER_CONTEXT_METHODS)
        return value as? Context
    }

    /**
     * 从宿主对象（容器 / widget / View）解包出 Activity。
     *
     * 6.5.0 真机实测：容器的 Context 是主题包装后的 ContextWrapper，不是 Activity，
     * 所以必须逐层解包 `baseContext`；同时取 Context 的方法名是 `t()` 而不是 `getContext()`。
     */
    fun activity(host: Any): android.app.Activity? {
        var current: Context? = context(host)
        var depth = 0
        while (current != null && depth < 10) {
            if (current is android.app.Activity) return current
            current = (current as? android.content.ContextWrapper)?.baseContext
            depth++
        }
        return null
    }

    fun contextHash(playerContainer: Any): Int {
        return context(playerContainer)?.hashCode() ?: 0
    }

    fun contextHash(context: Context): Int {
        return context.hashCode()
    }
}

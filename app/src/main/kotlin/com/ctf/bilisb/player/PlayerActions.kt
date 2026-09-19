package com.ctf.bilisb.player

import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

/**
 * 播放器 core 操作（seek / 时长 / 位置）的反射封装。
 *
 * 6.5.0（`com.bilibili.app.in`）实测：
 *   - `getDuration()` / `getCurrentPosition()` 名字保留，返回 int（毫秒）
 *   - `seekTo(int)` 是默认方法，方法体就是 `o(pos, false)`；
 *     平滑 seek 必须调 `o(int, boolean)`（旧目标是 `seekTo(int, boolean)`）
 */
object PlayerActions {
    fun seekTo(module: XposedModule, core: Any, positionMs: Long): Boolean {
        val smoothMethod = HookResolve.forTarget(
            core,
            HostTargets.SEEK_SMOOTH_METHODS,
            Integer.TYPE,
            java.lang.Boolean.TYPE,
        )
        if (smoothMethod != null) {
            return runCatching { smoothMethod.invoke(core, positionMs.toInt(), true); true }
                .onFailure { module.info("seekTo(${smoothMethod.name}) failed: ${it.javaClass.name}: ${it.message}") }
                .getOrDefault(false)
        }

        val plainMethod = HookResolve.forTarget(
            core,
            HostTargets.SEEK_PLAIN_METHODS,
            Integer.TYPE,
        )
        if (plainMethod != null) {
            return runCatching { plainMethod.invoke(core, positionMs.toInt()); true }
                .onFailure { module.info("seekTo(${plainMethod.name}) failed: ${it.javaClass.name}: ${it.message}") }
                .getOrDefault(false)
        }

        module.info("seekTo unresolved on ${core.javaClass.name}")
        return false
    }

    /** 反射 `IPlayerCoreService#getCurrentPosition()`,返回当前播放位置(ms)。 */
    fun currentPositionMs(module: XposedModule, core: Any): Long? {
        return invokeLongNoArg(module, core, HostTargets.GET_POSITION_METHODS)
    }

    /** 反射 `IPlayerCoreService#getDuration()`,返回视频总时长(ms)。 */
    fun durationMs(module: XposedModule, core: Any): Long? {
        return invokeLongNoArg(module, core, HostTargets.GET_DURATION_METHODS)
    }

    private fun invokeLongNoArg(module: XposedModule, target: Any, methodNames: List<String>): Long? {
        val method = HookResolve.forTarget(target, methodNames) ?: run {
            module.info("${methodNames.first()} unresolved on ${target.javaClass.name}")
            return null
        }
        return runCatching { (method.invoke(target) as? Number)?.toLong() }
            .onFailure { module.info("${method.name} failed: ${it.javaClass.name}: ${it.message}") }
            .getOrNull()
    }
}

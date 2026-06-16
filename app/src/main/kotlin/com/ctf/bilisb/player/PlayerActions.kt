package com.ctf.bilisb.player

import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

object PlayerActions {
    fun seekTo(module: XposedModule, core: Any, positionMs: Long) {
        runCatching {
            val method = core.javaClass.getDeclaredMethod(
                "seekTo",
                Integer.TYPE,
                java.lang.Boolean.TYPE,
            ).apply { isAccessible = true }
            method.invoke(core, positionMs.toInt(), true)
        }.onFailure { throwable ->
            module.info("seekTo failed: ${throwable.javaClass.name}: ${throwable.message}")
        }
    }

    /**
     * 反射 `IPlayerCoreService#getCurrentPosition()`,返回当前播放位置(ms)。
     * 对应 APK `PlayerHookProvider.n(obj)` = getCurrentPositionMethodName。
     */
    fun currentPositionMs(module: XposedModule, core: Any): Long? {
        return invokeLongNoArg(module, core, "getCurrentPosition")
    }

    /**
     * 反射 `IPlayerCoreService#getDuration()`,返回视频总时长(ms)。
     * 对应 APK `PlayerHookProvider.o(obj)` = getDurationMethodName。
     * APK 里返回 int(秒级精度按 ms 计),这里统一按 Number 取 long。
     */
    fun durationMs(module: XposedModule, core: Any): Long? {
        return invokeLongNoArg(module, core, "getDuration")
    }

    private fun invokeLongNoArg(module: XposedModule, target: Any, methodName: String): Long? {
        return runCatching {
            val method = target.javaClass.getDeclaredMethod(methodName).apply { isAccessible = true }
            (method.invoke(target) as? Number)?.toLong()
        }.onFailure { throwable ->
            module.info("$methodName failed: ${throwable.javaClass.name}: ${throwable.message}")
        }.getOrNull()
    }
}


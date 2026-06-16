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
}

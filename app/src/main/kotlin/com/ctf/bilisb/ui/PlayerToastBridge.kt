package com.ctf.bilisb.ui

import android.os.Bundle
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

object PlayerToastBridge {
    fun showSkipToast(module: XposedModule, host: Any, message: String) {
        runCatching {
            val toastService = host.javaClass.getDeclaredMethod("getToastService").apply { isAccessible = true }
                .invoke(host) ?: return

            val toastClass = Class.forName("tv.danmaku.biliplayerv2.widget.toast.PlayerToast")
            val toast = toastClass.getConstructor(Bundle::class.java).newInstance(Bundle()).apply {
                setStringExtra(this, "extra_title", message)
                invokeIfExists(this, "setDuration", 3000L)
                invokeIfExists(this, "setLocation", 33)
                invokeIfExists(this, "setToastType", 17)
                invokeIfExists(this, "setLevel", 2)
                invokeIfExists(this, "setQueueType", 48)
                invokeIfExists(this, "setRefreshDuration", -1L)
                invokeIfExists(this, "setCreateTime", System.currentTimeMillis())
            }

            val showToast = toastService.javaClass.methods.firstOrNull {
                it.name == "showToast" && it.parameterTypes.size == 1
            } ?: return
            showToast.isAccessible = true
            showToast.invoke(toastService, toast)
        }.onFailure { throwable ->
            module.info("showSkipToast failed: ${throwable.javaClass.name}: ${throwable.message}")
        }
    }

    private fun setStringExtra(target: Any, key: String, value: String) {
        invokeIfExists(target, "setExtraString", key, value)
    }

    private fun invokeIfExists(target: Any, methodName: String, vararg args: Any?) {
        val method = target.javaClass.methods.firstOrNull { candidate ->
            candidate.name == methodName && candidate.parameterTypes.size == args.size
        } ?: return
        method.isAccessible = true
        method.invoke(target, *args)
    }
}

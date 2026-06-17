package com.ctf.bilisb.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

object PlayerToastBridge {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    fun showSkipToast(module: XposedModule, host: Any, message: String) {
        showToast(module, host, "跳过: $message")
    }

    fun showMarkToast(module: XposedModule, host: Any, message: String) {
        showToast(module, host, message)
    }

    private fun showToast(module: XposedModule, host: Any, message: String) {
        // 策略:直接用 Android 原生 Toast(简单可靠,BiliRoaming 的降级方案)
        runCatching {
            // 从 player container 获取 context
            val context = runCatching {
                val method = host.javaClass.getDeclaredMethod("getContext").apply { isAccessible = true }
                method.invoke(host) as? Context
            }.getOrNull() ?: return

            handler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
            module.info("showToast: $message")
        }.onFailure { throwable ->
            module.info("showToast failed: ${throwable.javaClass.name}: ${throwable.message}")
        }
    }
}

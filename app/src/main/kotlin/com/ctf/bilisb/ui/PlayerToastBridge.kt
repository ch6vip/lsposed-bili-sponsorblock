package com.ctf.bilisb.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

/**
 * 播放器内 Toast 提示。
 *
 * 6.5.0 坑位（真机复现）：容器取 Context 的方法从 `getContext()` 改成了 `t()`，
 * 旧实现里 `getDeclaredMethod("getContext")` 抛异常后被 `?: return` **静默吞掉**，
 * 表现就是「跳过了但没有 Toast」。现在统一走 [PlayerBridge.context]，
 * 并且取不到 Context 时**必须留日志**，不再静默。
 *
 * 另外加了相同文案的节流：进度回调与倒计时结束时可能连着触发多次，宿主 Toast
 * 队列会排成一长串。节流丢弃**完全不写日志**（否则日志比 Toast 还吵），
 * 只有真正弹出的那一次记 info。
 */
object PlayerToastBridge {
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** 同一文案 1 秒内只弹一次；节流命中直接丢弃，不写日志。 */
    private val throttle = ToastThrottle()

    fun showSkipToast(module: XposedModule, host: Any, message: String) {
        showToast(module, host, "跳过: $message")
    }

    fun showMarkToast(module: XposedModule, host: Any, message: String) {
        showToast(module, host, message)
    }

    private fun showToast(module: XposedModule, host: Any, message: String) {
        val context: Context = PlayerBridge.context(host) ?: run {
            module.info("showToast skipped: no context from ${host.javaClass.name}")
            return
        }

        // 节流时间用单调时钟 uptimeMillis:墙钟回拨最多导致一次重复 Toast,用单调钟避免
        if (!throttle.shouldShow(message, SystemClock.uptimeMillis())) {
            return // 节流丢弃：静默，不刷日志
        }

        handler.post {
            runCatching {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }.onSuccess {
                module.info("showToast: $message")
            }.onFailure { throwable ->
                module.info("showToast failed: ${throwable.javaClass.name}: ${throwable.message}")
            }
        }
    }
}

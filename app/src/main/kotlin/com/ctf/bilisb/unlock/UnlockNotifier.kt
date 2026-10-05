package com.ctf.bilisb.unlock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.ui.ModuleStrings
import io.github.libxposed.api.XposedModule
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 解锁状态提示中心（对齐 BiliRoaming show_info 与 C0232kc.e）。
 *
 * 受 [UnlockConfig.Config.unlockShowInfo] 控制；默认开启。
 * 统一主线程分发与 5 秒级防抖节流，避免在重试风暴与连播时反复刷屏。
 */
object UnlockNotifier {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lastToastRef = AtomicReference<Toast?>(null)
    private val lastShownAt = ConcurrentHashMap<String, Long>()
    private const val DEBOUNCE_MS = 5000L

    private fun postToast(module: XposedModule, text: String, force: Boolean = false) {
        val config = UnlockConfig.load(module)
        if (!force && !config.unlockShowInfo) {
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastShownAt[text] ?: 0L
        if (now - last < DEBOUNCE_MS) return
        lastShownAt[text] = now

        mainHandler.post {
            runCatching {
                val context = getApplicationContext() ?: return@runCatching
                lastToastRef.get()?.cancel()
                val toast = Toast.makeText(context, "哔哩解锁：$text", Toast.LENGTH_SHORT)
                lastToastRef.set(toast)
                toast.show()
                HookProbe.first(module, "unlock:toastShown", 5) { text }
            }.onFailure { t ->
                HookProbe.first(module, "unlock:toastError", 2) { "${t.javaClass.simpleName}: ${t.message}" }
            }
        }
    }

    private fun getApplicationContext(): Context? {
        return runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        }.getOrNull()
    }

    /** 发现受限番剧/视频。 */
    fun toastAreaRestricted(module: XposedModule, isThai: Boolean) {
        val msg = if (isThai) "发现东南亚区域番剧，尝试解锁……" else "发现区域限制番剧，尝试解锁……"
        postToast(module, msg)
    }

    /** 代理服务器成功取回播放地址。 */
    fun toastProxySuccess(module: XposedModule) {
        postToast(module, "已从代理服务器获取播放地址\n如加载缓慢或黑屏，可去漫游设置中测速并设置 UPOS")
    }

    /** 代理服务器失败。 */
    fun toastProxyFailed(module: XposedModule, error: String? = null) {
        val msg = if (!error.isNullOrBlank()) {
            "请求解析服务器发生错误: $error"
        } else {
            "获取播放地址失败"
        }
        postToast(module, msg)
    }

    /** 版权权限已修改为允许下载。 */
    fun toastDownloadAllowed(module: XposedModule) {
        postToast(module, "已允许下载")
    }

    /** UPOS 服务器已切换启用。 */
    fun toastUposEnabled(module: XposedModule, uposHost: String) {
        postToast(module, "已启用 UPOS 服务器：$uposHost")
    }

    fun toastUposEnabled(context: Context, uposHost: String) {
        val text = "已启用 UPOS 服务器：$uposHost"
        Toast.makeText(context, "哔哩解锁：$text", Toast.LENGTH_SHORT).show()
    }
}

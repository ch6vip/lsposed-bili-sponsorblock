package com.ctf.bilisb.settings

import android.content.Context
import android.os.SystemClock
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

/**
 * B 站增强功能的开关读取器(移植自 BiliTamer 的能力,设置管线复用本项目)。
 *
 * 与播放器链路不同:增强 hook 大多挂在首页/网络层,**拿不到「播放器进入」那个
 * 设置加载时机**,所以这里自带一条惰性读取通道:
 *   1. [captureContext] 由各 hook 在碰到 Context/View 时随手捕获(进程内只取一次);
 *      拿到后走 [ModuleSettings] 完整管线(IPC 权威 + 文件兜底),TTL 内复用缓存;
 *   2. 一直拿不到 Context 时退化为只读镜像文件(无 Context 也能读,只是兜底数据)。
 *
 * hook 回调里用 [snapshot] 取整份快照再读字段,不要缓存字段值 —— 开关要能热生效。
 */
object EnhanceFlags {
    private const val TTL_MS = 10_000L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: SettingsSnapshot? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    /** 各 hook 碰到 Context/View 时调用:进程内只记第一个,取 applicationContext 防泄漏。 */
    fun captureContext(host: Any) {
        if (appContext != null) return
        runCatching {
            val context = when (host) {
                is Context -> host.applicationContext ?: host
                is android.view.View -> host.context?.applicationContext ?: host.context
                is android.app.Activity -> host.applicationContext
                else -> null
            }
            if (context is Context) {
                appContext = context
            }
        }
    }

    /** 取当前增强设置快照(TTL 内复用缓存;过期后按来源顺序重读)。 */
    fun snapshot(module: XposedModule): SettingsSnapshot {
        val now = SystemClock.uptimeMillis()
        cached?.let { if (now - cachedAtMs < TTL_MS) return it }
        val context = appContext
        val fresh = runCatching {
            if (context != null) {
                // 不走 ModuleSettings.reload:那会清掉播放器路径的进程缓存。
                ModuleSettings.readUncached(module, context) ?: ModuleSettings.loadFromMirror(module)
            } else {
                ModuleSettings.loadFromMirror(module)
            }
        }.getOrElse {
            module.info("EnhanceFlags: read settings failed: ${it.message}")
            null
        } ?: cached ?: SettingsSnapshot.DEFAULT
        cached = fresh
        cachedAtMs = now
        return fresh
    }
}

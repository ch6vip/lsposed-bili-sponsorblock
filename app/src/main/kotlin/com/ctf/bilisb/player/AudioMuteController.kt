package com.ctf.bilisb.player

import android.content.Context
import android.media.AudioManager
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

/**
 * 片段静音控制。
 *
 * B 站 8.96.0 的播放器 core(biliplayerv2 IPlayerCoreService)在反编译里没有保留名字的
 * 音量/静音方法(seekTo / getCurrentPosition / getDuration 保留了名字,音量相关被混淆),
 * 无法稳定反射。这里改用 Android 的 [AudioManager] 对 STREAM_MUSIC 做静音/取消静音 ——
 * 与宿主混淆无关、可逆、API 23 起可用。
 *
 * 代价:静音作用于整个媒体音频流而非仅 B 站,但片段内短暂静音可接受。
 *
 * ## 多播放器记账
 *
 * 静音是**整个流**的,但「谁需要静音」按 contextHash 记账([mutedContexts]):
 *   - 小窗/多实例场景下,A 在 mute 片段内、B 不在 —— 若用全局布尔,B 的每个回调都会
 *     unmute 再被 A mute 回来,音频反复抖动。按 context 记账后,只有**所有** context
 *     都不再需要静音才真正解除流静音。
 *   - 一个播放器销毁([unmute])只清掉它自己的记账;流层面是否 unmute 由剩余记账决定。
 */
object AudioMuteController {
    private val lock = Any()

    /** 按 contextHash 记账:哪些播放器 context 正处于「我们静音」状态。 */
    private val mutedContexts = HashSet<Int>()

    fun mute(module: XposedModule, host: Any) {
        val contextHash = PlayerBridge.contextHash(host)
        synchronized(lock) {
            if (mutedContexts.contains(contextHash)) return
            // 已有别的 context 在静音中:只记账,不动流(它已经在静音状态)
            if (mutedContexts.isNotEmpty()) {
                mutedContexts.add(contextHash)
                return
            }
        }
        val am = audioManager(host) ?: return
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }.onSuccess {
            synchronized(lock) { mutedContexts.add(contextHash) }
            module.info("audio muted for mute-segment (context=$contextHash)")
        }.onFailure {
            module.info("audio mute failed: ${it.javaClass.name}: ${it.message}")
        }
    }

    /** 解除该 context 的静音记账;只有所有 context 都不再需要时才真正 unmute 流。 */
    fun unmute(module: XposedModule, host: Any) {
        val contextHash = PlayerBridge.contextHash(host)
        val lastRemaining: Boolean
        synchronized(lock) {
            val removed = mutedContexts.remove(contextHash)
            if (!removed) return
            lastRemaining = mutedContexts.isNotEmpty()
        }
        if (lastRemaining) {
            // 还有别的播放器需要静音:保持流静音,只清本 context 的记账
            module.info("audio unmute deferred for context=$contextHash (other contexts still muted)")
            return
        }
        val am = audioManager(host)
        if (am != null) {
            runCatching {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }.onSuccess {
                module.info("audio unmuted")
            }.onFailure {
                module.info("audio unmute failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    private fun audioManager(host: Any): AudioManager? {
        // 6.5.0 容器取 Context 的方法是 t()；统一走 PlayerBridge，避免写死 getContext 后静默失败
        val context = PlayerBridge.context(host) ?: return null
        return context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
}

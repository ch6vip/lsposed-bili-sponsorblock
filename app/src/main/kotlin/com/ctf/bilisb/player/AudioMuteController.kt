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

    /**
     * 对某个 context 记账静音。
     *
     * @param contextHashHint 调用方已知的 contextHash(如 [PlayerHandle.contextHash])。
     *   传 0 时才从 host 反射推算 —— 每个 tick 一次反射 BFS 是可省的。
     */
    fun mute(module: XposedModule, host: Any, contextHashHint: Int = 0) {
        val contextHash = if (contextHashHint != 0) contextHashHint else PlayerBridge.contextHash(host)
        // 流级 mute/unmute 必须与记账在**同一把锁**内完成:
        // 之前「锁内判定 + 锁外动流」存在 TOCTOU —— A 的 unmute 判完「最后一个」释放锁后,
        // B 的 mute 看到空账、把流 mute 并记上账,随后 A 的 UNMUTE 落地把刚静音的流放开,
        // 而 B 的记账还在,后续 mute() 全部命中 contains 短路,该片段从此以有声播放。
        synchronized(lock) {
            if (mutedContexts.contains(contextHash)) return
            // 已有别的 context 在静音中:只记账,不动流(它已经在静音状态)
            if (mutedContexts.isNotEmpty()) {
                mutedContexts.add(contextHash)
                return
            }
            val am = audioManager(host)
            if (am == null) {
                // 拿不到 AudioManager:不记账,下次进度回调还会重试
                module.info("audio mute skipped: no audio manager from ${host.javaClass.name}")
                return
            }
            runCatching {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            }.onSuccess {
                mutedContexts.add(contextHash)
                module.info("audio muted for mute-segment (context=$contextHash)")
            }.onFailure {
                module.info("audio mute failed: ${it.javaClass.name}: ${it.message}")
            }
        }
    }

    /**
     * 解除指定 context 的静音记账;只有所有 context 都不再需要时才真正 unmute 流。
     *
     * 与 [unmute] 的区别:不从 host 上重新算 hash。播放器销毁路径传入的可能是
     * contextHash 本身(此时宿主 widget 已 detach,反射取 Context 会失败拿到 0),
     * 按 hash 解除才是可靠路径。
     *
     * @param audioHost 可用于拿 AudioManager 的宿主对象(可为 null:null 时只清记账,
     *                  流层面的 unmute 由剩余 context 的下一次回调完成)。
     * @return 是否真的解除了流静音(供调用方留日志)。
     */
    fun unmuteContext(module: XposedModule, contextHash: Int, audioHost: Any?): Boolean {
        var am: AudioManager? = null
        synchronized(lock) {
            val removed = mutedContexts.remove(contextHash)
            if (!removed) return false
            if (mutedContexts.isNotEmpty()) {
                module.info("audio unmute deferred for context=$contextHash (other contexts still muted)")
                return false
            }
            // 流级 unmute 也要在锁内:理由见 [mute] 的 TOCTOU 说明
            am = audioHost?.let { audioManager(it) }
            if (am != null) {
                runCatching {
                    am!!.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                }.onSuccess {
                    module.info("audio unmuted")
                }.onFailure {
                    module.info("audio unmute failed: ${it.javaClass.name}: ${it.message}")
                }
            }
        }
        return true
    }

    /** 解除该 context 的静音记账;只有所有 context 都不再需要时才真正 unmute 流。 */
    fun unmute(module: XposedModule, host: Any, contextHashHint: Int = 0) {
        val contextHash = if (contextHashHint != 0) contextHashHint else PlayerBridge.contextHash(host)
        unmuteContext(module, contextHash, host)
    }

    private fun audioManager(host: Any): AudioManager? {
        // 6.5.0 容器取 Context 的方法是 t()；统一走 PlayerBridge，避免写死 getContext 后静默失败
        val context = PlayerBridge.context(host) ?: return null
        return context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
}

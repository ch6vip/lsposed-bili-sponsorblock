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
 * 代价:静音作用于整个媒体音频流而非仅 B 站,但片段内短暂静音可接受。用 [mutedByUs]
 * 跟踪,只在我们静音过的情况下取消静音,避免覆盖用户自己的音量操作。
 */
object AudioMuteController {
    @Volatile private var mutedByUs = false

    fun mute(module: XposedModule, host: Any) {
        if (mutedByUs) return
        val am = audioManager(host) ?: return
        runCatching {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        }.onSuccess {
            mutedByUs = true
            module.info("audio muted for mute-segment")
        }.onFailure {
            module.info("audio mute failed: ${it.javaClass.name}: ${it.message}")
        }
    }

    fun unmute(module: XposedModule, host: Any) {
        if (!mutedByUs) return
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
        // 即使取不到 AudioManager 也清标志,避免卡在"已静音"状态再不尝试。
        mutedByUs = false
    }

    private fun audioManager(host: Any): AudioManager? {
        // 6.5.0 容器取 Context 的方法是 t()；统一走 PlayerBridge，避免写死 getContext 后静默失败
        val context = PlayerBridge.context(host) ?: return null
        return context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
}

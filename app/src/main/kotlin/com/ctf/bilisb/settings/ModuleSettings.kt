package com.ctf.bilisb.settings

import android.content.Context
import android.net.Uri
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule

/**
 * 模块 Hook 端的配置读取器。
 *
 * LSPosed 模块的 Hook 代码运行在目标 App(tv.danmaku.bili)进程里,
 * 无法直接访问模块 APK 的 SharedPreferences(应用沙箱隔离)。
 *
 * 解决方案:模块 APK 声明了一个 exported=true 的 SettingsProvider(ContentProvider),
 * Hook 端通过 ContentResolver.call() 跨进程 IPC 读取设置。
 * 底层走 Binder,不需要特殊权限。
 */
object ModuleSettings {
    @Volatile
    private var cached: SettingsSnapshot? = null

    /**
     * 加载设置。三级 fallback:
     *   1) JSON 镜像文件 (宿主进程内最稳定，避免 MIUI 拦截 provider 拉起)
     *   2) ContentProvider IPC (镜像缺失时兜底)
     *   3) 默认值
     *
     * @param module XposedModule 实例(用于日志)
     * @param hostContext 宿主 App 的 Context(用于获取 ContentResolver)
     */
    fun load(module: XposedModule, hostContext: Context): SettingsSnapshot {
        cached?.let { return it }

        val snapshot = tryFileFallback(module)
            ?: tryIpc(module, hostContext)
            ?: run {
                module.info("ModuleSettings: all sources failed, using defaults")
                SettingsSnapshot.DEFAULT
            }

        cached = snapshot
        module.info("ModuleSettings loaded: $snapshot")
        return snapshot
    }

    /** 强制重新加载(配置变化后调用) */
    fun reload(module: XposedModule, hostContext: Context): SettingsSnapshot {
        cached = null
        return load(module, hostContext)
    }

    /** Level 1: ContentProvider IPC */
    private fun tryIpc(module: XposedModule, hostContext: Context): SettingsSnapshot? {
        return runCatching {
            val uri = Uri.parse("content://${SettingsSyncBridge.AUTHORITY}")
            val bundle = hostContext.contentResolver.call(uri, SettingsSyncBridge.METHOD_GET_SETTINGS, null, null)
                ?: return@runCatching null

            module.info("ModuleSettings: read from ContentProvider IPC")
            SettingsCodec.snapshotFromBundle(bundle)
        }.getOrElse {
            module.info("ModuleSettings: IPC failed: ${it.message}")
            null
        }
    }

    /** Level 2: JSON 镜像文件 */
    private fun tryFileFallback(module: XposedModule): SettingsSnapshot? {
        val candidates = listOf(
            java.io.File("/data/data/com.ctf.bilisb/files", SettingsKeys.MIRROR_FILE),
            java.io.File("/data/data/tv.danmaku.bili", SettingsKeys.MIRROR_FILE),
            java.io.File("/data/user/0/tv.danmaku.bili", SettingsKeys.MIRROR_FILE),
        )

        for (file in candidates) {
            val snapshot = runCatching {
                if (!file.exists() || !file.canRead()) return@runCatching null
                val json = org.json.JSONObject(file.readText())
                module.info("ModuleSettings: read from file ${file.absolutePath}")
                SettingsCodec.snapshotFromJson(json)
            }.getOrNull()
            if (snapshot != null) return snapshot
        }

        module.info("ModuleSettings: no mirror file readable")
        return null
    }
}

/**
 * 配置快照(不可变)。
 */
data class SettingsSnapshot(
    val enabled: Boolean,
    val autoSkip: Boolean,
    val manualSkip: Boolean,
    val muteSegments: Boolean,
    val minSkipDurationSec: Float,
    val skipCountdownSec: Float,
    val serverAddress: String,
    val cacheTtlMs: Long,
    val userId: String,
    val defaultSubmitCategory: String,
    val enabledCategories: Set<String>,
    val showToast: Boolean,
    val showSeekbarMarker: Boolean,
    val showTimeDeduction: Boolean,
    val showSubmitButton: Boolean,
    /** 分类标记颜色:category 字符串 → ARGB int。缺省由 CATEGORY_COLOR_DEFAULTS 填充。 */
    val categoryColors: Map<String, Int>,
) {
    companion object {
        val DEFAULT = SettingsCodec.defaultSnapshot()
    }
}

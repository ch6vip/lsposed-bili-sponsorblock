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
    private const val AUTHORITY = "com.ctf.bilisb.settings"

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
            val uri = Uri.parse("content://$AUTHORITY")
            val bundle = hostContext.contentResolver.call(uri, "getSettings", null, null)
                ?: return@runCatching null

            module.info("ModuleSettings: read from ContentProvider IPC")
            parseFromBundle(bundle)
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
                parseFromJson(json)
            }.getOrNull()
            if (snapshot != null) return snapshot
        }

        module.info("ModuleSettings: no mirror file readable")
        return null
    }

    /** 从 Bundle (ContentProvider 返回) 解析 */
    private fun parseFromBundle(bundle: android.os.Bundle): SettingsSnapshot {
        val enabledCategories = SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            bundle.getBoolean(key, true)
        }.values.toSet()

        return SettingsSnapshot(
            enabled = bundle.getBoolean(SettingsKeys.ENABLED, true),
            autoSkip = bundle.getBoolean(SettingsKeys.AUTO_SKIP, true),
            manualSkip = bundle.getBoolean(SettingsKeys.MANUAL_SKIP, false),
            minSkipDurationSec = parseDuration(bundle.getString(SettingsKeys.MIN_SKIP_DURATION, "0")),
            serverAddress = bundle.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER),
            enabledCategories = enabledCategories,
            showToast = bundle.getBoolean(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = bundle.getBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = bundle.getBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSubmitButton = bundle.getBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
        )
    }

    /** 从 JSON 镜像文件解析 */
    private fun parseFromJson(json: org.json.JSONObject): SettingsSnapshot {
        fun bool(key: String, default: Boolean) =
            if (json.has(key)) json.getBoolean(key) else default

        fun str(key: String, default: String) =
            if (json.has(key)) json.getString(key) else default

        val enabledCategories = SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            bool(key, true)
        }.values.toSet()

        return SettingsSnapshot(
            enabled = bool(SettingsKeys.ENABLED, true),
            autoSkip = bool(SettingsKeys.AUTO_SKIP, true),
            manualSkip = bool(SettingsKeys.MANUAL_SKIP, false),
            minSkipDurationSec = parseDuration(str(SettingsKeys.MIN_SKIP_DURATION, "0")),
            serverAddress = str(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER),
            enabledCategories = enabledCategories,
            showToast = bool(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = bool(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = bool(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSubmitButton = bool(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
        )
    }

    /** 把秒字符串解析成非负 Float,解析失败或负数按 0(不过滤)处理。 */
    private fun parseDuration(raw: String?): Float =
        raw?.trim()?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f
}

/**
 * 配置快照(不可变)。
 */
data class SettingsSnapshot(
    val enabled: Boolean,
    val autoSkip: Boolean,
    val manualSkip: Boolean,
    val minSkipDurationSec: Float,
    val serverAddress: String,
    val enabledCategories: Set<String>,
    val showToast: Boolean,
    val showSeekbarMarker: Boolean,
    val showTimeDeduction: Boolean,
    val showSubmitButton: Boolean,
) {
    companion object {
        val DEFAULT = SettingsSnapshot(
            enabled = true,
            autoSkip = true,
            manualSkip = false,
            minSkipDurationSec = 0f,
            serverAddress = SettingsKeys.DEFAULT_SERVER,
            enabledCategories = setOf(
                "sponsor", "selfpromo", "interaction", "intro",
                "outro", "preview", "music_offtopic", "filler", "poi_highlight"
            ),
            showToast = true,
            showSeekbarMarker = true,
            showTimeDeduction = true,
            showSubmitButton = true,
        )
    }
}

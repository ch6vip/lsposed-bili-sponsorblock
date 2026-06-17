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
     * 加载设置。优先通过 ContentProvider IPC 读取,失败时 fallback 到默认值。
     *
     * @param module XposedModule 实例(用于日志)
     * @param hostContext 宿主 App 的 Context(用于获取 ContentResolver)
     */
    fun load(module: XposedModule, hostContext: Context): SettingsSnapshot {
        cached?.let { return it }

        val snapshot = runCatching {
            readViaContentProvider(module, hostContext)
        }.getOrElse {
            module.info("ModuleSettings: IPC failed, using defaults: ${it.message}")
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

    private fun readViaContentProvider(module: XposedModule, hostContext: Context): SettingsSnapshot {
        val uri = Uri.parse("content://$AUTHORITY")
        val bundle = hostContext.contentResolver.call(uri, "getSettings", null, null)

        if (bundle == null) {
            module.info("ModuleSettings: ContentProvider returned null, using defaults")
            return SettingsSnapshot.DEFAULT
        }

        module.info("ModuleSettings: read from ContentProvider IPC")

        val enabledCategories = SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            bundle.getBoolean(key, true)
        }.values.toSet()

        return SettingsSnapshot(
            enabled = bundle.getBoolean(SettingsKeys.ENABLED, true),
            autoSkip = bundle.getBoolean(SettingsKeys.AUTO_SKIP, true),
            serverAddress = bundle.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER),
            enabledCategories = enabledCategories,
            showToast = bundle.getBoolean(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = bundle.getBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = bundle.getBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSubmitButton = bundle.getBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
        )
    }
}

/**
 * 配置快照(不可变)。
 */
data class SettingsSnapshot(
    val enabled: Boolean,
    val autoSkip: Boolean,
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

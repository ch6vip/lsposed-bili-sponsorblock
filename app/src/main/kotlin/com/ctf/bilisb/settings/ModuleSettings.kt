package com.ctf.bilisb.settings

import android.content.Context
import android.net.Uri
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedModule

/**
 * 模块 Hook 端的配置读取器。
 *
 * LSPosed 模块的 Hook 代码运行在目标 App(见 [HostTargets.HOST_PACKAGE])进程里,
 * 无法直接访问模块 APK 的 SharedPreferences(应用沙箱隔离)。
 *
 * 解决方案:模块 APK 声明了一个 exported=true 的 SettingsProvider(ContentProvider),
 * Hook 端通过 ContentResolver.call() 跨进程 IPC 读取设置。
 * 底层走 Binder,不需要特殊权限。
 */
object ModuleSettings {
    /**
     * 进程内缓存。命中后不再进行任何 IPC / 文件读取;设置变化由调用方显式调用 [reload] 清缓存,
     * 因此 [load] 的返回值在同一进程里是稳定的 —— 调用方可以放心长期持有。
     */
    @Volatile
    private var cached: SettingsSnapshot? = null

    /**
     * 加载设置。来源顺序:**IPC 权威,文件兜底**:
     *   1) ContentProvider IPC —— 权威存储(模块 App 的 SharedPreferences),设置改动即时生效;
     *   2) JSON 镜像文件 —— 模块 App 进程已退出 / provider 被系统拦截时的兜底副本;
     *   3) 默认值。
     *
     * 缓存语义:首次成功后写入进程内 [cached],[reload] 才会清空。
     *
     * @param module XposedModule 实例(用于日志)
     * @param hostContext 宿主 App 的 Context(用于获取 ContentResolver)
     */
    fun load(module: XposedModule, hostContext: Context): SettingsSnapshot {
        cached?.let { return it }

        // Level 1: IPC(权威)
        val fromIpc = tryIpc(module, hostContext)
        if (fromIpc != null) {
            cached = fromIpc
            module.info("ModuleSettings: read from IPC")
            return fromIpc
        }

        // Level 2: 镜像文件(兜底)
        val fromFile = tryFileFallback(module)
        if (fromFile != null) {
            cached = fromFile
            return fromFile
        }

        // Level 3: 默认值
        module.info("ModuleSettings: all sources failed, using defaults")
        val defaults = SettingsSnapshot.DEFAULT
        cached = defaults
        return defaults
    }

    /** 强制重新加载(配置变化后调用):清空进程内缓存后按 [load] 的来源顺序重新解析。 */
    fun reload(module: XposedModule, hostContext: Context): SettingsSnapshot {
        cached = null
        return load(module, hostContext)
    }

    /** Level 1: ContentProvider IPC(权威存储) */
    private fun tryIpc(module: XposedModule, hostContext: Context): SettingsSnapshot? {
        return runCatching {
            val uri = Uri.parse("content://${SettingsSyncBridge.AUTHORITY}")
            val bundle = hostContext.contentResolver.call(uri, SettingsSyncBridge.METHOD_GET_SETTINGS, null, null)
                ?: return@runCatching null

            SettingsCodec.snapshotFromBundle(bundle)
        }.getOrElse {
            module.info("ModuleSettings: IPC failed: ${it.message}")
            null
        }
    }

    /**
     * Level 2: JSON 镜像文件。
     *
     * 解析失败不再静默:文件可能存在但只有半截 JSON(或在被外部改写),
     * 这时打 warn 并继续试下一个候选,而不是"第一个能读就算数"。
     */
    private fun tryFileFallback(module: XposedModule): SettingsSnapshot? {
        // 6.5.0 目标宿主是 com.bilibili.app.in；旧包目录保留兜底
        val candidates = buildList {
            add(java.io.File("/data/data/com.ctf.bilisb/files", SettingsKeys.MIRROR_FILE))
            HostTargets.HOST_DATA_DIRS.forEach { dir ->
                add(java.io.File(dir, SettingsKeys.MIRROR_FILE))
            }
        }

        for (file in candidates) {
            if (!file.exists() || !file.canRead()) continue
            val snapshot = try {
                SettingsCodec.snapshotFromJson(org.json.JSONObject(file.readText()))
            } catch (e: Exception) {
                module.warn("ModuleSettings: parse mirror failed: ${file.absolutePath}: ${e.message}")
                null
            }
            if (snapshot != null) {
                module.info("ModuleSettings: read from file ${file.absolutePath}")
                return snapshot
            }
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

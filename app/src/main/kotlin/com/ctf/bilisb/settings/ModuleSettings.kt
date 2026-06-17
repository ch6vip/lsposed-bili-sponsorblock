package com.ctf.bilisb.settings

import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import org.json.JSONObject
import java.io.File

/**
 * 模块 Hook 端的配置读取器。
 *
 * LSPosed 模块进程运行在目标 App(tv.danmaku.bili)里,无法直接访问模块自己 APK 的
 * SharedPreferences。这里通过读取模块 APK 包目录下的 prefs XML 文件来获取设置。
 *
 * 设置 Activity 用 MODE_WORLD_READABLE 写入 prefs,文件路径:
 *   /data/data/com.ctf.bilisb/shared_prefs/sponsorblock_settings.xml
 *
 * 模块进程有权限读取该文件(world-readable)。
 *
 * 当前为简化实现:每次读取时重新解析文件。配置变化无需重启目标 App,但需要
 * 重新进入播放器才生效(因为 config 在 onPackageLoaded 时加载)。
 */
object ModuleSettings {
    private const val MODULE_PACKAGE = "com.ctf.bilisb"
    private const val PREFS_FILE = "sponsorblock_settings.xml"

    @Volatile
    private var cached: SettingsSnapshot? = null

    fun load(module: XposedModule): SettingsSnapshot {
        cached?.let { return it }

        val snapshot = runCatching {
            readFromPrefsFile(module)
        }.getOrElse {
            module.info("ModuleSettings: failed to read prefs, using defaults: ${it.message}")
            SettingsSnapshot.DEFAULT
        }
        cached = snapshot
        module.info("ModuleSettings loaded: $snapshot")
        return snapshot
    }

    /** 强制重新加载(配置变化后调用) */
    fun reload(module: XposedModule): SettingsSnapshot {
        cached = null
        return load(module)
    }

    private fun readFromPrefsFile(module: XposedModule): SettingsSnapshot {
        // 在B站进程中运行，读取B站进程自己的SharedPreferences
        // 这样和设置对话框写入的位置一致
        val targetPackage = "tv.danmaku.bili"

        val candidates = listOf(
            "/data/data/$targetPackage/shared_prefs/$PREFS_FILE",
            "/data/user/0/$targetPackage/shared_prefs/$PREFS_FILE",
            // 兜底：尝试模块自己的目录
            "/data/data/$MODULE_PACKAGE/shared_prefs/$PREFS_FILE",
            "/data/user/0/$MODULE_PACKAGE/shared_prefs/$PREFS_FILE",
        )

        val file = candidates.map { File(it) }.firstOrNull { it.exists() && it.canRead() }
        if (file == null) {
            module.info("ModuleSettings: prefs file not found in any location, using defaults")
            return SettingsSnapshot.DEFAULT
        }

        module.info("ModuleSettings: reading from ${file.absolutePath}")
        val xml = file.readText()
        return parsePrefsXml(xml)
    }

    /**
     * 解析 Android SharedPreferences XML 格式。
     * 格式: <boolean name="key" value="true" /> 和 <string name="key">value</string>
     */
    private fun parsePrefsXml(xml: String): SettingsSnapshot {
        fun bool(key: String, default: Boolean): Boolean {
            val regex = Regex("""<boolean name="$key" value="(true|false)" ?/>""")
            return regex.find(xml)?.groupValues?.get(1)?.toBoolean() ?: default
        }
        fun str(key: String, default: String): String {
            val regex = Regex("""<string name="$key">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            return regex.find(xml)?.groupValues?.get(1) ?: default
        }

        val enabledCategories = SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            bool(key, true) // 默认全开
        }.values.toSet()

        return SettingsSnapshot(
            enabled = bool(SettingsKeys.ENABLED, true),
            autoSkip = bool(SettingsKeys.AUTO_SKIP, true),
            serverAddress = str(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER),
            enabledCategories = enabledCategories,
            showToast = bool(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = bool(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = bool(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSubmitButton = bool(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
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

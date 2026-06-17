package com.ctf.bilisb.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * 设置存储。
 *
 * 关键:LSPosed 模块进程(在 tv.danmaku.bili 里)和设置 Activity(在模块 APK 里)
 * 是不同进程。写入端写 SharedPreferences + JSON 镜像文件,读取端走三级 fallback:
 *   1) ContentProvider IPC (模块进程存活时)
 *   2) JSON 镜像文件 (模块进程已退出时)
 *   3) 默认值
 */
object SettingsKeys {
    const val PREFS_NAME = "sponsorblock_settings"
    const val MIRROR_FILE = "sponsorblock_settings.json"

    // 总开关
    const val ENABLED = "enabled"
    const val AUTO_SKIP = "auto_skip"

    // 跳过策略
    // manual_skip: 开启后不自动跳过,改为在片段内显示"跳过"按钮,由用户点按跳过。
    const val MANUAL_SKIP = "manual_skip"
    // min_skip_duration: 最小片段时长(秒,可带小数)。短于此值的片段不跳过/不显示按钮。"0" = 不过滤。
    const val MIN_SKIP_DURATION = "min_skip_duration"
    // mute_segments: 对 actionType=mute 的片段静音(而非跳过)。默认关闭(对齐原 APK 未实现 mute)。
    const val MUTE_SEGMENTS = "mute_segments"
    // skip_countdown: 自动跳过倒计时(秒)。>0 时进入片段先显示"N秒后跳过 [取消]",倒计时结束才跳。"0" = 立即跳。
    const val SKIP_COUNTDOWN = "skip_countdown"

    // 服务器
    const val SERVER_ADDRESS = "server_address"
    const val DEFAULT_SERVER = "https://bsbsb.top"

    // 类别开关 (每个类别一个 bool)
    const val CAT_SPONSOR = "cat_sponsor"
    const val CAT_SELFPROMO = "cat_selfpromo"
    const val CAT_INTERACTION = "cat_interaction"
    const val CAT_INTRO = "cat_intro"
    const val CAT_OUTRO = "cat_outro"
    const val CAT_PREVIEW = "cat_preview"
    const val CAT_MUSIC_OFFTOPIC = "cat_music_offtopic"
    const val CAT_FILLER = "cat_filler"
    const val CAT_POI_HIGHLIGHT = "cat_poi_highlight"

    // UI 开关
    const val SHOW_TOAST = "show_toast"
    const val SHOW_SEEKBAR_MARKER = "show_seekbar_marker"
    const val SHOW_TIME_DEDUCTION = "show_time_deduction"
    const val SHOW_SUBMIT_BUTTON = "show_submit_button"

    // 分类标记颜色:key 为 "color_<category>",值为 "#RRGGBB" hex 字符串。
    const val COLOR_PREFIX = "color_"
    fun colorKey(category: String): String = COLOR_PREFIX + category

    /** 类别 → 默认标记颜色(hex)。与 ProgressMarkerPainter 内置配色一致。保序用于 UI 展示。 */
    val CATEGORY_COLOR_DEFAULTS: Map<String, String> = linkedMapOf(
        "sponsor" to "#00D200",         // 绿色
        "selfpromo" to "#FFFF00",       // 黄色
        "interaction" to "#AA00FF",     // 紫色
        "intro" to "#00FFFF",           // 青色
        "outro" to "#0064FF",           // 蓝色
        "preview" to "#FF8000",         // 橙色
        "music_offtopic" to "#FF00B4",  // 粉色
        "filler" to "#7F00FF",          // 深紫
        "poi_highlight" to "#FF1E1E",   // 红色
    )

    /** 所有颜色 key */
    val COLOR_KEYS: List<String> = CATEGORY_COLOR_DEFAULTS.keys.map { colorKey(it) }


    /** 类别 key → SponsorBlock category 字符串 */
    val CATEGORY_MAP = mapOf(
        CAT_SPONSOR to "sponsor",
        CAT_SELFPROMO to "selfpromo",
        CAT_INTERACTION to "interaction",
        CAT_INTRO to "intro",
        CAT_OUTRO to "outro",
        CAT_PREVIEW to "preview",
        CAT_MUSIC_OFFTOPIC to "music_offtopic",
        CAT_FILLER to "filler",
        CAT_POI_HIGHLIGHT to "poi_highlight",
    )

    /** 所有 bool 类型 key */
    val BOOL_KEYS = listOf(
        ENABLED, AUTO_SKIP, MANUAL_SKIP, MUTE_SEGMENTS,
        SHOW_TOAST, SHOW_SEEKBAR_MARKER, SHOW_TIME_DEDUCTION, SHOW_SUBMIT_BUTTON,
    ) + CATEGORY_MAP.keys.toList()

    /** 所有 string 类型 key */
    val STRING_KEYS = listOf(SERVER_ADDRESS, MIN_SKIP_DURATION, SKIP_COUNTDOWN)

    /** Bool key 默认值 */
    val BOOL_DEFAULTS = mapOf(
        ENABLED to true, AUTO_SKIP to true, MANUAL_SKIP to false, MUTE_SEGMENTS to false,
        SHOW_TOAST to true, SHOW_SEEKBAR_MARKER to true,
        SHOW_TIME_DEDUCTION to true, SHOW_SUBMIT_BUTTON to true,
    ) + CATEGORY_MAP.keys.associateWith { true }

    /** String key 默认值 */
    val STRING_DEFAULTS = mapOf(
        SERVER_ADDRESS to DEFAULT_SERVER,
        MIN_SKIP_DURATION to "0",
        SKIP_COUNTDOWN to "0",
    )
}

/**
 * 设置 Activity 端使用的写入器。
 *
 * 每次 SharedPreferences 变更时自动镜像为 JSON 文件到两个位置:
 *   - 模块自身 filesDir (ContentProvider 也读这里)
 *   - /data/data/tv.danmaku.bili/MIRROR_FILE (Hook 端直接可读)
 */
class SettingsWriter(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    private val mirrorTargets = mutableListOf<File>()

    init {
        // 镜像位置 1: 模块自身 filesDir
        mirrorTargets.add(File(context.filesDir, SettingsKeys.MIRROR_FILE))

        // 镜像位置 2: 目标 App 数据目录 (Hook 端可直接读取)
        mirrorTargets.add(File("/data/data/tv.danmaku.bili", SettingsKeys.MIRROR_FILE))
        mirrorTargets.add(File("/data/user/0/tv.danmaku.bili", SettingsKeys.MIRROR_FILE))

        // 监听变更,自动镜像
        prefs.registerOnSharedPreferenceChangeListener { _, _ -> mirrorToFile() }
        // 初始化时写一次
        mirrorToFile()
    }

    val sharedPreferences: SharedPreferences get() = prefs

    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default

    private fun mirrorToFile() {
        val json = JSONObject()
        for (key in SettingsKeys.BOOL_KEYS) {
            val def = SettingsKeys.BOOL_DEFAULTS[key] ?: true
            json.put(key, prefs.getBoolean(key, def))
        }
        for (key in SettingsKeys.STRING_KEYS) {
            val def = SettingsKeys.STRING_DEFAULTS[key] ?: ""
            json.put(key, prefs.getString(key, def) ?: def)
        }
        for ((category, def) in SettingsKeys.CATEGORY_COLOR_DEFAULTS) {
            val key = SettingsKeys.colorKey(category)
            json.put(key, prefs.getString(key, def) ?: def)
        }
        val content = json.toString(2)

        for (target in mirrorTargets) {
            try {
                target.parentFile?.mkdirs()
                target.writeText(content)
            } catch (_: Exception) {
                // 写入目标 App 目录可能因权限失败,忽略
            }
        }
    }
}

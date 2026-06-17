package com.ctf.bilisb.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * 设置存储。
 *
 * 关键:LSPosed 模块进程(在 tv.danmaku.bili 里)和设置 Activity(在模块 APK 里)
 * 是不同进程,需要用 MODE_WORLD_READABLE 共享 SharedPreferences。
 *
 * - 设置 Activity 端:用普通 Context.getSharedPreferences(MODE_WORLD_READABLE) 写入
 * - 模块 Hook 端:用 XSharedPreferences 或反射读取模块 APK 的 prefs 文件
 */
object SettingsKeys {
    const val PREFS_NAME = "sponsorblock_settings"

    // 总开关
    const val ENABLED = "enabled"
    const val AUTO_SKIP = "auto_skip"

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
}

/**
 * 设置 Activity 端使用的写入器(普通进程内 SharedPreferences)。
 */
class SettingsWriter(context: Context) {
    // 不再用 MODE_WORLD_READABLE(Android 7.0+ 已禁用)
    // 模块 Hook 端通过 root 权限读取 prefs 文件
    private val prefs: SharedPreferences =
        context.getSharedPreferences(SettingsKeys.PREFS_NAME, Context.MODE_PRIVATE)

    val sharedPreferences: SharedPreferences get() = prefs

    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default
}

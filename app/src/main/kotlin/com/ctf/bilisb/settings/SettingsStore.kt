package com.ctf.bilisb.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.net.Uri
import android.util.Log
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
    const val CACHE_TTL_MINUTES = "cache_ttl_minutes"
    const val DEFAULT_CACHE_TTL_MINUTES = "60"

    // 提交配置
    const val USER_ID = "user_id"
    const val DEFAULT_SUBMIT_CATEGORY = "default_submit_category"
    const val DEFAULT_SUBMIT_CATEGORY_VALUE = "sponsor"

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
    val STRING_KEYS = listOf(
        SERVER_ADDRESS,
        CACHE_TTL_MINUTES,
        MIN_SKIP_DURATION,
        SKIP_COUNTDOWN,
        USER_ID,
        DEFAULT_SUBMIT_CATEGORY,
    )

    /** Bool key 默认值 */
    val BOOL_DEFAULTS = mapOf(
        ENABLED to true, AUTO_SKIP to true, MANUAL_SKIP to false, MUTE_SEGMENTS to false,
        SHOW_TOAST to true, SHOW_SEEKBAR_MARKER to true,
        SHOW_TIME_DEDUCTION to true, SHOW_SUBMIT_BUTTON to true,
    ) + CATEGORY_MAP.keys.associateWith { true }

    /** String key 默认值 */
    val STRING_DEFAULTS = mapOf(
        SERVER_ADDRESS to DEFAULT_SERVER,
        CACHE_TTL_MINUTES to DEFAULT_CACHE_TTL_MINUTES,
        MIN_SKIP_DURATION to "0",
        SKIP_COUNTDOWN to "0",
        USER_ID to "",
        DEFAULT_SUBMIT_CATEGORY to DEFAULT_SUBMIT_CATEGORY_VALUE,
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
    private val appContext = context.applicationContext
    private val syncToModule = appContext.packageName == SettingsSyncBridge.MODULE_PACKAGE ||
        appContext.packageName == SettingsSyncBridge.HOST_PACKAGE

    private val mirrorTargets = mutableListOf<File>()
    @Volatile
    private var suppressSync = false

    init {
        hydrateFromCanonicalStoreIfNeeded()

        // 镜像位置 1: 模块自身 filesDir
        mirrorTargets.add(File(context.filesDir, SettingsKeys.MIRROR_FILE))

        // 镜像位置 2: 目标 App 数据目录 (Hook 端可直接读取)
        mirrorTargets.add(File("/data/data/tv.danmaku.bili", SettingsKeys.MIRROR_FILE))
        mirrorTargets.add(File("/data/user/0/tv.danmaku.bili", SettingsKeys.MIRROR_FILE))

        // 监听变更,自动镜像
        prefs.registerOnSharedPreferenceChangeListener { _, _ ->
            mirrorToFile()
            if (!suppressSync) {
                syncSnapshotToModule()
            }
        }
        // 初始化时写一次
        mirrorToFile()
    }

    val sharedPreferences: SharedPreferences get() = prefs

    fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)
    fun getString(key: String, default: String): String = prefs.getString(key, default) ?: default

    private fun hydrateFromCanonicalStoreIfNeeded() {
        if (appContext.packageName == SettingsSyncBridge.MODULE_PACKAGE) {
            return
        }
        val snapshot = SettingsSyncBridge.readSnapshot(appContext) ?: return
        suppressSync = true
        try {
            SettingsCodec.writeSnapshotToPreferences(prefs, snapshot)
        } finally {
            suppressSync = false
        }
    }

    private fun mirrorToFile() {
        val json = SettingsCodec.snapshotToJson(SettingsCodec.snapshotFromPreferences(prefs))
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

    private fun syncSnapshotToModule() {
        if (!syncToModule || appContext.packageName == SettingsSyncBridge.MODULE_PACKAGE) {
            return
        }
        val snapshot = SettingsCodec.snapshotFromPreferences(prefs)
        SettingsSyncBridge.writeSnapshot(appContext, snapshot)
    }
}

object SettingsCodec {
    fun snapshotFromPreferences(prefs: SharedPreferences): SettingsSnapshot {
        return SettingsSnapshot(
            enabled = prefs.getBoolean(SettingsKeys.ENABLED, true),
            autoSkip = prefs.getBoolean(SettingsKeys.AUTO_SKIP, true),
            manualSkip = prefs.getBoolean(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = prefs.getBoolean(SettingsKeys.MUTE_SEGMENTS, false),
            minSkipDurationSec = parseDuration(prefs.getString(SettingsKeys.MIN_SKIP_DURATION, "0")),
            skipCountdownSec = parseDuration(prefs.getString(SettingsKeys.SKIP_COUNTDOWN, "0")),
            serverAddress = prefs.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER)
                ?: SettingsKeys.DEFAULT_SERVER,
            cacheTtlMs = parseCacheTtlMs(prefs.getString(SettingsKeys.CACHE_TTL_MINUTES, SettingsKeys.DEFAULT_CACHE_TTL_MINUTES)),
            userId = prefs.getString(SettingsKeys.USER_ID, "") ?: "",
            defaultSubmitCategory = sanitizeCategory(
                prefs.getString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE),
            ),
            enabledCategories = enabledCategoriesFromPrefs(prefs::getBoolean),
            showToast = prefs.getBoolean(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = prefs.getBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = prefs.getBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSubmitButton = prefs.getBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(prefs.getString(SettingsKeys.colorKey(category), def), def)
            },
        )
    }

    fun snapshotFromBundle(bundle: Bundle): SettingsSnapshot {
        return SettingsSnapshot(
            enabled = bundle.getBoolean(SettingsKeys.ENABLED, true),
            autoSkip = bundle.getBoolean(SettingsKeys.AUTO_SKIP, true),
            manualSkip = bundle.getBoolean(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = bundle.getBoolean(SettingsKeys.MUTE_SEGMENTS, false),
            minSkipDurationSec = parseDuration(bundle.getString(SettingsKeys.MIN_SKIP_DURATION, "0")),
            skipCountdownSec = parseDuration(bundle.getString(SettingsKeys.SKIP_COUNTDOWN, "0")),
            serverAddress = bundle.getString(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER)
                ?: SettingsKeys.DEFAULT_SERVER,
            cacheTtlMs = parseCacheTtlMs(
                bundle.getString(SettingsKeys.CACHE_TTL_MINUTES, SettingsKeys.DEFAULT_CACHE_TTL_MINUTES),
            ),
            userId = bundle.getString(SettingsKeys.USER_ID, "") ?: "",
            defaultSubmitCategory = sanitizeCategory(
                bundle.getString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE),
            ),
            enabledCategories = enabledCategoriesFromPrefs { key, default -> bundle.getBoolean(key, default) },
            showToast = bundle.getBoolean(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = bundle.getBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = bundle.getBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSubmitButton = bundle.getBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(bundle.getString(SettingsKeys.colorKey(category), def), def)
            },
        )
    }

    fun snapshotFromJson(json: JSONObject): SettingsSnapshot {
        fun bool(key: String, default: Boolean) =
            if (json.has(key)) json.getBoolean(key) else default

        fun str(key: String, default: String) =
            if (json.has(key)) json.getString(key) else default

        return SettingsSnapshot(
            enabled = bool(SettingsKeys.ENABLED, true),
            autoSkip = bool(SettingsKeys.AUTO_SKIP, true),
            manualSkip = bool(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = bool(SettingsKeys.MUTE_SEGMENTS, false),
            minSkipDurationSec = parseDuration(str(SettingsKeys.MIN_SKIP_DURATION, "0")),
            skipCountdownSec = parseDuration(str(SettingsKeys.SKIP_COUNTDOWN, "0")),
            serverAddress = str(SettingsKeys.SERVER_ADDRESS, SettingsKeys.DEFAULT_SERVER),
            cacheTtlMs = parseCacheTtlMs(str(SettingsKeys.CACHE_TTL_MINUTES, SettingsKeys.DEFAULT_CACHE_TTL_MINUTES)),
            userId = str(SettingsKeys.USER_ID, ""),
            defaultSubmitCategory = sanitizeCategory(
                str(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE),
            ),
            enabledCategories = enabledCategoriesFromPrefs(::bool),
            showToast = bool(SettingsKeys.SHOW_TOAST, true),
            showSeekbarMarker = bool(SettingsKeys.SHOW_SEEKBAR_MARKER, true),
            showTimeDeduction = bool(SettingsKeys.SHOW_TIME_DEDUCTION, true),
            showSubmitButton = bool(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(str(SettingsKeys.colorKey(category), def), def)
            },
        )
    }

    fun snapshotToJson(snapshot: SettingsSnapshot): JSONObject {
        return JSONObject().apply {
            put(SettingsKeys.ENABLED, snapshot.enabled)
            put(SettingsKeys.AUTO_SKIP, snapshot.autoSkip)
            put(SettingsKeys.MANUAL_SKIP, snapshot.manualSkip)
            put(SettingsKeys.MUTE_SEGMENTS, snapshot.muteSegments)
            put(SettingsKeys.MIN_SKIP_DURATION, snapshot.minSkipDurationSec.toString())
            put(SettingsKeys.SKIP_COUNTDOWN, snapshot.skipCountdownSec.toString())
            put(SettingsKeys.SERVER_ADDRESS, snapshot.serverAddress)
            put(SettingsKeys.CACHE_TTL_MINUTES, formatMinutes(snapshot.cacheTtlMs))
            put(SettingsKeys.USER_ID, snapshot.userId)
            put(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, snapshot.defaultSubmitCategory)
            SettingsKeys.CATEGORY_MAP.keys.forEach { key ->
                put(key, snapshot.enabledCategories.contains(SettingsKeys.CATEGORY_MAP[key]))
            }
            put(SettingsKeys.SHOW_TOAST, snapshot.showToast)
            put(SettingsKeys.SHOW_SEEKBAR_MARKER, snapshot.showSeekbarMarker)
            put(SettingsKeys.SHOW_TIME_DEDUCTION, snapshot.showTimeDeduction)
            put(SettingsKeys.SHOW_SUBMIT_BUTTON, snapshot.showSubmitButton)
            SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, def) ->
                put(SettingsKeys.colorKey(category), snapshot.categoryColors[category]?.let(::toHex) ?: def)
            }
        }
    }

    fun snapshotToBundle(snapshot: SettingsSnapshot): Bundle {
        return Bundle().apply {
            putBoolean(SettingsKeys.ENABLED, snapshot.enabled)
            putBoolean(SettingsKeys.AUTO_SKIP, snapshot.autoSkip)
            putBoolean(SettingsKeys.MANUAL_SKIP, snapshot.manualSkip)
            putBoolean(SettingsKeys.MUTE_SEGMENTS, snapshot.muteSegments)
            putString(SettingsKeys.MIN_SKIP_DURATION, snapshot.minSkipDurationSec.toString())
            putString(SettingsKeys.SKIP_COUNTDOWN, snapshot.skipCountdownSec.toString())
            putString(SettingsKeys.SERVER_ADDRESS, snapshot.serverAddress)
            putString(SettingsKeys.CACHE_TTL_MINUTES, formatMinutes(snapshot.cacheTtlMs))
            putString(SettingsKeys.USER_ID, snapshot.userId)
            putString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, snapshot.defaultSubmitCategory)
            SettingsKeys.CATEGORY_MAP.keys.forEach { key ->
                putBoolean(key, snapshot.enabledCategories.contains(SettingsKeys.CATEGORY_MAP[key]))
            }
            putBoolean(SettingsKeys.SHOW_TOAST, snapshot.showToast)
            putBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, snapshot.showSeekbarMarker)
            putBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, snapshot.showTimeDeduction)
            putBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, snapshot.showSubmitButton)
            SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, _) ->
                putString(SettingsKeys.colorKey(category), snapshot.categoryColors[category]?.let(::toHex))
            }
        }
    }

    fun writeSnapshotToPreferences(prefs: SharedPreferences, snapshot: SettingsSnapshot) {
        prefs.edit().apply {
            putBoolean(SettingsKeys.ENABLED, snapshot.enabled)
            putBoolean(SettingsKeys.AUTO_SKIP, snapshot.autoSkip)
            putBoolean(SettingsKeys.MANUAL_SKIP, snapshot.manualSkip)
            putBoolean(SettingsKeys.MUTE_SEGMENTS, snapshot.muteSegments)
            putString(SettingsKeys.MIN_SKIP_DURATION, snapshot.minSkipDurationSec.toString())
            putString(SettingsKeys.SKIP_COUNTDOWN, snapshot.skipCountdownSec.toString())
            putString(SettingsKeys.SERVER_ADDRESS, snapshot.serverAddress)
            putString(SettingsKeys.CACHE_TTL_MINUTES, formatMinutes(snapshot.cacheTtlMs))
            putString(SettingsKeys.USER_ID, snapshot.userId)
            putString(SettingsKeys.DEFAULT_SUBMIT_CATEGORY, snapshot.defaultSubmitCategory)
            SettingsKeys.CATEGORY_MAP.forEach { (key, category) ->
                putBoolean(key, snapshot.enabledCategories.contains(category))
            }
            putBoolean(SettingsKeys.SHOW_TOAST, snapshot.showToast)
            putBoolean(SettingsKeys.SHOW_SEEKBAR_MARKER, snapshot.showSeekbarMarker)
            putBoolean(SettingsKeys.SHOW_TIME_DEDUCTION, snapshot.showTimeDeduction)
            putBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, snapshot.showSubmitButton)
            SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, def) ->
                putString(SettingsKeys.colorKey(category), snapshot.categoryColors[category]?.let(::toHex) ?: def)
            }
        }.apply()
    }

    fun defaultSnapshot(): SettingsSnapshot = SettingsSnapshot(
        enabled = true,
        autoSkip = true,
        manualSkip = false,
        muteSegments = false,
        minSkipDurationSec = 0f,
        skipCountdownSec = 0f,
        serverAddress = SettingsKeys.DEFAULT_SERVER,
        cacheTtlMs = 60L * 60_000L,
        userId = "",
        defaultSubmitCategory = SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE,
        enabledCategories = SettingsKeys.CATEGORY_MAP.values.toSet(),
        showToast = true,
        showSeekbarMarker = true,
        showTimeDeduction = true,
        showSubmitButton = true,
        categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (_, hex) -> parseColor(hex, "#808080") },
    )

    private fun enabledCategoriesFromPrefs(reader: (String, Boolean) -> Boolean): Set<String> {
        return SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            reader(key, true)
        }.values.toSet()
    }

    private fun parseColor(hex: String?, default: String): Int =
        parseHexColor(hex) ?: parseHexColor(default) ?: 0xFF808080.toInt()

    private fun parseDuration(raw: String?): Float =
        raw?.trim()?.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f

    private fun parseCacheTtlMs(raw: String?): Long {
        val minutes = raw?.trim()?.toFloatOrNull()?.coerceAtLeast(0f) ?: 60f
        return (minutes * 60_000L).toLong()
    }

    private fun formatMinutes(cacheTtlMs: Long): String {
        val minutes = cacheTtlMs / 60_000.0
        return if (minutes % 1.0 == 0.0) {
            minutes.toLong().toString()
        } else {
            val raw = minutes.toString()
            raw.trimEnd('0').trimEnd('.')
        }
    }

    private fun sanitizeCategory(raw: String?): String {
        val category = raw?.trim().orEmpty()
        return if (category in com.ctf.bilisb.model.SponsorCategories.displayNames) {
            category
        } else {
            SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE
        }
    }

    private fun toHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

    private fun parseHexColor(raw: String?): Int? {
        val normalized = raw?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        val value = normalized.removePrefix("#")
        return when (value.length) {
            6 -> value.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
            8 -> value.toLongOrNull(16)?.toInt()
            else -> null
        }
    }
}

object SettingsSyncBridge {
    const val MODULE_PACKAGE = "com.ctf.bilisb"
    const val HOST_PACKAGE = "tv.danmaku.bili"
    const val AUTHORITY = "com.ctf.bilisb.settings"
    const val METHOD_GET_SETTINGS = "getSettings"
    const val METHOD_PUT_SETTINGS = "putSettings"
    const val METHOD_PUT_USER_ID = "putUserId"
    private const val EXTRA_JSON = "settings_json"
    private const val EXTRA_USER_ID = "user_id"
    private const val TAG = "SettingsSyncBridge"

    fun readSnapshot(context: Context): SettingsSnapshot? {
        return runCatching {
            val bundle = context.contentResolver.call(contentUri(), METHOD_GET_SETTINGS, null, null) ?: return null
            SettingsCodec.snapshotFromBundle(bundle)
        }.getOrElse {
            Log.w(TAG, "readSnapshot failed: ${it.message}")
            null
        }
    }

    fun writeSnapshot(context: Context, snapshot: SettingsSnapshot): Boolean {
        return runCatching {
            val extras = Bundle().apply {
                putString(EXTRA_JSON, SettingsCodec.snapshotToJson(snapshot).toString())
            }
            val result = context.contentResolver.call(contentUri(), METHOD_PUT_SETTINGS, null, extras)
            result?.getBoolean("ok", false) == true
        }.getOrElse {
            Log.w(TAG, "writeSnapshot failed: ${it.message}")
            false
        }
    }

    fun writeUserId(context: Context, userId: String): Boolean {
        return runCatching {
            val extras = Bundle().apply {
                putString(EXTRA_USER_ID, userId)
            }
            val result = context.contentResolver.call(contentUri(), METHOD_PUT_USER_ID, null, extras)
            result?.getBoolean("ok", false) == true
        }.getOrElse {
            Log.w(TAG, "writeUserId failed: ${it.message}")
            false
        }
    }

    private fun contentUri(): Uri = Uri.parse("content://$AUTHORITY")
}

object SettingsProviderAccess {
    fun isAllowedCaller(
        callingPackage: String?,
        uidPackages: Array<String>?,
        selfPackage: String,
    ): Boolean {
        val allowedPackages = setOf(selfPackage, SettingsSyncBridge.HOST_PACKAGE)
        if (callingPackage != null && callingPackage !in allowedPackages) {
            return false
        }
        return uidPackages?.any { it in allowedPackages } == true
    }
}

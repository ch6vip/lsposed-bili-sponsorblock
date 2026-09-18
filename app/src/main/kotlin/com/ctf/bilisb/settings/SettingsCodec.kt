package com.ctf.bilisb.settings

import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONObject

object SettingsCodec {
    fun snapshotFromPreferences(prefs: SharedPreferences): SettingsSnapshot {
        return SettingsSnapshot(
            enabled = prefs.getBoolean(SettingsKeys.ENABLED, true),
            autoSkip = prefs.getBoolean(SettingsKeys.AUTO_SKIP, true),
            manualSkip = prefs.getBoolean(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = prefs.getBoolean(SettingsKeys.MUTE_SEGMENTS, false),
            minSkipDurationSec = parseDuration(
                prefs.getString(SettingsKeys.MIN_SKIP_DURATION, "0"),
                SettingsKeys.MAX_MIN_SKIP_DURATION_SECONDS,
            ),
            skipCountdownSec = parseDuration(
                prefs.getString(SettingsKeys.SKIP_COUNTDOWN, "0"),
                SettingsKeys.MAX_SKIP_COUNTDOWN_SECONDS,
            ),
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
            showSkipStats = prefs.getBoolean(SettingsKeys.SHOW_SKIP_STATS, true),
            showSubmitButton = prefs.getBoolean(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(prefs.getString(SettingsKeys.colorKey(category), def), def)
            },
            ipLocation = prefs.getBoolean(SettingsKeys.ENHANCE_IP_LOCATION, false),
            hideTriple = prefs.getBoolean(SettingsKeys.ENHANCE_HIDE_TRIPLE, false),
            hideUpPrompt = prefs.getBoolean(SettingsKeys.ENHANCE_HIDE_UP_PROMPT, false),
            hideVote = prefs.getBoolean(SettingsKeys.ENHANCE_HIDE_VOTE, false),
            noAutoRefresh = prefs.getBoolean(SettingsKeys.ENHANCE_NO_AUTO_REFRESH, false),
            shareQq = prefs.getBoolean(SettingsKeys.ENHANCE_SHARE_QQ, false),
        )
    }

    /**
     * 字段映射的唯一实现:Bundle / JSON / SharedPreferences 三条通道都折叠到这里。
     *
     * 为什么用 Map 中转:JVM 单测里 `android.os.Bundle` 全是 stub(没有 Robolectric),
     * 折叠之后「整对象等价」的往返测试可以在无 Android 环境下覆盖全部字段。
     * 数值统一用字符串(与 Bundle / JSON 的持久化形态一致),颜色统一 `#RRGGBB`(裁掉 alpha)。
     */
    fun snapshotToMap(snapshot: SettingsSnapshot): Map<String, Any> {
        return linkedMapOf<String, Any>().apply {
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
            SettingsKeys.CATEGORY_MAP.forEach { (key, category) ->
                put(key, snapshot.enabledCategories.contains(category))
            }
            put(SettingsKeys.SHOW_TOAST, snapshot.showToast)
            put(SettingsKeys.SHOW_SEEKBAR_MARKER, snapshot.showSeekbarMarker)
            put(SettingsKeys.SHOW_TIME_DEDUCTION, snapshot.showTimeDeduction)
            put(SettingsKeys.SHOW_SKIP_STATS, snapshot.showSkipStats)
            put(SettingsKeys.SHOW_SUBMIT_BUTTON, snapshot.showSubmitButton)
            SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, def) ->
                put(SettingsKeys.colorKey(category), snapshot.categoryColors[category]?.let(::toHex) ?: def)
            }
            SettingsKeys.ENHANCE_KEYS.forEach { key -> put(key, enhanceFlag(snapshot, key)) }
        }
    }

    /** 增强开关统一取值(映射快照字段,新增开关只改这里)。 */
    private fun enhanceFlag(snapshot: SettingsSnapshot, key: String): Boolean = when (key) {
        SettingsKeys.ENHANCE_IP_LOCATION -> snapshot.ipLocation
        SettingsKeys.ENHANCE_HIDE_TRIPLE -> snapshot.hideTriple
        SettingsKeys.ENHANCE_HIDE_UP_PROMPT -> snapshot.hideUpPrompt
        SettingsKeys.ENHANCE_HIDE_VOTE -> snapshot.hideVote
        SettingsKeys.ENHANCE_NO_AUTO_REFRESH -> snapshot.noAutoRefresh
        SettingsKeys.ENHANCE_SHARE_QQ -> snapshot.shareQq
        else -> false
    }

    /**
     * 从 Map 还原快照。
     *
     * 字段级容错(JSON 镜像可能是半截内容或被外部改写):单个字段出错只影响该字段,
     * 退回它的默认值,其余字段照常解析。布尔/字符串都要求类型严格匹配,不做隐式强转
     * (`"enabled": 1` 这类脏数据不会被当成 true 用)。
     */
    fun snapshotFromMap(values: Map<String, Any?>): SettingsSnapshot {
        fun <T> field(key: String, default: T, cast: (Any) -> T?): T =
            runCatching { values[key]?.let(cast) }.getOrNull() ?: default

        fun bool(key: String, default: Boolean) = field(key, default) { it as? Boolean }
        fun str(key: String, default: String) = field(key, default) { it as? String }

        return SettingsSnapshot(
            enabled = bool(SettingsKeys.ENABLED, true),
            autoSkip = bool(SettingsKeys.AUTO_SKIP, true),
            manualSkip = bool(SettingsKeys.MANUAL_SKIP, false),
            muteSegments = bool(SettingsKeys.MUTE_SEGMENTS, false),
            minSkipDurationSec = parseDuration(
                str(SettingsKeys.MIN_SKIP_DURATION, "0"),
                SettingsKeys.MAX_MIN_SKIP_DURATION_SECONDS,
            ),
            skipCountdownSec = parseDuration(
                str(SettingsKeys.SKIP_COUNTDOWN, "0"),
                SettingsKeys.MAX_SKIP_COUNTDOWN_SECONDS,
            ),
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
            showSkipStats = bool(SettingsKeys.SHOW_SKIP_STATS, true),
            showSubmitButton = bool(SettingsKeys.SHOW_SUBMIT_BUTTON, true),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(str(SettingsKeys.colorKey(category), def), def)
            },
            ipLocation = bool(SettingsKeys.ENHANCE_IP_LOCATION, false),
            hideTriple = bool(SettingsKeys.ENHANCE_HIDE_TRIPLE, false),
            hideUpPrompt = bool(SettingsKeys.ENHANCE_HIDE_UP_PROMPT, false),
            hideVote = bool(SettingsKeys.ENHANCE_HIDE_VOTE, false),
            noAutoRefresh = bool(SettingsKeys.ENHANCE_NO_AUTO_REFRESH, false),
            shareQq = bool(SettingsKeys.ENHANCE_SHARE_QQ, false),
        )
    }

    fun snapshotFromBundle(bundle: Bundle): SettingsSnapshot {
        val values = HashMap<String, Any?>(bundle.size())
        bundle.keySet().forEach { key -> values[key] = bundle.get(key) }
        return snapshotFromMap(values)
    }

    fun snapshotFromJson(json: JSONObject): SettingsSnapshot {
        val values = HashMap<String, Any?>(json.length())
        json.keys().forEach { key -> values[key] = json.opt(key) }
        return snapshotFromMap(values)
    }

    fun snapshotToJson(snapshot: SettingsSnapshot): JSONObject {
        return JSONObject().apply {
            snapshotToMap(snapshot).forEach { (key, value) -> put(key, value) }
        }
    }

    fun snapshotToBundle(snapshot: SettingsSnapshot): Bundle {
        return Bundle().apply {
            snapshotToMap(snapshot).forEach { (key, value) ->
                // 与旧形态保持一致:数值以字符串写入 Bundle,避免跨进程 Bundle 类型漂移
                if (value is Boolean) putBoolean(key, value) else putString(key, value.toString())
            }
        }
    }

    fun writeSnapshotToPreferences(prefs: SharedPreferences, snapshot: SettingsSnapshot) {
        prefs.edit().apply {
            snapshotToMap(snapshot).forEach { (key, value) ->
                if (value is Boolean) putBoolean(key, value) else putString(key, value.toString())
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
        showSkipStats = true,
        showSubmitButton = true,
        categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (_, hex) -> parseColor(hex, "#808080") },
        ipLocation = false,
        hideTriple = false,
        hideUpPrompt = false,
        hideVote = false,
        noAutoRefresh = false,
        shareQq = false,
    )

    private fun enabledCategoriesFromPrefs(reader: (String, Boolean) -> Boolean): Set<String> {
        return SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            reader(key, true)
        }.values.toSet()
    }

    private fun parseColor(hex: String?, default: String): Int =
        parseHexColor(hex) ?: parseHexColor(default) ?: 0xFF808080.toInt()

    /** 时长解析:非数值/NaN/无穷一律按 0,并 clamp 到 [0, maxSeconds]。 */
    private fun parseDuration(raw: String?, maxSeconds: Float): Float {
        val value = raw?.trim()?.toFloatOrNull() ?: return 0f
        if (!value.isFinite()) return 0f
        return value.coerceIn(0f, maxSeconds)
    }

    /** 缓存 TTL 解析(分钟 → 毫秒):非法值退回 60 分钟,并 clamp 到 [0, MAX_CACHE_TTL_MINUTES]。 */
    private fun parseCacheTtlMs(raw: String?): Long {
        val parsed = raw?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
        val minutes = parsed?.coerceIn(0f, SettingsKeys.MAX_CACHE_TTL_MINUTES.toFloat()) ?: 60f
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

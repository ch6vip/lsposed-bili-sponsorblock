package com.ctf.bilisb.settings

import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONObject

/**
 * 设置快照 ↔ 各存储通道的编解码。
 *
 * ## 字段映射的单一来源（R1 收敛）
 *
 * [FIELDS] 是全部**纯字段**（布尔/时长/TTL/净化字符串）的唯一声明表：
 * 键名、默认值、读取通道语义、快照取值函数都在条目上。四条通道全部由表派生：
 *
 * | 通道 | 实现 | 加字段时 |
 * | --- | --- | --- |
 * | SharedPreferences 读 | [snapshotFromPreferences]（表驱动） | 零改动 |
 * | Map（JSON/Bundle 中转）读 | [snapshotFromMap] = [assemble] | 零改动 |
 * | Map 写 | [snapshotToMap]（表驱动） | 零改动 |
 * | 默认值 | [defaultSnapshot] = 空表 assemble | 零改动 |
 *
 * 加一个设置项只剩两处：[SettingsSnapshot] 属性 + [FIELDS] 一条目；
 * 防漏网由 `SettingsCodecFieldParityTest` 的「全字段非默认值往返」用例兜底。
 *
 * **复合字段**（enabledCategories ← cat_* 九键、categoryColors ← color_* 九键）
 * 不进表：它们已是 [SettingsKeys.CATEGORY_MAP] / [SettingsKeys.CATEGORY_COLOR_DEFAULTS]
 * 单源表的派生，各通道的读取循环保持显式（读取语义不同：prefs 读 Boolean，
 * Map 读严格 String）。
 *
 * ## 通道语义（由各 FieldDef 条目实现，历史行为不变）
 *
 * - Map/JSON/Bundle：数值以字符串持久化（与既有镜像/Bundle 形态一致）；布尔严格类型
 *   匹配（`"enabled": 1` 不做强转，退回默认）；单字段容错（坏字段回默认，不抛不扩散）。
 * - SharedPreferences：类型化 getter + 各自的 sanitize/clamp。
 * - 颜色统一 `#RRGGBB`（裁掉 alpha）。
 */
object SettingsCodec {

    // ---------------------------------------------------------------- 字段定义表

    private abstract class FieldDef<T : Any>(
        val key: String,
        val get: (SettingsSnapshot) -> T,
    ) {
        abstract val defaultValue: T
        /** prefs 通道：类型化读取（带默认值与 sanitize/clamp）。 */
        abstract fun readPrefs(prefs: SharedPreferences): T
        /** Map 通道（JSON/Bundle 中转）：严格类型 + 容错回默认。 */
        abstract fun readMap(values: Map<String, Any?>): T
        /** 写通道：快照 → 持久化值。 */
        abstract fun mapValue(snapshot: SettingsSnapshot): Any
    }

    private class BoolDef(
        key: String,
        get: (SettingsSnapshot) -> Boolean,
        private val default: Boolean,
    ) : FieldDef<Boolean>(key, get) {
        override val defaultValue: Boolean get() = default
        override fun readPrefs(prefs: SharedPreferences): Boolean = prefs.getBoolean(key, default)
        override fun readMap(values: Map<String, Any?>): Boolean = (values[key] as? Boolean) ?: default
        override fun mapValue(snapshot: SettingsSnapshot): Any = get(snapshot)
    }

    /** 字符串字段：持久化前经 [sanitize]（服务器地址/用户ID/类别各有净化规则）。 */
    private class StrDef(
        key: String,
        get: (SettingsSnapshot) -> String,
        private val rawDefault: String,
        private val sanitize: (String?) -> String,
    ) : FieldDef<String>(key, get) {
        override val defaultValue: String get() = sanitize(rawDefault)
        override fun readPrefs(prefs: SharedPreferences): String = sanitize(prefs.getString(key, rawDefault))
        override fun readMap(values: Map<String, Any?>): String = sanitize((values[key] as? String) ?: rawDefault)
        override fun mapValue(snapshot: SettingsSnapshot): Any = get(snapshot)
    }

    /** 秒数字段：字符串持久化，读时 clamp 到 [0, maxSeconds]。 */
    private class DurationSecDef(
        key: String,
        get: (SettingsSnapshot) -> Float,
        private val maxSeconds: Float,
    ) : FieldDef<Float>(key, get) {
        override val defaultValue: Float get() = 0f
        override fun readPrefs(prefs: SharedPreferences): Float = parseDuration(prefs.getString(key, "0"), maxSeconds)
        override fun readMap(values: Map<String, Any?>): Float = parseDuration(values[key] as? String ?: "0", maxSeconds)
        override fun mapValue(snapshot: SettingsSnapshot): Any = get(snapshot).toString()
    }

    /** 缓存 TTL（分钟字符串 → 毫秒），非法退回 60 分钟。 */
    private class CacheTtlDef(
        key: String,
        get: (SettingsSnapshot) -> Long,
    ) : FieldDef<Long>(key, get) {
        override val defaultValue: Long get() = parseCacheTtlMs(SettingsKeys.DEFAULT_CACHE_TTL_MINUTES)
        override fun readPrefs(prefs: SharedPreferences): Long =
            parseCacheTtlMs(prefs.getString(key, SettingsKeys.DEFAULT_CACHE_TTL_MINUTES))
        override fun readMap(values: Map<String, Any?>): Long =
            parseCacheTtlMs(values[key] as? String ?: SettingsKeys.DEFAULT_CACHE_TTL_MINUTES)
        override fun mapValue(snapshot: SettingsSnapshot): Any = formatMinutes(get(snapshot))
    }

    /** 原生 Long 字段：prefs 读 Long（兼容历史 String 形态），Map/JSON 读 Number。 */
    private class LongDef(
        key: String,
        get: (SettingsSnapshot) -> Long,
    ) : FieldDef<Long>(key, get) {
        override val defaultValue: Long get() = 0L
        override fun readPrefs(prefs: SharedPreferences): Long = runCatching {
            prefs.getLong(key, 0L)
        }.recoverCatching {
            prefs.getString(key, null)?.toLongOrNull() ?: 0L
        }.getOrDefault(0L)
        override fun readMap(values: Map<String, Any?>): Long =
            (values[key] as? Number)?.toLong() ?: values[key].toString().toLongOrNull() ?: 0L
        override fun mapValue(snapshot: SettingsSnapshot): Any = get(snapshot)
    }

    // 条目即单一来源：assemble 的命名参数引用这些单例（类型安全），顺序 = Map 键序。
    private val ENABLED = BoolDef(SettingsKeys.ENABLED, { it.enabled }, true)
    private val AUTO_SKIP = BoolDef(SettingsKeys.AUTO_SKIP, { it.autoSkip }, true)
    private val MANUAL_SKIP = BoolDef(SettingsKeys.MANUAL_SKIP, { it.manualSkip }, false)
    private val MUTE_SEGMENTS = BoolDef(SettingsKeys.MUTE_SEGMENTS, { it.muteSegments }, false)
    private val MIN_SKIP_DURATION = DurationSecDef(
        SettingsKeys.MIN_SKIP_DURATION, { it.minSkipDurationSec }, SettingsKeys.MAX_MIN_SKIP_DURATION_SECONDS,
    )
    private val SKIP_COUNTDOWN = DurationSecDef(
        SettingsKeys.SKIP_COUNTDOWN, { it.skipCountdownSec }, SettingsKeys.MAX_SKIP_COUNTDOWN_SECONDS,
    )
    private val SERVER_ADDRESS = StrDef(
        SettingsKeys.SERVER_ADDRESS,
        { it.serverAddress },
        SettingsKeys.DEFAULT_SERVER,
    ) { raw -> SettingsSanitizer.sanitizeServerAddress(raw, SettingsKeys.DEFAULT_SERVER) }
    private val CACHE_TTL = CacheTtlDef(SettingsKeys.CACHE_TTL_MINUTES, { it.cacheTtlMs })
    private val USER_ID = StrDef(
        SettingsKeys.USER_ID, { it.userId }, "",
    ) { raw -> SettingsSanitizer.sanitizeUserId(raw, "") }
    private val DEFAULT_SUBMIT_CATEGORY = StrDef(
        SettingsKeys.DEFAULT_SUBMIT_CATEGORY, { it.defaultSubmitCategory },
        SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE,
    ) { raw -> sanitizeCategory(raw) }
    private val SHOW_TOAST = BoolDef(SettingsKeys.SHOW_TOAST, { it.showToast }, true)
    private val SHOW_SEEKBAR_MARKER = BoolDef(SettingsKeys.SHOW_SEEKBAR_MARKER, { it.showSeekbarMarker }, true)
    private val SHOW_TIME_DEDUCTION = BoolDef(SettingsKeys.SHOW_TIME_DEDUCTION, { it.showTimeDeduction }, true)
    private val SHOW_SKIP_STATS = BoolDef(SettingsKeys.SHOW_SKIP_STATS, { it.showSkipStats }, true)
    private val SHOW_SUBMIT_BUTTON = BoolDef(SettingsKeys.SHOW_SUBMIT_BUTTON, { it.showSubmitButton }, true)
    private val IP_LOCATION = BoolDef(SettingsKeys.ENHANCE_IP_LOCATION, { it.ipLocation }, false)
    private val HIDE_TRIPLE = BoolDef(SettingsKeys.ENHANCE_HIDE_TRIPLE, { it.hideTriple }, false)
    private val HIDE_UP_PROMPT = BoolDef(SettingsKeys.ENHANCE_HIDE_UP_PROMPT, { it.hideUpPrompt }, false)
    private val HIDE_VOTE = BoolDef(SettingsKeys.ENHANCE_HIDE_VOTE, { it.hideVote }, false)
    private val NO_AUTO_REFRESH = BoolDef(SettingsKeys.ENHANCE_NO_AUTO_REFRESH, { it.noAutoRefresh }, false)
    private val SHARE_QQ = BoolDef(SettingsKeys.ENHANCE_SHARE_QQ, { it.shareQq }, false)
    // 解锁番剧（默认关闭；服务器/令牌用户自配——见 docs/UNLOCK_PLAN.md）
    private val UNLOCK_ENABLED = BoolDef(SettingsKeys.UNLOCK_ENABLED, { it.unlockEnabled }, false)
    private val UNLOCK_SERVER_URL = StrDef(SettingsKeys.UNLOCK_SERVER_URL, { it.unlockServerUrl }, "") { raw -> raw?.trim() ?: "" }
    private val UNLOCK_AK = StrDef(SettingsKeys.UNLOCK_SERVER_ACCESS_KEY, { it.unlockServerAccessKey }, "") { raw -> raw ?: "" }
    private val UNLOCK_CACHE = BoolDef(SettingsKeys.UNLOCK_CACHE, { it.unlockCache }, false)
    private val UNLOCK_UPOS = StrDef(SettingsKeys.UNLOCK_UPOS_HOST, { it.unlockUposHost }, "") { raw -> raw?.trim() ?: "" }
    private val UNLOCK_AREA = StrDef(SettingsKeys.UNLOCK_SERVER_AREA, { it.unlockServerArea }, "") { raw -> sanitizeRoamArea(raw) }
    private val UNLOCK_TEST_EP = LongDef(SettingsKeys.UNLOCK_TEST_EPID, { it.unlockTestEpId })

    private val FIELDS: List<FieldDef<*>> = listOf(
        ENABLED, AUTO_SKIP, MANUAL_SKIP, MUTE_SEGMENTS,
        MIN_SKIP_DURATION, SKIP_COUNTDOWN, SERVER_ADDRESS, CACHE_TTL,
        USER_ID, DEFAULT_SUBMIT_CATEGORY,
        SHOW_TOAST, SHOW_SEEKBAR_MARKER, SHOW_TIME_DEDUCTION, SHOW_SKIP_STATS, SHOW_SUBMIT_BUTTON,
        IP_LOCATION, HIDE_TRIPLE, HIDE_UP_PROMPT, HIDE_VOTE, NO_AUTO_REFRESH, SHARE_QQ,
        UNLOCK_ENABLED, UNLOCK_SERVER_URL, UNLOCK_AK, UNLOCK_CACHE, UNLOCK_UPOS, UNLOCK_AREA, UNLOCK_TEST_EP,
    )

    // ---------------------------------------------------------------- 四条通道

    fun snapshotFromPreferences(prefs: SharedPreferences): SettingsSnapshot {
        val raw = HashMap<String, Any?>(FIELDS.size + 32)
        FIELDS.forEach { f -> raw[f.key] = f.readPrefs(prefs) }
        SettingsKeys.CATEGORY_MAP.keys.forEach { key -> raw[key] = prefs.getBoolean(key, true) }
        SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, def) ->
            raw[SettingsKeys.colorKey(category)] = prefs.getString(SettingsKeys.colorKey(category), def)
        }
        return assemble(raw)
    }

    /** Map 通道：JSON/Bundle 的共用中转（Bundle 是 Map ↔ Bundle 的薄适配）。 */
    fun snapshotFromMap(values: Map<String, Any?>): SettingsSnapshot = assemble(values)

    fun snapshotToMap(snapshot: SettingsSnapshot): Map<String, Any> {
        return linkedMapOf<String, Any>().apply {
            FIELDS.forEach { f -> put(f.key, f.mapValue(snapshot)) }
            SettingsKeys.CATEGORY_MAP.forEach { (key, category) ->
                put(key, snapshot.enabledCategories.contains(category))
            }
            SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, def) ->
                put(SettingsKeys.colorKey(category), snapshot.categoryColors[category]?.let(::toHex) ?: def)
            }
        }
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
                when (value) {
                    is Boolean -> putBoolean(key, value)
                    is Long -> putLong(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }.apply()
    }

    /** 默认快照 = 空表还原：每个字段各自走默认值（由 emptyJsonObjectYieldsAllDefaults 用例钉死等价）。 */
    fun defaultSnapshot(): SettingsSnapshot = snapshotFromMap(emptyMap())

    // ---------------------------------------------------------------- 组装

    /** 从原始键值组装快照（复合字段在此读取：严格类型 + 各自默认）。 */
    private fun assemble(values: Map<String, Any?>): SettingsSnapshot {
        fun <T : Any> f(def: FieldDef<T>): T = def.readMap(values)
        return SettingsSnapshot(
            enabled = f(ENABLED),
            autoSkip = f(AUTO_SKIP),
            manualSkip = f(MANUAL_SKIP),
            muteSegments = f(MUTE_SEGMENTS),
            minSkipDurationSec = f(MIN_SKIP_DURATION),
            skipCountdownSec = f(SKIP_COUNTDOWN),
            serverAddress = f(SERVER_ADDRESS),
            cacheTtlMs = f(CACHE_TTL),
            userId = f(USER_ID),
            defaultSubmitCategory = f(DEFAULT_SUBMIT_CATEGORY),
            enabledCategories = enabledFromRaw(values),
            showToast = f(SHOW_TOAST),
            showSeekbarMarker = f(SHOW_SEEKBAR_MARKER),
            showTimeDeduction = f(SHOW_TIME_DEDUCTION),
            showSkipStats = f(SHOW_SKIP_STATS),
            showSubmitButton = f(SHOW_SUBMIT_BUTTON),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(values[SettingsKeys.colorKey(category)] as? String ?: def, def)
            },
            ipLocation = f(IP_LOCATION),
            hideTriple = f(HIDE_TRIPLE),
            hideUpPrompt = f(HIDE_UP_PROMPT),
            hideVote = f(HIDE_VOTE),
            noAutoRefresh = f(NO_AUTO_REFRESH),
            shareQq = f(SHARE_QQ),
            unlockEnabled = f(UNLOCK_ENABLED),
            unlockServerUrl = f(UNLOCK_SERVER_URL),
            unlockServerAccessKey = f(UNLOCK_AK),
            unlockCache = f(UNLOCK_CACHE),
            unlockUposHost = f(UNLOCK_UPOS),
            unlockServerArea = f(UNLOCK_AREA),
            unlockTestEpId = f(UNLOCK_TEST_EP),
        )
    }

    /** 分类启用集合：cat_* 键严格布尔读取（默认 true=启用），与既有语义一致。 */
    private fun enabledFromRaw(values: Map<String, Any?>): Set<String> =
        SettingsKeys.CATEGORY_MAP.filter { (key, _) ->
            (values[key] as? Boolean) ?: true
        }.values.toSet()

    // ---------------------------------------------------------------- 解析辅助（历史语义不变）

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
        return (minutes * 60_000.0).toLong()
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

    /**
     * 回读「默认标记类别」时校验。
     *
     * 存的是 **category 字面量**（`"sponsor"`），所以必须拿字面量集合校验。
     * 旧实现拿 `displayNames`（显示名 → id）校验，`"sponsor" in {赞助/恰饭=sponsor,...}`
     * 永远为 false —— 任何已保存的合法值都会被静默改回默认值（赞助/恰饭），
     * 且用户看不到任何提示。现在按 [SponsorCategories.ids] 判定。
     */
    private fun sanitizeCategory(raw: String?): String {
        val category = raw?.trim().orEmpty()
        return if (category in com.ctf.bilisb.model.SponsorCategories.ids) {
            category
        } else {
            SettingsKeys.DEFAULT_SUBMIT_CATEGORY_VALUE
        }
    }

    /**
     * 漫游区域白名单：cn/hk/tw/th 之外的非空值一律回落 "cn"；**空串原样保留**
     * （= 未设置，语义上由消费端回落默认）——否则快照往返不再是恒等映射。
     */
    private fun sanitizeRoamArea(raw: String?): String {
        val area = raw?.trim()?.lowercase().orEmpty()
        return when {
            area.isEmpty() -> ""
            area in ROAM_AREAS -> area
            else -> "cn"
        }
    }

    private val ROAM_AREAS = setOf("cn", "hk", "tw", "th")

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

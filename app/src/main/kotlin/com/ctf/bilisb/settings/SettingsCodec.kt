package com.ctf.bilisb.settings

import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONObject
import com.ctf.bilisb.model.CategoryMode
import com.ctf.bilisb.model.SponsorCategories

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
 * **复合字段**（enabledCategories ← cat_* 十键、categoryColors ← color_* 十键、
 * categoryModes ← category_mode_* 十键）不进表：它们已是 [SettingsKeys.CATEGORY_MAP] /
 * [SettingsKeys.CATEGORY_COLOR_DEFAULTS] / [com.ctf.bilisb.model.SponsorCategories.defaultModes]
 * 单源表的派生，各通道的读取循环保持显式（读取语义不同：prefs 读 Boolean / String，
 * Map 读严格 Boolean / String；categoryModes 还多一层「键缺失 → legacy 派生」）。
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

    /**
     * `modesFromRaw` 的哨兵值：表示「该分类的 `category_mode_*` 键在存储里**存在**，
     * 但值读不出来」（类型被外部工具改坏）。
     *
     * 为什么需要它：读通道要区分三件事，而 Map 只能提供「键在 / 键不在」两态：
     *   - 键在且值合法 → 用该值；
     *   - 键在但值损坏 → 用户表达过偏好 → 回退 [SponsorCategories.defaultModes]；
     *   - 键根本不在（全新安装 / 老安装）→ [CategoryModes.legacyCategoryMode] 派生。
     * 前两种在 Map 里都是 `containsKey == true`，所以损坏时放这个哨兵进去。
     * 它是一个不可能与合法 persistedValue 相撞的字符串，[CategoryMode.fromPersisted]
     * 会把未知值判成 fallback —— 配合哨兵即可拿到「键在」的分支。
     *
     * 只活在读通道的中间 Map 里（[snapshotToMap] 不写它），不污染对外键集契约。
     */
    private const val MODE_VALUE_CORRUPTED = "__category_mode_corrupted__"

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
            when (val v = values[key]) {
                is Number -> v.toLong()
                is String -> v.toLongOrNull() ?: 0L
                else -> 0L
            }
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
    private val LANGUAGE = StrDef(SettingsKeys.LANGUAGE, { it.language }, SettingsLanguage.SYSTEM, SettingsLanguage::sanitize)
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
    private val UNLOCK_SEARCH = BoolDef(SettingsKeys.UNLOCK_SEARCH, { it.unlockSearch }, false)
    private val UNLOCK_ADD_BANGUMI = BoolDef(SettingsKeys.UNLOCK_ADD_BANGUMI, { it.unlockAddBangumi }, false)
    private val UNLOCK_UPOS = StrDef(SettingsKeys.UNLOCK_UPOS_HOST, { it.unlockUposHost }, "") { raw -> raw?.trim() ?: "" }
    private val UNLOCK_AREA = StrDef(SettingsKeys.UNLOCK_SERVER_AREA, { it.unlockServerArea }, "") { raw -> sanitizeRoamArea(raw) }
    private val UNLOCK_TEST_EP = LongDef(SettingsKeys.UNLOCK_TEST_EPID, { it.unlockTestEpId })
    private val UNLOCK_SHOW_INFO = BoolDef(SettingsKeys.UNLOCK_SHOW_INFO, { it.unlockShowInfo }, true)
    private val FULL_SCREEN_QUALITY = StrDef(SettingsKeys.FULL_SCREEN_QUALITY, { it.fullScreenQuality }, "0") { raw -> raw?.trim() ?: "0" }
    private val HALF_SCREEN_QUALITY = StrDef(SettingsKeys.HALF_SCREEN_QUALITY, { it.halfScreenQuality }, "0") { raw -> raw?.trim() ?: "0" }
    private val UNLOCK_FORCE_UPOS = BoolDef(SettingsKeys.UNLOCK_FORCE_UPOS, { it.unlockForceUpos }, false)
    private val UNLOCK_SERVER_CN = StrDef(SettingsKeys.UNLOCK_SERVER_CN, { it.unlockServerCn }, "") { raw -> raw?.trim() ?: "" }
    private val UNLOCK_SERVER_HK = StrDef(SettingsKeys.UNLOCK_SERVER_HK, { it.unlockServerHk }, "") { raw -> raw?.trim() ?: "" }
    private val UNLOCK_SERVER_TW = StrDef(SettingsKeys.UNLOCK_SERVER_TW, { it.unlockServerTw }, "") { raw -> raw?.trim() ?: "" }
    private val UNLOCK_SERVER_TH = StrDef(SettingsKeys.UNLOCK_SERVER_TH, { it.unlockServerTh }, "") { raw -> raw?.trim() ?: "" }
    private val UNLOCK_AUTO_GEN_SUB = BoolDef(SettingsKeys.UNLOCK_AUTO_GENERATE_SUBTITLE, { it.unlockAutoGenerateSubtitle }, false)
    private val UNLOCK_TH_SUB = BoolDef(SettingsKeys.UNLOCK_TH_SUBTITLE, { it.unlockThSubtitle }, true)

    private val FIELDS: List<FieldDef<*>> = listOf(
        ENABLED, AUTO_SKIP, MANUAL_SKIP, MUTE_SEGMENTS,
        MIN_SKIP_DURATION, SKIP_COUNTDOWN, SERVER_ADDRESS, CACHE_TTL,
        USER_ID, DEFAULT_SUBMIT_CATEGORY,
        SHOW_TOAST, SHOW_SEEKBAR_MARKER, SHOW_TIME_DEDUCTION, SHOW_SKIP_STATS, SHOW_SUBMIT_BUTTON,
        LANGUAGE, IP_LOCATION, HIDE_TRIPLE, HIDE_UP_PROMPT, HIDE_VOTE, NO_AUTO_REFRESH, SHARE_QQ,
        UNLOCK_ENABLED, UNLOCK_SERVER_URL, UNLOCK_AK, UNLOCK_CACHE, UNLOCK_SEARCH, UNLOCK_ADD_BANGUMI, UNLOCK_UPOS, UNLOCK_AREA, UNLOCK_TEST_EP,
        UNLOCK_SHOW_INFO, FULL_SCREEN_QUALITY, HALF_SCREEN_QUALITY, UNLOCK_FORCE_UPOS,
        UNLOCK_SERVER_CN, UNLOCK_SERVER_HK, UNLOCK_SERVER_TW, UNLOCK_SERVER_TH,
        UNLOCK_AUTO_GEN_SUB, UNLOCK_TH_SUB,
    )

    // ---------------------------------------------------------------- 四条通道

    fun snapshotFromPreferences(prefs: SharedPreferences): SettingsSnapshot {
        // 只做「搬运」：把每条通道的原始值按各自读取语义摊平进 raw，任何迁移/派生
        // 判定都不在这里 —— assemble 是唯一的解释器（见其文档）。
        val raw = HashMap<String, Any?>(FIELDS.size + 32)
        FIELDS.forEach { f -> raw[f.key] = f.readPrefs(prefs) }
        SettingsKeys.CATEGORY_MAP.keys.forEach { key -> raw[key] = prefs.getBoolean(key, true) }
        SettingsKeys.CATEGORY_COLOR_DEFAULTS.forEach { (category, def) ->
            raw[SettingsKeys.colorKey(category)] = prefs.getString(SettingsKeys.colorKey(category), def)
        }
        // 分类模式（prefs 通道）：这里**只负责把值搬进 raw**，不做任何迁移判定 ——
        // 迁移语义集中在 modesFromRaw 一处（否则 prefs 与 Map 两条通道会各有一套 legacy 派生，
        // 改一处漏一处）。键缺失时干脆不写，让 modesFromRaw 的 containsKey 自然走到
        // legacy 分支；键存在但类型被改坏时写哨兵，保留「用户表达过偏好」这一信息。
        SponsorCategories.ordered.forEach { category ->
            val modeKey = SettingsKeys.categoryModeKey(category.id)
            if (prefs.contains(modeKey)) {
                raw[modeKey] = runCatching { prefs.getString(modeKey, null) }
                    .getOrNull() ?: MODE_VALUE_CORRUPTED
            }
        }
        return assemble(raw)
    }

    /** Map 通道：JSON/Bundle 的共用中转（Bundle 是 Map ↔ Bundle 的薄适配）。 */
    fun snapshotFromMap(values: Map<String, Any?>): SettingsSnapshot = assemble(values)

    fun snapshotToMap(snapshot: SettingsSnapshot): Map<String, Any> {
        return linkedMapOf<String, Any>().apply {
            FIELDS.forEach { f -> put(f.key, f.mapValue(snapshot)) }
            // cat_* 继续写（老安装 / 外部工具链 / 降级路径都还认它），值由四态模式派生：
            // mode != DISABLED 即「启用」。两处不一致时以 category_mode_* 为准。
            SettingsKeys.CATEGORY_MAP.forEach { (key, category) ->
                val mode = snapshot.categoryModes[category]
                    ?: com.ctf.bilisb.model.SponsorCategories.defaultModes[category]
                put(key, mode != CategoryMode.DISABLED)
            }
            // 分类模式：写原始 persistedValue 字符串（Map / JSON / Bundle 三条通道统一）。
            com.ctf.bilisb.model.SponsorCategories.ordered.forEach { category ->
                val fallback = com.ctf.bilisb.model.SponsorCategories.defaultModes.getValue(category.id)
                put(
                    SettingsKeys.categoryModeKey(category.id),
                    (snapshot.categoryModes[category.id] ?: fallback).persistedValue,
                )
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
        // 四态表要先算出来：enabledCategories 是它的派生（见 enabledFromRaw），
        // 不能各自独立从原始键读一遍 —— 两者会因「cat_* 与 category_mode_* 谁更权威」而分叉。
        val modes = modesFromRaw(values)
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
            enabledCategories = enabledFromRaw(modes),
            showToast = f(SHOW_TOAST),
            showSeekbarMarker = f(SHOW_SEEKBAR_MARKER),
            showTimeDeduction = f(SHOW_TIME_DEDUCTION),
            showSkipStats = f(SHOW_SKIP_STATS),
            showSubmitButton = f(SHOW_SUBMIT_BUTTON),
            categoryColors = SettingsKeys.CATEGORY_COLOR_DEFAULTS.mapValues { (category, def) ->
                parseColor(values[SettingsKeys.colorKey(category)] as? String ?: def, def)
            },
            language = f(LANGUAGE),
            categoryModes = modes,
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
            unlockSearch = f(UNLOCK_SEARCH),
            unlockAddBangumi = f(UNLOCK_ADD_BANGUMI),
            unlockUposHost = f(UNLOCK_UPOS),
            unlockServerArea = f(UNLOCK_AREA),
            unlockTestEpId = f(UNLOCK_TEST_EP),
            unlockShowInfo = f(UNLOCK_SHOW_INFO),
            fullScreenQuality = f(FULL_SCREEN_QUALITY),
            halfScreenQuality = f(HALF_SCREEN_QUALITY),
            unlockForceUpos = f(UNLOCK_FORCE_UPOS),
            unlockServerCn = f(UNLOCK_SERVER_CN),
            unlockServerHk = f(UNLOCK_SERVER_HK),
            unlockServerTw = f(UNLOCK_SERVER_TW),
            unlockServerTh = f(UNLOCK_SERVER_TH),
            unlockAutoGenerateSubtitle = f(UNLOCK_AUTO_GEN_SUB),
            unlockThSubtitle = f(UNLOCK_TH_SUB),
        )
    }

    /**
     * 分类启用集合 = 模式 != DISABLED 的分类。
     *
     * 以前这里直接读 cat_* 布尔；四态之后 cat_* 只是**派生值**（由 categoryModes 写出来），
     * 再读一次就成了「派生值的影子」：用户在设置页显式选了 intro=auto_skip，而 cat_intro 还停在
     * 迁移前的 false，两者就会打架。因此唯一来源是 categoryModes。
     * 代价（也是唯一的取舍）：老安装的 cat_* 在**第一次读取**时仍然生效 ——
     * 因为 modesFromRaw 对「category_mode_* 缺失」的分类正是用 cat_* 派生的。
     */
    private fun enabledFromRaw(modes: Map<String, CategoryMode>): Set<String> =
        com.ctf.bilisb.model.SponsorCategories.ordered
            .filterTo(linkedSetOf()) { modes[it.id] != CategoryMode.DISABLED }
            .mapTo(linkedSetOf()) { it.id }

    /**
     * 分类四态表：**读通道唯一的迁移解释器**（prefs / Map / JSON / Bundle 全走这里）。
     *
     * 逐分类三分支，顺序即语义：
     *   1. 键在、值合法 → 直接用该值（用户的显式选择永远最优先）；
     *   2. 键在、值非法/损坏（外部改写、跨版本新增枚举、prefs 类型被改坏后落下的
     *      [MODE_VALUE_CORRUPTED]）→ 用户表达过偏好但表达坏了 → 退回
     *      [com.ctf.bilisb.model.SponsorCategories.defaultModes] 里该分类的默认模式；
     *   3. 键缺失（全新安装 / 老安装升级，从没写过 `category_mode_*`）→
     *      [CategoryModes.legacyCategoryMode] 按 `cat_*` + 两个全局开关派生，
     *      保证升级后行为与四态化之前一致。
     *
     * 第 2 与第 3 分支的区分靠 `containsKey`，因此 prefs 通道在「键在但读不出值」时
     * 必须写哨兵而不是留 null —— null 与「键不存在」在 Map 里无法区分。
     */
    private fun modesFromRaw(values: Map<String, Any?>): Map<String, CategoryMode> {
        val autoSkip = AUTO_SKIP.readMap(values)
        val manualSkip = MANUAL_SKIP.readMap(values)
        val defaults = SponsorCategories.defaultModes
        return SponsorCategories.ordered.associate { category ->
            val modeKey = SettingsKeys.categoryModeKey(category.id)
            val fallback = if (values.containsKey(modeKey)) {
                // 键在：偏好存在（哪怕值坏了），不再做 legacy 派生。
                defaults.getValue(category.id)
            } else {
                CategoryModes.legacyCategoryMode(
                    enabled = (values[category.settingsKey] as? Boolean) ?: true,
                    autoSkip = autoSkip,
                    manualSkip = manualSkip,
                )
            }
            category.id to CategoryMode.fromPersisted(values[modeKey] as? String, fallback)
        }
    }

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

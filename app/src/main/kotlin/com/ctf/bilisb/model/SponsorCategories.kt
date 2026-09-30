package com.ctf.bilisb.model

/**
 * SponsorBlock 分类的**单一事实来源**：顺序、category 字面量、中文显示名、默认标记颜色。
 *
 * 之前这三样散在四处（`SponsorCategories.displayNames`、`SettingsKeys.CATEGORY_MAP`、
 * `SettingsKeys.CATEGORY_COLOR_DEFAULTS`、`ProgressMarkerPainter.defaultCategoryColors`
 * 与 `ColorPickerDialog.PRESETS`），改一个分类名或配色要同步改 3–4 个地方，
 * 漏一处就是「进度条颜色与设置里显示的默认色不一致」这类难查的漂移。
 * 现在只剩这里一张表，其余地方全部从它派生。
 *
 * 顺序即 UI 展示顺序（[ordered] 保序）；颜色是 ARGB int，十六进制字符串只在需要
 * 「设置存储格式 / 色板文案」时用 [toHex] 临时派生。
 */
object SponsorCategories {
    /** POI（精彩时刻）片段的 actionType/category 字面量。 */
    const val POI_HIGHLIGHT = "poi_highlight"

    /** 无分类匹配时的兜底颜色（琥珀色）。 */
    const val FALLBACK_COLOR = 0xFFFFC400.toInt()

    /**
     * 一个分类的全部元数据。
     *
     * @param id SponsorBlock API 里的 category / actionType 字面量
     * @param displayName 中文显示名（UI 与日志统一使用）
     * @param defaultColor 进度条标记的默认颜色（ARGB）
     * @param settingsKey 设置里「分类开关」用的 SharedPreferences 键
     */
    data class Category(
        val id: String,
        val displayName: String,
        val defaultColor: Int,
        val settingsKey: String,
    ) {
        /** 默认颜色的 `#RRGGBB` 形式（设置存储 / 色板文案用），由 ARGB 派生，不另存字面量。 */
        val defaultColorHex: String get() = toHex(defaultColor)
    }

    /**
     * 权威分类表（保序）。
     *
     * 新增/调整分类**只改这里**：设置开关键、颜色默认值、显示名、UI 顺序全部随之更新。
     */
    val ordered: List<Category> = listOf(
        Category("sponsor", "赞助/恰饭", 0xFF00D200.toInt(), "cat_sponsor"),
        Category("selfpromo", "自我推广", 0xFFFFFF00.toInt(), "cat_selfpromo"),
        Category("interaction", "互动提醒", 0xFFAA00FF.toInt(), "cat_interaction"),
        Category("intro", "开场动画", 0xFF00FFFF.toInt(), "cat_intro"),
        Category("outro", "结束画面", 0xFF0064FF.toInt(), "cat_outro"),
        Category("preview", "回顾/概要", 0xFFFF8000.toInt(), "cat_preview"),
        Category("music_offtopic", "非音乐片段", 0xFFFF00B4.toInt(), "cat_music_offtopic"),
        Category("filler", "填充内容", 0xFF7F00FF.toInt(), "cat_filler"),
        Category(POI_HIGHLIGHT, "精彩时刻", 0xFFFF1E1E.toInt(), "cat_poi_highlight"),
    )

    /** category 字面量 → 显示名（保序，UI 直接遍历）。 */
    val displayNames: Map<String, String> = linkedMapOf<String, String>().apply {
        ordered.forEach { put(it.id, it.displayName) }
    }

    /** category 字面量 → 默认颜色（ARGB）。 */
    val defaultColors: Map<String, Int> = linkedMapOf<String, Int>().apply {
        ordered.forEach { put(it.id, it.defaultColor) }
    }

    /** category 字面量 → 默认颜色（`#RRGGBB` 大写，设置存储与色板文案用）。 */
    val defaultColorHex: Map<String, String> = defaultColors.mapValues { (_, argb) -> toHex(argb) }

    /** category 字面量 → 设置开关键。 */
    val settingsKeys: Map<String, String> = ordered.associate { it.id to it.settingsKey }

    /** 设置开关键 → category 字面量（设置存储按 key 持久化，读取时用它反转回来）。 */
    val settingsKeysInverted: Map<String, String> = ordered.associate { it.settingsKey to it.id }

    /** 显示名 → category 字面量（设置存储回读校验用）。 */
    val idsByDisplayName: Map<String, String> = ordered.associate { it.displayName to it.id }

    private val colorsByCategory: Map<String, Int> = defaultColors

    /** 只用于「是不是一个合法分类」的判断（设置存储里存的是 category 字面量，不是显示名）。 */
    val ids: Set<String> = ordered.map { it.id }.toSet()

    /** UI 显示名 → 资源 id。新增分类时**必须**同时补两处：这里与 `res/values*`。 */
    val displayNameResIds: Map<String, Int> = mapOf(
        "sponsor" to com.ctf.bilisb.R.string.category_name_sponsor,
        "selfpromo" to com.ctf.bilisb.R.string.category_name_selfpromo,
        "interaction" to com.ctf.bilisb.R.string.category_name_interaction,
        "intro" to com.ctf.bilisb.R.string.category_name_intro,
        "outro" to com.ctf.bilisb.R.string.category_name_outro,
        "preview" to com.ctf.bilisb.R.string.category_name_preview,
        "music_offtopic" to com.ctf.bilisb.R.string.category_name_music_offtopic,
        "filler" to com.ctf.bilisb.R.string.category_name_filler,
        POI_HIGHLIGHT to com.ctf.bilisb.R.string.category_name_poi_highlight,
    )

    /**
     * UI 用的显示名（按 locale 取资源）。
     *
     * 与 [displayNames] 的分工：
     *   - [displayNames] 是**规范名**（中文），给日志与「没有 Context 可用的地方」用；
     *   - 这里给界面用 —— 目标宿主是国际版，英文用户要看到英文分类名。
     *
     * **必须经 `ModuleStrings`**：调用方常常在宿主进程里（播放器面板、手动跳过按钮），
     * 那个 context 是宿主的，直接 `getString` 会拿模块 id 去查宿主资源表（真机上会显示成
     * `res/anim/...`）。取不到时回退规范名，界面不会变空。
     */
    fun displayName(context: android.content.Context, category: String): String {
        val resId = displayNameResIds[category] ?: return displayName(category)
        return com.ctf.bilisb.ui.ModuleStrings.get(context, resId, fallback = displayName(category))
    }

    fun displayName(category: String): String = displayNames[category] ?: category

    /** 默认颜色：未知分类回退 [FALLBACK_COLOR]（与进度条绘制的兜底一致）。 */
    fun defaultColor(category: String): Int = colorsByCategory[category] ?: FALLBACK_COLOR

    /** 默认颜色的十六进制形式。 */
    fun defaultColorHex(category: String): String = toHex(defaultColor(category))

    /** ARGB int → `#RRGGBB`（丢掉 alpha：设置存储与色板都只认 RGB）。 */
    fun toHex(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)
}

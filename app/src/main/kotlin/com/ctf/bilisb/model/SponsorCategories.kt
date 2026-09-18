package com.ctf.bilisb.model

/**
 * SponsorBlock 分类的中文显示名(单一事实来源)。
 *
 * 顺序即 UI 展示顺序(LinkedHashMap 保序)。controller / 设置界面 / 统计明细都从这里取名,
 * 避免各处各写一份 when 分支造成漂移。
 */
object SponsorCategories {
    /** POI(精彩时刻)片段的 actionType/category 字面量,单一事实来源。 */
    const val POI_HIGHLIGHT = "poi_highlight"

    val displayNames: Map<String, String> = linkedMapOf(
        "sponsor" to "赞助/恰饭",
        "selfpromo" to "自我推广",
        "interaction" to "互动提醒",
        "intro" to "开场动画",
        "outro" to "结束画面",
        "preview" to "回顾/概要",
        "music_offtopic" to "非音乐片段",
        "filler" to "填充内容",
        POI_HIGHLIGHT to "精彩时刻",
    )

    fun displayName(category: String): String = displayNames[category] ?: category
}

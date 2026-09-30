package com.ctf.bilisb.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类元数据「单一事实来源」的守卫测试。
 *
 * 背景：分类顺序、显示名、默认颜色、设置键以前散在四处
 * （`SponsorCategories` / `SettingsKeys.CATEGORY_MAP` / `SettingsKeys.CATEGORY_COLOR_DEFAULTS` /
 * `ProgressMarkerPainter.defaultCategoryColors` / `ColorPickerDialog.PRESETS`），
 * 改一处漏一处就会出现「进度条颜色与设置页显示的默认色不一致」这类难查的漂移。
 * 这些用例把「只剩一张表」这件事固定下来。
 */
class SponsorCategoriesTest {

    @Test
    fun `顺序稳定且为九个分类`() {
        assertEquals(
            listOf(
                "sponsor", "selfpromo", "interaction", "intro", "outro",
                "preview", "music_offtopic", "filler", SponsorCategories.POI_HIGHLIGHT,
            ),
            SponsorCategories.ordered.map { it.id },
        )
    }

    @Test
    fun `每个分类的字段都非空且 id 唯一`() {
        SponsorCategories.ordered.forEach { category ->
            assertTrue("id 不能为空", category.id.isNotBlank())
            assertTrue("显示名不能为空: ${category.id}", category.displayName.isNotBlank())
            assertTrue("设置键不能为空: ${category.id}", category.settingsKey.isNotBlank())
        }
        val ids = SponsorCategories.ordered.map { it.id }
        assertEquals("category 不允许重复", ids.size, ids.toSet().size)
        val keys = SponsorCategories.ordered.map { it.settingsKey }
        assertEquals("设置键不允许重复", keys.size, keys.toSet().size)
        val names = SponsorCategories.ordered.map { it.displayName }
        assertEquals("显示名不允许重复（SettingsCodec 用显示名反查 id）", names.size, names.toSet().size)
    }

    /** hex 必须能无损往返成 ARGB —— 设置存储写 hex、绘制读 int，两边必须同源。 */
    @Test
    fun `hex 与 ARGB 往返一致`() {
        SponsorCategories.ordered.forEach { category ->
            val hex = category.defaultColorHex
            assertEquals("hex 格式应为 #RRGGBB: $hex", 7, hex.length)
            assertEquals("#", hex.substring(0, 1))
            val parsed = (0xFF000000L or hex.removePrefix("#").toLong(16)).toInt()
            assertEquals("hex 与 ARGB 不匹配: ${category.id}", category.defaultColor, parsed)
        }
    }

    @Test
    fun `显示名与颜色的派生表与 ordered 一致`() {
        assertEquals(SponsorCategories.ordered.size, SponsorCategories.displayNames.size)
        assertEquals(SponsorCategories.ordered.size, SponsorCategories.defaultColors.size)
        assertEquals(SponsorCategories.ordered.size, SponsorCategories.defaultColorHex.size)
        assertEquals(SponsorCategories.ordered.size, SponsorCategories.settingsKeys.size)
        SponsorCategories.ordered.forEach { category ->
            assertEquals(category.displayName, SponsorCategories.displayName(category.id))
            assertEquals(category.defaultColor, SponsorCategories.defaultColor(category.id))
        }
    }

    /** 显示名反查（SettingsCodec 回读用户填的提交分类用）。 */
    @Test
    fun `显示名可反查回 category id`() {
        SponsorCategories.ordered.forEach { category ->
            assertEquals(category.id, SponsorCategories.idsByDisplayName[category.displayName])
        }
    }

    /** 未知分类：显示名回显原文、颜色回退兜底色（不能让进度条标记消失）。 */
    @Test
    fun `未知分类走兜底而不抛异常`() {
        assertEquals("brand_new_category", SponsorCategories.displayName("brand_new_category"))
        assertEquals(SponsorCategories.FALLBACK_COLOR, SponsorCategories.defaultColor("brand_new_category"))
        assertEquals("#FFC400", SponsorCategories.defaultColorHex("brand_new_category"))
    }

    /** 与设置层的默认颜色（hex 形态）必须逐项相等 —— 这两张表以前是手抄的两份。 */
    @Test
    fun `设置层默认颜色与分类元数据逐项相等`() {
        val fromSettings = com.ctf.bilisb.settings.SettingsKeys.CATEGORY_COLOR_DEFAULTS
        assertEquals(SponsorCategories.defaultColorHex, fromSettings)
    }

    /** 分类开关键映射必须与元数据一致（SettingsKeys 的 init 守卫也会查，这里给出可读的失败信息）。 */
    @Test
    fun `设置层分类映射与元数据一致`() {
        assertEquals(
            SponsorCategories.settingsKeysInverted,
            com.ctf.bilisb.settings.SettingsKeys.CATEGORY_MAP,
        )
        // 方向别写反：元数据的正向表是「category → 键」，设置层用的是它的反转。
        assertEquals(
            SponsorCategories.settingsKeys.entries.associate { (k, v) -> v to k },
            SponsorCategories.settingsKeysInverted,
        )
    }
}

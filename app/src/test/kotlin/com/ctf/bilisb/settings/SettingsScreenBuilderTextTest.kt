package com.ctf.bilisb.settings

import com.ctf.bilisb.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置页文案国际化的**防回归扫描**。
 *
 * 目标宿主是国际版，设置页曾经通篇是硬编码中文字面量（98 处）。这类回归没有任何运行时症状 ——
 * 只是英文界面里悄悄夹回一句中文 —— 所以用源码级扫描钉住：
 *  1. `SettingsScreenBuilder.kt` 里不再有「用户可见的中文字面量」（注释与调试日志除外）；
 *  2. 每一段设置页 UI 文案都确实从资源取（关键 key 必须被引用到）。
 *
 * 刻意不做成「全仓库零中文」：日志/探针/异常说明留中文是有意为之（面向维护者），
 * 把它们也钉死只会让测试变得脆弱。
 */
class SettingsScreenBuilderTextTest {

    private fun sourceLines(): List<String> =
        File("src/main/kotlin/com/ctf/bilisb/settings/SettingsScreenBuilder.kt")
            .readLines()

    /** 去掉注释行与行尾注释后再判断，避免把中文注释误判成文案。 */
    private fun codeOf(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) return ""
        return line.substringBefore("//")
    }

    @Test
    fun `设置页不再有用户可见的中文字面量`() {
        val offenders = sourceLines()
            .withIndex()
            .filter { (_, line) -> Regex("\"[^\"]*[\\u4e00-\\u9fff][^\"]*\"").containsMatchIn(codeOf(line)) }
            .map { (index, line) -> "第 ${index + 1} 行: ${line.trim()}" }

        assertEquals(
            "设置页文案必须走 res/values*（英文用户会看到中文）。发现：\n" + offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /** 关键段落必须真的从资源取（防止「扫过了但换成别的写法」的假通过）。 */
    @Test
    fun `设置页各段文案都引用了资源`() {
        val source = sourceLines().joinToString("\n")
        val required = listOf(
            R.string.app_tagline,
            R.string.entry_sponsorblock_title,
            R.string.entry_sponsorblock_summary,
            R.string.entry_enhance_title,
            R.string.entry_enhance_summary,
            R.string.common_about,
            R.string.common_version,
            R.string.common_author,
            R.string.enhance_section,
            R.string.enhance_note,
            R.string.settings_section_sponsorblock,
            R.string.enable_title,
            R.string.section_auto_skip,
            R.string.auto_skip_title,
            R.string.manual_skip_title,
            R.string.mute_segments_title,
            R.string.min_skip_duration_label,
            R.string.skip_countdown_label,
            R.string.section_categories,
            R.string.section_marker_colors,
            R.string.marker_colors_hint,
            R.string.section_ui,
            R.string.show_toast_title,
            R.string.show_marker_title,
            R.string.show_time_deduction_title,
            R.string.show_skip_stats_title,
            R.string.section_stats,
            R.string.section_submission,
            R.string.section_server,
            R.string.cache_ttl_label,
            R.string.server_address_label,
            R.string.server_address_scheme_error,
            R.string.default_category_title,
            R.string.user_id_title,
            R.string.user_id_empty_hint,
            R.string.user_id_not_generated,
            R.string.user_id_copied,
            R.string.user_id_reset_done,
            R.string.user_id_import_title,
            R.string.user_id_invalid_hex,
            R.string.stats_host_only,
            R.string.stats_summary,
            R.string.stats_empty,
            R.string.stats_category_line,
            R.string.stats_reset,
            R.string.stats_reset_done,
            R.string.state_user_generated,
            R.string.state_user_missing,
            R.string.state_user_invalid,
            R.string.state_source_local_prefs,
            R.string.state_module,
            R.string.state_settings_source,
            R.string.state_enabled_categories,
            R.string.common_back,
            R.string.common_status,
            R.string.cannot_open_link,
            R.string.saved,
            R.string.common_cancel,
            R.string.common_copy,
            R.string.common_reset,
            R.string.common_import,
            R.string.common_save,
        )
        val missing = required.filter { !source.contains("R.string.${name(it)}") }
        // R 是资源 id（int），用反射拿名字比在测试里手抄字符串可靠
        assertEquals(
            "这些设置页文案没有从资源取（漏改会静默显示默认语言的文案）",
            emptyList<String>(),
            missing,
        )
        assertTrue("设置页应统一走 str() 辅助函数", source.contains("str(activity,"))
    }

    private fun name(resId: Int): String = resourceNameById.getValue(resId)

    /** 用同一个 R 类的常量名建立 id→名字映射，避免手抄字符串与 R 漂移。 */
    private val resourceNameById: Map<Int, String> = buildMap {
        R.string::class.java.fields.forEach { field ->
            put(field.getInt(null), field.name)
        }
    }
}

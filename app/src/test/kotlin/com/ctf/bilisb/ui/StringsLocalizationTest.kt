package com.ctf.bilisb.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import com.ctf.bilisb.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 文案国际化守卫。
 *
 * 四件事必须同时成立，否则「英文用户看到什么」会静默退化：
 *  1. `values-en` 的键集合与 `values` 一致（漏一条 = 那条永远显示中文）；
 *  2. `values-en` 里不含中日韩字符（复制粘贴漏译的典型形态）；
 *  3. 格式占位符两种语言一一对应（否则 `getString` 抛 IllegalFormatException）；
 *  4. 面板在两种 locale 下**真的取到对应语言**（不只看 XML —— 还要证明资源被运行时加载了：
 *     这一条曾经直接暴露 `isIncludeAndroidResources` 关闭导致 `getString` 全崩的问题）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StringsLocalizationTest {

    private fun stringsOf(dir: String): Map<String, String> {
        val file = File("src/main/res/$dir/strings.xml")
        assertTrue("找不到资源文件: ${file.path}", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val element = nodes.item(i) as Element
            element.getAttribute("name") to element.textContent
        }
    }

    private fun contextFor(language: String, country: String): Context {
        val activity = Robolectric.setupActivity(Activity::class.java)
        val configuration = Configuration(activity.resources.configuration)
        configuration.setLocale(Locale(language, country))
        return activity.createConfigurationContext(configuration)
    }

    @Test
    fun `英文资源与默认资源的键集合一致`() {
        val zh = stringsOf("values")
        val en = stringsOf("values-en")

        assertEquals(
            "values-en 缺少这些键（英文用户会看到中文）",
            emptyList<String>(),
            (zh.keys - en.keys).sorted(),
        )
        assertEquals(
            "values-en 多出这些键（默认资源里没有对应文案）",
            emptyList<String>(),
            (en.keys - zh.keys).sorted(),
        )
    }

    @Test
    fun `英文资源不含中日韩字符`() {
        val offenders = stringsOf("values-en").filter { (_, text) ->
            text.any { it.code in 0x4E00..0x9FFF || it.code in 0x3040..0x30FF }
        }
        assertEquals("这些英文文案里还留着中文：$offenders", emptyMap<String, String>(), offenders)
    }

    @Test
    fun `格式占位符在两种语言里一一对应`() {
        val zh = stringsOf("values")
        val en = stringsOf("values-en")
        val specifier = Regex("""%(\d+)\$[sd]""")

        val mismatched = zh.keys.filter { key ->
            val zhSpecs = specifier.findAll(zh.getValue(key)).map { it.value }.toSortedSet()
            val enSpecs = specifier.findAll(en[key].orEmpty()).map { it.value }.toSortedSet()
            zhSpecs.isNotEmpty() && zhSpecs != enSpecs
        }
        assertEquals(
            "这些键的格式占位符在两种语言里不一致（运行时取文案会抛 IllegalFormatException）",
            emptyList<String>(),
            mismatched,
        )
    }

    /**
     * 运行时证明：同一段拼装逻辑（[SheetStateFormatter] + [AndroidStrings]）
     * 在中/英 locale 下取到各自语言的文案。
     */
    @Test
    fun `面板文案随 locale 切换`() {
        val zh = SheetStateFormatter.formatSegmentInfo(3, null, AndroidStrings(contextFor("zh", "CN")))
        val en = SheetStateFormatter.formatSegmentInfo(3, null, AndroidStrings(contextFor("en", "US")))

        assertTrue("中文 locale 应含中文文案，实际=$zh", zh.any { it.code in 0x4E00..0x9FFF })
        assertTrue("英文 locale 不应含中文，实际=$en", en.none { it.code in 0x4E00..0x9FFF })
        assertTrue("两种 locale 的文案不应相同（说明资源没被区分加载）", zh != en)
        assertTrue("两种语言都应带上片段数，实际 zh=$zh en=$en", zh.contains("3") && en.contains("3"))
    }

    /** 资源里的模块名（真实存在于两个 locale）能被取到 —— 证明应用资源确实进了运行时 classpath。 */
    @Test
    fun `应用资源在单测运行时可见`() {
        val context = contextFor("zh", "CN")
        assertEquals("Bili2233", context.getString(R.string.module_name))
    }
}

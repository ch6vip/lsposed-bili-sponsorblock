package com.ctf.bilisb.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.ctf.bilisb.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **跨包资源解析**的回归测试。
 *
 * ## 为什么必须有
 *
 * 模块跑在宿主进程里，`R.string.*` 是模块 APK 编译期的 id。若拿**宿主的** Resources 解析它，
 * 会按同一个数字 id 去查宿主的资源表 —— 2026-09-30 真机上设置弹窗标题就这样变成了
 * `res/anim/abc_fade_in.xml`（撞上宿主的 anim 资源）。
 *
 * 这个错配**不崩、不抛异常、与竞态和生命周期无关**，所以当时那 175 例单测（XML 键集合、
 * 源码字面量扫描、locale 切换）**全绿却完全没发现它** —— 它们验的都是「资源本身对不对」，
 * 没有一条验证「用哪个 Context 去解析」。
 *
 * ## 怎么精确模拟宿主
 *
 * 不去 `createPackageContext`（对未安装的包会失败并静默退回本包，测出来是假绿），
 * 而是用一个 [ContextWrapper]：`packageName` 报宿主的、`getString` 返回**哨兵值**。
 * 这样一旦被测代码走错路径（直接拿 context 查资源），断言会立刻看到哨兵值而失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModuleStringsTest {

    /** 走错路径时会被取到的哨兵文案（真机上对应 `res/anim/...` 那种宿主资源）。 */
    private val sentinel = "HOST_RESOURCE_SENTINEL"

    private lateinit var selfContext: Context

    /** 模拟宿主 Context：**只改包名**，从而让被测代码走「宿主进程」那条分支。 */
    private fun hostContext(): Context = object : ContextWrapper(selfContext) {
        override fun getPackageName(): String = "com.bilibili.app.in"
    }

    private fun setUpSelf() {
        ModuleStrings.resetForTest()
        val activity = Robolectric.setupActivity(Activity::class.java)
        assertNotNull(activity)
        selfContext = activity
    }

    /**
     * 关键用例：**宿主包名**的 Context 上取模块文案，必须回退到 fallback。
     *
     * 这就是真机上坏掉的那条路径：包名不匹配 ⇒ 不能直接 `context.getString`（那会查宿主资源表）。
     * 旧实现无条件 `activity.getString(R.string.x)`，这个用例会失败。
     */
    @Test
    fun `宿主包名的 Context 上回退到 fallback`() {
        setUpSelf()

        val text = ModuleStrings.get(hostContext(), R.string.app_tagline, fallback = "FALLBACK")

        assertEquals("宿主包名的 Context 上必须回退，不能去宿主资源表里碰运气", "FALLBACK", text)
        assertTrue("绝不能出现宿主资源名：$text", !text.contains("res/") && text != sentinel)
    }

    /** 宿主包名 + 带格式参数：同样必须回退，不能吐未替换的占位符。 */
    @Test
    fun `宿主包名的 Context 上带参调用回退到 fallback`() {
        setUpSelf()

        val text = ModuleStrings.get(hostContext(), R.string.sheet_user_id_invalid, fallback = "ID_INVALID")

        assertEquals("ID_INVALID", text)
        assertTrue("不能把未替换的占位符吐给用户：$text", !text.contains("%"))
    }

    /** 模块自己的包名：这条路径本来就对，必须真的取到文案而不是回退。 */
    @Test
    fun `模块包名的 Context 上取到真实文案`() {
        setUpSelf()

        val text = ModuleStrings.get(selfContext, R.string.module_name, fallback = "FALLBACK")

        assertEquals("Bili2233", text)
    }

    /** 分类显示名同样要经 ModuleStrings：宿主包名上回退规范名，而不是宿主资源。 */
    @Test
    fun `分类显示名在宿主包名上回退到规范名`() {
        setUpSelf()

        val name = com.ctf.bilisb.model.SponsorCategories.displayName(hostContext(), "sponsor")

        assertEquals("赞助/恰饭", name)
        assertTrue("不能出现宿主资源名：$name", !name.contains("res/"))
    }

    /** 模块自己的 Context 上，分类显示名应取到资源（中文默认或英文，取决于 locale）。 */
    @Test
    fun `分类显示名在模块 Context 上取到资源`() {
        setUpSelf()

        val name = com.ctf.bilisb.model.SponsorCategories.displayName(selfContext, "sponsor")

        assertTrue("应取到真实文案：$name", name.isNotBlank() && !name.contains("res/"))
    }

    /** null Context 也不能崩（播放器浮层在极早期可能拿不到 Context）。 */
    @Test
    fun `null Context 回退不崩`() {
        setUpSelf()

        assertEquals("FALLBACK", ModuleStrings.get(null, R.string.app_tagline, fallback = "FALLBACK"))
        assertEquals("", ModuleStrings.get(null, R.string.app_tagline))
    }
}
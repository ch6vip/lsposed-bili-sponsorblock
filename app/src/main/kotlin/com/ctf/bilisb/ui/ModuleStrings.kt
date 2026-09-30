package com.ctf.bilisb.ui

import android.content.Context
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import io.github.libxposed.api.XposedModule

/**
 * **模块文案的唯一入口** —— 所有读 `R.string.*` 的地方都必须走这里。
 *
 * ## 为什么不能直接用调用方的 Context
 *
 * 模块跑在**宿主进程**里，拿到的所有 `Context`（Activity / 播放器容器 / Application）都是宿主的；
 * 而 `R.string.*` 是**模块 APK 编译期**的资源 id。拿宿主的 `Resources` 解析它，会按同一个数字 id
 * 在**宿主的资源表**里查表。
 *
 * 2026-09-30 真机后果：设置弹窗标题变成 `res/anim/abc_fade_in.xml`、`res/anim/anim_bottom_in.xml`
 * （模块那几个 string id 恰好落在宿主 anim 资源的 id 段上）；而副标题是字面量所以正常。
 * **这类错配不崩、不打日志、不报错**，只是静默显示成风马牛不相及的内容。
 *
 * ## 两种进程、两条路径
 *
 * | 进程 | Context | 取值方式 |
 * | --- | --- | --- |
 * | 模块自己的 App（LauncherActivity / 设置页） | 模块的 Activity | `context.getString` 就是对的 |
 * | 宿主进程（「我的」页入口 / 播放器面板 / 浮层） | 宿主的 Activity | 必须用 [moduleResources] |
 *
 * [get] 内部按 `context.packageName` 自动选路，调用方不需要关心自己在哪个进程。
 *
 * ## 模块资源表怎么来
 *
 * libxposed 的 [XposedModule.getModuleApplicationInfo] 给出模块 APK 的 `ApplicationInfo`，
 * 用它的 `sourceDir` 建一份只读 [Resources]。这样**不依赖模块 App 是否在运行**，
 * 宿主进程里也能取到模块文案。
 *
 * ## 取不到时
 *
 * 返回调用方给的兜底文案（或空串），**绝不回退去查宿主资源表** ——
 * 宁可少一行文案，也不要显示 `res/anim/...`。
 */
object ModuleStrings {

    private const val MODULE_PACKAGE = "io.github.ch6vip.bilisb"

    /** 由 [com.ctf.bilisb.BiliSponsorBlockHooks.install] 在宿主进程注入时登记。 */
    @Volatile
    private var hookModule: XposedModule? = null

    @Volatile
    private var cachedResources: Resources? = null

    /** 只在宿主进程调用：登记当前 XposedModule，供后续取模块文案。 */
    fun attach(module: XposedModule) {
        hookModule = module
    }

    /**
     * 取文案（带格式参数）。
     *
     * @param context 调用方手上的 Context（可能是宿主的，也可能是模块自己的）
     * @param fallback 两边都取不到时的兜底文案；null 表示返回空串
     */
    fun get(context: Context?, resId: Int, vararg args: Any, fallback: String? = null): String {
        // 模块自己的进程：Context 就是模块的，资源 id 天然对得上
        if (context != null && context.packageName == MODULE_PACKAGE) {
            runCatching { context.getString(resId, *args) }.getOrNull()?.let { return it }
        }
        // 宿主进程：走模块自己的资源表
        moduleResources()?.let { res ->
            runCatching { res.getString(resId, *args) }.getOrNull()?.let { return it }
        }
        return fallback.orEmpty()
    }

    /** 取文案（无格式参数）。 */
    fun get(context: Context?, resId: Int, fallback: String? = null): String =
        get(context, resId, *emptyArray<Any>(), fallback = fallback)

    /**
     * 模块自己的资源表；不可用时返回 null。
     *
     * 结果缓存在 [cachedResources]：资源表在进程生命周期内不变，而设置弹窗会被反复构建。
     */
    fun moduleResources(): Resources? {
        cachedResources?.let { return it }
        val module = hookModule ?: return null
        val info = runCatching { module.moduleApplicationInfo }.getOrNull() ?: return null
        val res = runCatching { buildResources(info.sourceDir) }.getOrNull() ?: return null
        cachedResources = res
        return res
    }

    /**
     * 用模块 APK 的路径建一份只读 [Resources]。
     *
     * 只挂这一个 APK（模块没有 split）；不指定 Configuration 时跟随系统语言，
     * 这正是 `values-en` 生效所需要的。用**系统**的 displayMetrics/configuration 而不是宿主的，
     * 避免继承宿主的 density 覆盖导致资源选择异常。
     */
    private fun buildResources(sourceDir: String): Resources {
        val assets = AssetManager::class.java.getDeclaredConstructor().newInstance()
        val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
        val cookie = addAssetPath.invoke(assets, sourceDir) as? Int ?: 0
        require(cookie != 0) { "addAssetPath failed: $sourceDir" }
        val system = Resources.getSystem()
        @Suppress("DEPRECATION")
        return Resources(assets, system.displayMetrics, Configuration(system.configuration))
    }

    /** 仅供测试。 */
    fun resetForTest() {
        hookModule = null
        cachedResources = null
    }
}
package com.ctf.bilisb.hook

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 往宿主播放器「更多」面板（右上角「⋯」）注入一行「空降助手」。
 *
 * ## 宿主契约（6.5.0 实测反汇编，classes12.dex）
 *
 * - 面板内容是一个 RecyclerView，适配器 `com.bilibili.app.gemini.ui.f`，
 *   全量刷新入口 `f0(List)`（把传入的 List 记为 `d` 字段，`getItemCount()` = `d.size()`）。
 * - 行条目接口 `com.bilibili.app.gemini.ui.i`（**interface**）：
 *     - `a()Ljava/lang/Object;` 默认返回 `getClass()`，作为"视图类型"注册表的 key；
 *     - `b(Landroid/content/Context;Landroid/view/ViewGroup;)Lcom/bilibili/app/gemini/ui/i$b;`
 *       —— **由条目自己构建行视图**（返回的 holder 只要 `getRoot()` 给个 View）；
 *     - `e(Lcom/bilibili/app/gemini/ui/i$b;Ldy1/b;)Ljava/lang/Object;` 绑定回调。
 * - 视图类型由 `i$a#a(item)` 动态分配：`registry.putIfAbsent(item.a(), nextType++)`，
 *   所以**任意新条目类都会自动拿到一个新 type**，无需预先注册；
 *   `onCreateViewHolder(parent, viewType)` 会反查 `d` 里 `a()` 命中该 type 的条目，再调它的 `b()`。
 *
 * 结论：只要往 `f0` 的 List 里加一个实现 `i` 的条目（用 [Proxy] 实现，因为它是 interface，
 * 且 `i$b` 也是 interface），宿主就会用**我们自己的行视图**渲染它 —— 不需要新增任何依赖。
 *
 * ## 为什么用「内容判据」而不是无脑注入
 *
 * 同一个 adapter `f` 也被**详情页**复用（真机实测：详情页 47 项、播放器面板 18 项）。
 * 这里只在列表里出现 `com.bilibili.playerbizcommonv2.widget.setting.` 包下的行时才注入。
 */
object MorePanelInjector {

    /** 我们这一行的标题与说明（点击后打开自研面板）。 */
    private const val ROW_TITLE = "空降助手"
    private const val ROW_SUBTITLE = "点击打开设置面板"

    @Volatile
    private var rowItem: Any? = null

    /**
     * 安装注入。
     *
     * @param onOpenPanel 用户点击我们这一行时的回调，参数是这一行的根 View（调用方用它拿 Activity）。
     */
    fun install(
        module: XposedModule,
        classLoader: ClassLoader,
        onOpenPanel: (host: Any) -> Unit,
    ) {
        val adapterClass = HookResolve.findClass(classLoader, listOf(HostTargets.MORE_PANEL_ADAPTER_CLASS))
        if (adapterClass == null) {
            HookProbe.miss(module, "morePanelAdapter", HostTargets.MORE_PANEL_ADAPTER_CLASS)
            return
        }
        val itemInterface = HookResolve.findClass(classLoader, listOf(HostTargets.MORE_PANEL_ITEM_INTERFACE))
        val holderInterface = HookResolve.findClass(classLoader, listOf(HostTargets.MORE_PANEL_HOLDER_INTERFACE))
        if (itemInterface == null || holderInterface == null) {
            HookProbe.miss(module, "morePanelItemInterface", "item/holder interface not found")
            return
        }

        // f0(List) 是混淆名：按「名字 + 单个 List 参数」匹配
        val refresh = adapterClass.declaredMethods.firstOrNull { method ->
            method.name == HostTargets.MORE_PANEL_REFRESH_METHOD &&
                method.parameterTypes.size == 1 &&
                List::class.java.isAssignableFrom(method.parameterTypes[0])
        }
        if (refresh == null) {
            HookProbe.miss(module, "morePanelRefresh", "${adapterClass.name}#${HostTargets.MORE_PANEL_REFRESH_METHOD}(List)")
            return
        }

        module.hook(refresh)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // 在 proceed **之前**插入：f0 内部会把传入的 List 记为 d 并做刷新，
                // 插在后面就来不及出现在本次渲染里了。
                runCatching {
                    injectRowIfPlayerPanel(module, chain, itemInterface, holderInterface, onOpenPanel)
                }.onFailure { module.info("morePanel inject failed: ${it.javaClass.simpleName}: ${it.message}") }
                chain.proceed()
            }
        HookProbe.ok(module, "morePanelRefresh", "${adapterClass.name}#${refresh.name}")
    }

    private fun injectRowIfPlayerPanel(
        module: XposedModule,
        chain: XposedInterface.Chain,
        itemInterface: Class<*>,
        holderInterface: Class<*>,
        onOpenPanel: (host: Any) -> Unit,
    ) {
        @Suppress("UNCHECKED_CAST")
        val items = chain.getArgs().getOrNull(0) as? MutableList<Any?> ?: return
        HookProbe.first(module, "morePanelItems", 6) {
            buildString {
                append("size=").append(items.size)
                append(" classes=")
                var index = 0
                for (item in items) {
                    if (index >= 8) break
                    if (index > 0) append(',')
                    @Suppress("SENSELESS_COMPARISON")
                    val safe = item
                    append(if (safe == null) "null" else safe.javaClass.simpleName)
                    index++
                }
            }
        }

        if (!looksLikePlayerPanel(items)) return
        if (items.any { isOurRow(it) }) return

        val item = buildRowItem(module, itemInterface, holderInterface, onOpenPanel)
        rowItem = item
        items.add(item)
        HookProbe.ok(module, "morePanelInjected", "size=${items.size}")
    }

    /** 判据：列表里出现播放设置行（`playerbizcommonv2.widget.setting.*`）才认为是播放器「更多」面板。 */
    private fun looksLikePlayerPanel(items: List<Any?>): Boolean {
        for (item in items) {
            @Suppress("SENSELESS_COMPARISON")
            val safe = item ?: continue
            if (safe.javaClass.name.startsWith(HostTargets.MORE_PANEL_ROW_PACKAGE_PREFIX)) return true
        }
        return false
    }

    private fun isOurRow(item: Any?): Boolean {
        val ours = rowItem
        return ours != null && item === ours
    }

    /**
     * 构造我们这一行的条目（动态代理实现宿主 `i` 接口）。
     *
     * - `a()` 返回本代理类 → 宿主会为它分配一个全新的 viewType（无需注册）；
     * - `b(context, parent)` 返回 holder 代理，`getRoot()` 给我们自己画的行视图；
     * - `e(holder, continuation)` 是绑定回调：返回 `Unit`（避免宿主协程拿到 null）。
     */
    private fun buildRowItem(
        module: XposedModule,
        itemInterface: Class<*>,
        holderInterface: Class<*>,
        onOpenPanel: (host: Any) -> Unit,
    ): Any {
        val classLoader = itemInterface.classLoader
            ?: holderInterface.classLoader
            ?: ClassLoader.getSystemClassLoader()
        val unit = runCatching {
            Class.forName("kotlin.Unit", false, classLoader).getField("INSTANCE").get(null)
        }.getOrNull()

        return Proxy.newProxyInstance(
            classLoader,
            arrayOf(itemInterface),
            object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                    return try {
                        when (method.name) {
                            "b" -> {
                                val context = args?.getOrNull(0) as? Context
                                val parent = args?.getOrNull(1) as? ViewGroup
                                if (context == null || parent == null) {
                                    null
                                } else {
                                    buildHolderProxy(classLoader, holderInterface, context, parent, module, onOpenPanel)
                                }
                            }
                            // 绑定回调：宿主在 onBindViewHolder 里调用，返回 Unit 即可
                            "e" -> unit
                            // 视图类型 key：默认实现返回 getClass()，这里显式返回代理类，语义一致
                            "a" -> proxy.javaClass
                            "toString" -> "Bili2233MoreRow"
                            "hashCode" -> System.identityHashCode(proxy)
                            "equals" -> proxy === args?.firstOrNull()
                            else -> null
                        }
                    } catch (t: Throwable) {
                        module.info("morePanel row ${method.name} failed: ${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                }
            },
        )
    }

    /** holder 代理：宿主只要求 `getRoot()` 返回行视图。 */
    private fun buildHolderProxy(
        classLoader: ClassLoader,
        holderInterface: Class<*>,
        context: Context,
        parent: ViewGroup,
        module: XposedModule,
        onOpenPanel: (host: Any) -> Unit,
    ): Any {
        val row = buildRowView(context, module, onOpenPanel)
        return Proxy.newProxyInstance(
            classLoader,
            arrayOf(holderInterface),
            object : InvocationHandler {
                override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
                    return when (method.name) {
                        "getRoot" -> row
                        "toString" -> "Bili2233MoreRowHolder"
                        "hashCode" -> System.identityHashCode(proxy)
                        "equals" -> proxy === args?.firstOrNull()
                        else -> null
                    }
                }
            },
        )
    }

    /**
     * 我们这一行的视图：尽量贴近宿主行样式（图标 + 标题 + 右侧说明，52dp 高）。
     *
     * 正式版可以换成 VectorDrawable 图标；这里用 emoji 占位避免引入资源。
     */
    private fun buildRowView(
        context: Context,
        module: XposedModule,
        onOpenPanel: (host: Any) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        // 外层：顶部分隔线 + 行内容。宿主各设置行之间就是 1px 细线，
        // 我们这一行追加在末尾，所以画在**顶部**才和上面的行分隔开。
        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        wrapper.addView(
            View(context).apply {
                setBackgroundColor(Color.parseColor("#1A000000"))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            },
        )

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            )
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            // 点击涟漪：优先用主题里的 selectableItemBackground，与宿主行手感一致
            val outValue = android.util.TypedValue()
            val resolved = context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                outValue,
                true,
            )
            if (resolved && outValue.resourceId != 0) {
                runCatching { setBackgroundResource(outValue.resourceId) }
            }
        }
        wrapper.addView(row)

        val icon = TextView(context).apply {
            text = "🎯"
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(dp(28), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(icon)

        val title = TextView(context).apply {
            text = ROW_TITLE
            textSize = 15f
            setTextColor(themeTextColor(context))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(title)

        val value = TextView(context).apply {
            // 尾部的 "›" 与宿主"进入二级页"的行保持一致
            text = "$ROW_SUBTITLE ›"
            textSize = 13f
            setTextColor(Color.parseColor("#999999"))
            gravity = Gravity.END
        }
        row.addView(value)

        // 点击打开自研面板：拿行的 Context（宿主 Activity）去解包
        row.setOnClickListener { view ->
            runCatching {
                module.info("morePanel row clicked")
                onOpenPanel(view)
            }.onFailure { module.info("morePanel row click failed: ${it.message}") }
        }

        return wrapper
    }

    /** 标题色跟随主题，避免深色模式下看不见（与设置页同一套做法）。 */
    private fun themeTextColor(context: Context): Int {
        val value = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(android.R.attr.textColorPrimary, value, true)
        return if (resolved) {
            if (value.resourceId != 0) {
                // 不引入 androidx：直接用 framework 的 Context#getColor（API 23+）
                runCatching { context.getColor(value.resourceId) }.getOrElse { value.data }
            } else {
                value.data
            }
        } else {
            Color.parseColor("#212121")
        }
    }
}

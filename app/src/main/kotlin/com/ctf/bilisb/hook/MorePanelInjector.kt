package com.ctf.bilisb.hook

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.ui.TargetIconDrawable
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
    private const val ROW_SUBTITLE = "打开面板"

    /**
     * 卡片外水平边距：宿主面板的白色卡片相对面板两侧的留白。
     *
     * 真机截图量得宿主卡片左沿距屏左 48px、行高 156px、卡片间距 ≈48px，
     * 即 density=3.0 下 **16dp / 52dp / 16dp**；所以这里必须是 16dp。
     */
    private const val CARD_MARGIN_DP = 16

    /**
     * 卡片竖直边距：**保持 0**。
     *
     * 同一个截图里量到「最后一张宿主卡片底沿 → 我们这一行顶沿」的空隙是 48px，
     * 与宿主卡片之间的 48px 完全一致 —— 说明宿主给追加进来的条目**自己就套了同一档间距**。
     * 我们再补一格反而会把空隙撑成两倍（26dp 左右），一眼就看得出和上面几组不一样。
     */
    private const val CARD_GAP_DP = 0

    /** 宿主行的标题字号。同截图里宿主标题墨迹高 38px、我们的 16sp 是 41px，按比例回推到 15sp。 */
    private const val TITLE_TEXT_SP = 15f

    /** 宿主行右侧说明的字号（宿主「无字幕资源」墨迹高 33px，对应 13sp）。 */
    private const val VALUE_TEXT_SP = 13f

    /** 深色模式下的卡片底色（浅色模式用纯白）。 */
    private const val CARD_BG_NIGHT = 0xFF23262B.toInt()

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
     * 我们这一行的视图：一张独立的圆角卡片，几何和字号都按真机截图对齐宿主的设置行。
     *
     * 宿主面板里若干行同属一张圆角卡片，「空降助手」追加在列表末尾、没法并入上一张卡，
     * 所以给自己一张四角全圆的独立卡片，左右各留 [CARD_MARGIN_DP]（=16dp，与宿主卡片对齐）、
     * 上下不补（见 [CARD_GAP_DP]）。行本身 52dp 高、左右 16dp 内边距，
     * 内容顺序与宿主一致：20dp 图标 → 8dp 间距 → 标题（占满剩余）→ 右侧说明 + 「›」。
     *
     * 涟漪画在卡片外层并 [View.setClipToOutline] 裁到圆角，避免方角涟漪溢出圆角卡片。
     * 图标用 [TargetIconDrawable] 现画（不走资源/矢量 XML，原因见那个类），
     * 颜色跟标题同色；标题色仍走主题（[themeTextColor]），深色模式不会看不见。
     */
    private fun buildRowView(
        context: Context,
        module: XposedModule,
        onOpenPanel: (host: Any) -> Unit,
    ): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val night = (context.resources.configuration.uiMode
            and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val textColor = themeTextColor(context)

        val wrapper = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(if (night) CARD_BG_NIGHT else Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                setMargins(dp(CARD_MARGIN_DP), dp(CARD_GAP_DP), dp(CARD_MARGIN_DP), dp(CARD_GAP_DP))
            }
            // 让涟漪跟着圆角卡片被裁切（背景是 GradientDrawable，outline 自带圆角）
            clipToOutline = true
            isClickable = true
            isFocusable = true
            // 点击涟漪：优先用主题里的 selectableItemBackground，与宿主行手感一致。
            // 用 foreground 而不是 background —— background 已经被白色卡片占用了。
            val outValue = TypedValue()
            val resolved = context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground,
                outValue,
                true,
            )
            if (resolved && outValue.resourceId != 0) {
                runCatching { foreground = context.getDrawable(outValue.resourceId) }
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(52),
            )
            setPadding(dp(16), 0, dp(16), 0)
        }
        wrapper.addView(row)

        row.addView(
            ImageView(context).apply {
                setImageDrawable(TargetIconDrawable(textColor))
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
            },
        )

        val title = TextView(context).apply {
            text = ROW_TITLE
            textSize = TITLE_TEXT_SP
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(8)
            }
        }
        row.addView(title)

        row.addView(
            TextView(context).apply {
                // 尾部的 "›" 与宿主「进入二级页」的行保持一致
                text = "$ROW_SUBTITLE ›"
                textSize = VALUE_TEXT_SP
                setTextColor(Color.parseColor("#999999"))
                gravity = Gravity.END
            },
        )

        // 点击打开自研面板：拿卡片的 Context（宿主 Activity）去解包
        wrapper.setOnClickListener { view ->
            runCatching {
                module.info("morePanel row clicked")
                onOpenPanel(view)
            }.onFailure { module.info("morePanel row click failed: ${it.message}") }
        }

        return wrapper
    }

    /** 标题色跟随主题，避免深色模式下看不见（与设置页同一套做法）。 */
    private fun themeTextColor(context: Context): Int {
        val value = TypedValue()
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

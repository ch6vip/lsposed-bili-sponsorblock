package com.ctf.bilisb.hook

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.player.PlayerBridge
import com.ctf.bilisb.settings.SponsorBlockSettingDialog
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule

/**
 * 在B站"我的"页面菜单注入设置入口。
 *
 * 6.5.0（`com.bilibili.app.in`）实测：
 *   - `MenuGroup` / `MenuGroup$Item` 类名与字段完整保留（`id/title/uri/icon/needLogin/redDot/localShow`），
 *     所以注入构造逻辑可以复用；
 *   - 「我的」页 adapter 的外层类被混淆成 `tv.danmaku.bili.ui.main2.mine.d`（Fragment 名保留），
 *     类名走 [HostTargets.MINE_ADAPTER_CLASSES] 候选；
 *   - adapter 本身不覆写 `notifyDataSetChanged`，现有实现命中的是 `RecyclerView.Adapter` 的基类方法，
 *     所以对所有 RecyclerView 生效、靠 [findListFieldByContent] 的字段内容过滤兜底。
 */
object MineMenuInjector {

    private const val SETTING_ID = 0x5B5B5B5BL
    private const val SETTING_URI = "bilisb://settings"
    private const val SETTING_TITLE = "Bili2233"
    private val ROUTER_METHOD_NAMES = setOf("open", "handle", "route", "navigate", "dispatch")
    // 宿主“我的”页按钮图标链路实际接受远程图片 URL。
    private const val SETTING_ICON = "https://i0.hdslb.com/bfs/album/276769577d2a5db1d9f914364abad7c5253086f6.png"

    /** 内容精确绑定是否成功过:成功后其它行的 bind 不再走轮询兜底(入口已可用)。 */
    @Volatile
    private var directBindSucceeded: Boolean = false

    /** 已绑过点击的格子(弱引用),避免 ViewHolder 复用重复 setOnClickListener。 */
    private val boundCells: MutableMap<View, Boolean> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, Boolean>())

    private fun attachClickListener(module: XposedModule, holder: Any, position: Int, adapter: Any) {
        val data = findListFieldByContent(adapter, HostTargets.MENU_GROUP_CLASS) ?: return
        if (data.isEmpty()) return

        val itemView = holderItemView(holder) ?: return
        if (tryBindOurRow(module, itemView, position)) {
            directBindSucceeded = true
            return
        }
        boundCells.remove(itemView)
        // 组 holder 绑定时内部 RecyclerView 往往还没 layout,延后扫子项标题。
        val delays = longArrayOf(0L, 120L, 360L, 800L)
        delays.forEach { delay ->
            itemView.postDelayed({
                if (tryBindOurRow(module, itemView, position)) {
                    directBindSucceeded = true
                }
            }, delay)
        }
    }

    private fun tryBindOurRow(module: XposedModule, root: View, position: Int): Boolean {
        if (findTitleView(root) != null) {
            bindSelfClick(module, root)
            HookProbe.first(module, "mineMenuDirectBind", 5) { "position=$position root=${root.javaClass.simpleName}" }
            return true
        }
        collectRecyclerViews(root).forEach { rv ->
            if (rv !is ViewGroup) return@forEach
            for (i in 0 until rv.childCount) {
                val child = rv.getChildAt(i)
                if (findTitleView(child) != null) {
                    bindSelfClick(module, child)
                    HookProbe.first(module, "mineMenuDirectBind", 5) { "position=$position innerChild=$i" }
                    return true
                }
            }
        }
        HookProbe.first(module, "mineMenuTexts", 3) { "pos=$position texts=${dumpTexts(root)}" }
        return false
    }

    private fun collectRecyclerViews(view: View, out: MutableList<View> = mutableListOf()): List<View> {
        if (isRecyclerView(view)) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectRecyclerViews(view.getChildAt(i), out)
        }
        return out
    }

    private fun dumpTexts(view: View, acc: MutableList<String> = mutableListOf()): String {
        if (view is TextView) {
            val t = view.text?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) acc.add(t.take(24))
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) dumpTexts(view.getChildAt(i), acc)
        }
        return acc.take(12).joinToString("|")
    }

    private fun holderItemView(holder: Any): View? {
        return try {
            val field = holder.javaClass.getDeclaredField("itemView").apply { isAccessible = true }
            field.get(holder) as? View
        } catch (e: Throwable) {
            try {
                holder.javaClass.getField("itemView").get(holder) as? View
            } catch (e2: Throwable) {
                null
            }
        }
    }

    private fun isOurTitle(text: CharSequence?): Boolean {
        val t = text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return false
        return t.equals(SETTING_TITLE, ignoreCase = true) || t.contains("Bili2233", ignoreCase = true)
    }

    private fun findTitleView(view: View): TextView? {
        if (view is TextView && isOurTitle(view.text)) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findTitleView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun isRecyclerView(view: View): Boolean =
        view.javaClass.name.contains("RecyclerView")

    private fun cellOfTitle(title: View): View {
        var current: View = title
        var parent = title.parent as? ViewGroup
        while (parent != null && !isRecyclerView(parent)) {
            current = parent
            parent = parent.parent as? ViewGroup
        }
        return current
    }

    private fun showSettings(module: XposedModule, view: View) {
        val activity = PlayerBridge.activity(view)
        if (activity != null) {
            SponsorBlockSettingDialog.show(activity)
            module.info("Showing Bili2233 settings dialog (direct bind)")
        } else {
            module.warn("Context is not Activity: ${view.context.javaClass.name}")
        }
    }

    private fun bindTree(view: View, listener: View.OnClickListener) {
        view.isClickable = true
        view.setOnClickListener(listener)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) bindTree(view.getChildAt(i), listener)
        }
    }

    private fun bindSelfClick(module: XposedModule, root: View) {
        val title = findTitleView(root) ?: return
        val cell = cellOfTitle(title)
        if (boundCells.putIfAbsent(cell, true) != null) return
        val listener = View.OnClickListener { showSettings(module, it) }
        bindTree(cell, listener)
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        try {
            val menuGroupClass = HookResolve.findClass(classLoader, listOf(HostTargets.MENU_GROUP_CLASS))
            val menuItemClass = HookResolve.findClass(classLoader, listOf(HostTargets.MENU_ITEM_CLASS))

            if (menuGroupClass == null || menuItemClass == null) {
                HookProbe.miss(module, "mineMenuModel", "MenuGroup/Item not found")
                module.warn("MenuGroup or Item class not found, skip mine menu injection")
                return
            }

            hookMineAdapter(module, classLoader, menuItemClass)

            // Hook URI路由器拦截点击
            hookUriRouter(module, classLoader)

            module.info("MineMenuInjector installed")
        } catch (e: Throwable) {
            HookProbe.miss(module, "mineMenuInstall", "${e.javaClass.name}: ${e.message}")
            module.warn("Failed to install MineMenuInjector: ${e.javaClass.name}: ${e.message}\n${android.util.Log.getStackTraceString(e)}")
        }
    }

    private fun findClass(classLoader: ClassLoader, name: String): Class<*>? {
        return HookResolve.findClass(classLoader, listOf(name))
    }

    private fun hookMineAdapter(module: XposedModule, classLoader: ClassLoader, menuItemClass: Class<*>) {
        // 6.5.0 的 adapter 外层类被混淆成 tv.danmaku.bili.ui.main2.mine.d
        val adapterClass = HookResolve.findClass(classLoader, HostTargets.MINE_ADAPTER_CLASSES)
        if (adapterClass == null) {
            HookProbe.miss(module, "mineAdapter", HostTargets.MINE_ADAPTER_CLASSES.joinToString())
            module.warn("mine adapter not found")
            return
        }

        // notifyDataSetChanged 由 RecyclerView.Adapter 基类声明（子类不覆写），getMethod 能取到
        val notifyMethod = try {
            adapterClass.getMethod("notifyDataSetChanged")
        } catch (e: Throwable) {
            HookProbe.miss(module, "mineAdapterNotify", "${adapterClass.name}: ${e.message}")
            null
        }

        if (notifyMethod != null) {
            module.hook(notifyMethod)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    try {
                        val adapter = chain.getThisObject()
                        injectSettingItemIfMineAdapter(module, adapter, menuItemClass)
                    } catch (e: Throwable) {
                        // 本仓库方针:异常必须留日志,裸吞导致过无法排查的现场
                        module.warn("injectSettingItem failed: ${e.javaClass.name}: ${e.message}")
                    }
                    chain.proceed()
                }
            HookProbe.ok(module, "mineAdapterNotify", "${adapterClass.name} (via ${notifyMethod.declaringClass.name})")
            module.info("Hooked notifyDataSetChanged for mine adapter ${adapterClass.name}")
        }

        // Hook adapter 的 onBindViewHolder 添加点击监听
        hookAdapterClickListener(module, adapterClass)
    }

    private fun hookAdapterClickListener(module: XposedModule, adapterClass: Class<*>) {
        // 不按名字加载 `androidx.recyclerview.widget.RecyclerView$ViewHolder`
        // （真机上 Class.forName 取不到，导致点击绑定 MISS），
        // 直接按「方法名 + 2 个参数 + 第二参数是 int」定位，第一参数类型就是 ViewHolder。
        val onBindMethod = adapterClass.declaredMethods.firstOrNull { method ->
            method.name == "onBindViewHolder" &&
                method.parameterTypes.size == 2 &&
                method.parameterTypes[1] == Int::class.javaPrimitiveType
        }?.apply { isAccessible = true } ?: run {
            HookProbe.miss(module, "mineAdapterBind", "${adapterClass.name}#onBindViewHolder not found")
            module.warn("onBindViewHolder not found")
            return
        }

        module.hook(onBindMethod)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()

                try {
                    val holder = chain.args[0]
                    val position = chain.args[1] as Int
                    attachClickListener(module, holder, position, chain.getThisObject())
                } catch (e: Throwable) {
                    module.warn("Failed to attach click listener: ${e.message}")
                }

                result
            }

        module.info("Hooked ${adapterClass.name}.onBindViewHolder")
        HookProbe.ok(module, "mineAdapterBind", "${adapterClass.name}#onBindViewHolder")
    }

    private fun injectSettingItemIfMineAdapter(module: XposedModule, adapter: Any, menuItemClass: Class<*>) {
        // 直接按内容定位 List<MenuGroup> 字段，避免“第一个 List 字段”假设在改版后选错。
        val data = findListFieldByContent(adapter, HostTargets.MENU_GROUP_CLASS) ?: return
        if (data.isEmpty()) return

        // 记录adapter类名用于后续hook点击
        HookProbe.first(module, "mineAdapterFound", 3) { adapter.javaClass.name }

        // 注入设置项(injectSettingItem 内部按 uri 幂等,已存在直接返回)。
        //
        // 注意:这里**不能**按「adapter + 列表实例(+尺寸)」缓存注入状态 ——
        // 宿主会异步刷新「我的」页远程配置,把同一个列表实例 clear+重填(尺寸可能都不变),
        // 我们注入的条目会被冲掉;缓存版会导致入口从此消失(2026-09-19 真机回归复现)。
        // 每次 notify 都走一遍存在性检查,是已知最低成本的正确实现。
        injectSettingItem(module, data, menuItemClass)
    }

    /**
     * 在 adapter 的所有 List 字段里，挑出元素类型为 [expectedClassName] 的那一个。
     *
     * 6.5.0 真机结论：**不再使用「第一个非空 List」兜底**。
     * 该兜底会在无关 adapter（实测 `LF1.b`）上也注入出一份菜单项，导致重复/错位；
     * 而真正的「我的」页 adapter（`tv.danmaku.bili.ui.main2.mine.d`）本身就能按内容命中，
     * 所以这里只认内容匹配，匹配不到就放弃（探针记录适配器类名，便于宿主改版后补候选）。
     */
    private fun findListFieldByContent(adapter: Any, expectedClassName: String): MutableList<Any>? {
        for (field in adapter.javaClass.declaredFields) {
            if (!List::class.java.isAssignableFrom(field.type)) continue
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val list = field.get(adapter) as? MutableList<Any> ?: continue
            val first = list.firstOrNull() ?: continue
            if (first.javaClass.name == expectedClassName) return list
        }
        return null
    }

    private fun injectSettingItem(module: XposedModule, data: MutableList<Any>, menuItemClass: Class<*>) {
        // 检查是否已存在设置项
        for (group in data) {
            val itemListField = try {
                group.javaClass.getDeclaredField("itemList").apply { isAccessible = true }
            } catch (e: Throwable) {
                continue
            }

            @Suppress("UNCHECKED_CAST")
            val itemList = itemListField.get(group) as? MutableList<Any> ?: continue

            for (item in itemList) {
                val uriField = try {
                    item.javaClass.getDeclaredField("uri").apply { isAccessible = true }
                } catch (e: Throwable) {
                    continue
                }

                if (uriField.get(item) == SETTING_URI) {
                    return // 已存在
                }
            }
        }

        // 在最后一个group插入设置项
        val lastGroup = data.lastOrNull() ?: return
        val itemListField = try {
            lastGroup.javaClass.getDeclaredField("itemList").apply { isAccessible = true }
        } catch (e: Throwable) {
            return
        }

        @Suppress("UNCHECKED_CAST")
        val itemList = itemListField.get(lastGroup) as? MutableList<Any> ?: return

        val settingItem = createSettingItem(module, menuItemClass) ?: return

        // 查找"设置"项的位置，插入在它前面
        var insertIndex = itemList.size
        for (i in itemList.indices) {
            val item = itemList[i]
            val titleField = try {
                item.javaClass.getDeclaredField("title").apply { isAccessible = true }
            } catch (e: Throwable) {
                continue
            }

            val title = titleField.get(item) as? String
            if (title == "设置" || title?.contains("设置") == true) {
                insertIndex = i
                break
            }
        }

        itemList.add(insertIndex, settingItem)
        module.info("Injected Bili2233 setting item at position $insertIndex")
    }

    private fun createSettingItem(module: XposedModule, menuItemClass: Class<*>): Any? {
        return try {
            val item = menuItemClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()

            // 设置字段
            setField(item, "id", SETTING_ID)
            setField(item, "title", SETTING_TITLE)
            setField(item, "uri", SETTING_URI)
            setField(item, "icon", SETTING_ICON)
            setField(item, "needLogin", 0)
            setField(item, "redDot", 0)
            setField(item, "localShow", true)

            item
        } catch (e: Throwable) {
            module.warn("Failed to create setting item: ${e.message}")
            null
        }
    }

    private fun setField(obj: Any, fieldName: String, value: Any?) {
        try {
            val field = obj.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            // 按字段实际类型转换:宿主 id 字段多为 int 而我们的 SETTING_ID 是 Long,
            // 直接 set 会因类型不匹配失败,构造出全默认值的菜单项且查不出原因
            when {
                field.type == Int::class.javaPrimitiveType && value is Long -> field.setInt(obj, value.toInt())
                field.type == Int::class.javaPrimitiveType && value is Int -> field.setInt(obj, value)
                field.type == Long::class.javaPrimitiveType && value is Int -> field.setLong(obj, value.toLong())
                field.type == Boolean::class.javaPrimitiveType && value is Boolean -> field.setBoolean(obj, value)
                else -> field.set(obj, value)
            }
        } catch (e: Throwable) {
            // 字段不存在或类型仍不匹配:留日志便于宿主改版后排查
            android.util.Log.w("MineMenuInjector", "setField $fieldName failed: ${e.javaClass.name}: ${e.message}")
        }
    }
    private fun extractRoutedUri(args: List<Any?>?): String {
        if (args.isNullOrEmpty()) return ""
        for (arg in args) {
            when (arg) {
                is Uri -> return arg.toString()
                is String -> if (arg.contains("bilisb://")) return arg
                is Intent -> {
                    val data = arg.dataString
                    if (!data.isNullOrEmpty()) return data
                }
            }
        }
        return args.firstOrNull()?.toString().orEmpty()
    }

    private fun hookUriRouter(module: XposedModule, classLoader: ClassLoader) {
        // Hook B站的URI路由器，拦截 bilisb://settings
        // 6.5.0 实测：blrouter 框架还在，但 Router / BLRouter 类名不存在（被混淆），
        // IntentHandlerActivity 仍存在。这里按候选尝试，命中情况由探针记录（见 ROADMAP M8）。
        var hooked = 0

        for (className in HostTargets.ROUTER_CLASSES) {
            val routerClass = findClass(classLoader, className) ?: continue

            val methods = routerClass.declaredMethods.filter { method ->
                val params = method.parameterTypes
                if (params.isEmpty()) return@filter false
                val first = params[0].name
                val name = method.name.lowercase()
                first.endsWith("Uri") || first.endsWith("Intent") || first == "java.lang.String" ||
                    name.contains("uri") || name.contains("route") || name.contains("open") ||
                    name.contains("handle") || name.contains("jump") || name.contains("navigate")
            }

            for (method in methods) {
                try {
                    module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val uri = extractRoutedUri(chain.args)
                            if (uri.startsWith(SETTING_URI) || uri.contains(SETTING_URI)) {
                                val context = chain.args.firstNotNullOfOrNull { arg ->
                                    when (arg) {
                                        is Activity -> arg
                                        is android.content.Context -> PlayerBridge.activity(arg)
                                        is View -> PlayerBridge.activity(arg)
                                        else -> null
                                    }
                                } ?: (chain.getThisObject() as? Activity)
                                    ?: chain.getThisObject()?.let { PlayerBridge.activity(it) }
                                if (context != null) {
                                    SponsorBlockSettingDialog.show(context)
                                    module.info("Intercepted $SETTING_URI via ${method.name}")
                                    val rt = method.returnType
                                    return@intercept when {
                                        rt == Void.TYPE || rt == Void::class.java -> null
                                        rt == java.lang.Boolean.TYPE || rt == Boolean::class.java -> true
                                        else -> chain.proceed()
                                    }
                                }
                            }
                            chain.proceed()
                        }
                    hooked++
                    module.info("Hooked URI router: ${className}.${method.name}")
                } catch (e: Throwable) {
                    // 继续尝试其他方法
                }
            }
        }

        if (hooked > 0) {
            HookProbe.ok(module, "uriRouter", "$hooked methods")
        } else {
            HookProbe.miss(module, "uriRouter", HostTargets.ROUTER_CLASSES.joinToString())
        }
    }
}

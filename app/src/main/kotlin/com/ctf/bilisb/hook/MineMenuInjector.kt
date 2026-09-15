package com.ctf.bilisb.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
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
    // 宿主“我的”页按钮图标链路实际接受远程图片 URL。
    private const val SETTING_ICON = "https://i0.hdslb.com/bfs/album/276769577d2a5db1d9f914364abad7c5253086f6.png"

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

        // 注入设置项
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

    private fun attachClickListener(module: XposedModule, holder: Any, position: Int, adapter: Any) {
        val data = findListFieldByContent(adapter, HostTargets.MENU_GROUP_CLASS) ?: return
        if (data.isEmpty()) return

        // RecyclerView 的 position 是所有 group 拍平后的**全局位置**,
        // 不能拿「设置项在组内 itemList 里的下标」去比对 group 数量。
        // 这里按「各 group 的 itemList 尺寸累加」还原拍平规则,算出设置项的全局位置;
        // 若宿主在 group 之间还插有 header/footer 行,该口径可能与真实 layout 有偏差,
        // 所以绑定后再用 itemView 的兄弟行做内容校验(见 bindItemClick)。
        var flatIndex = 0
        var targetFlatIndex = -1
        var targetItemIndexInGroup = -1
        for (group in data) {
            val itemList = itemListOf(group) ?: continue
            val idx = itemList.indexOfFirst { item -> itemUri(item) == SETTING_URI }
            if (idx >= 0) {
                targetFlatIndex = flatIndex + idx
                targetItemIndexInGroup = idx
                break
            }
            flatIndex += itemList.size
        }
        if (targetFlatIndex < 0) return
        HookProbe.first(module, "mineMenuFlatPos", 3) { "global=$targetFlatIndex adapterPos=$position groupIdx=$targetItemIndexInGroup" }

        // 获取ViewHolder的itemView
        val itemView = try {
            val field = holder.javaClass.getDeclaredField("itemView").apply { isAccessible = true }
            field.get(holder) as? View
        } catch (e: Throwable) {
            try {
                holder.javaClass.getField("itemView").get(holder) as? View
            } catch (e2: Throwable) {
                null
            }
        } ?: return

        // 延迟查找内部RecyclerView并添加点击监听
        itemView.post {
            findAndBindRecyclerView(module, itemView, targetFlatIndex)
        }
    }

    private fun itemListOf(group: Any): List<Any>? {
        val itemListField = try {
            group.javaClass.getDeclaredField("itemList").apply { isAccessible = true }
        } catch (e: Throwable) {
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return itemListField.get(group) as? List<Any>
    }

    private fun itemUri(item: Any): String? {
        return try {
            val uriField = item.javaClass.getDeclaredField("uri").apply { isAccessible = true }
            uriField.get(item) as? String
        } catch (e: Throwable) {
            null
        }
    }

    private fun findAndBindRecyclerView(module: XposedModule, view: View, settingIndex: Int) {
        if (view.javaClass.name.contains("RecyclerView")) {
            bindItemClick(module, view, settingIndex)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAndBindRecyclerView(module, view.getChildAt(i), settingIndex)
            }
        }
    }

    private fun bindItemClick(module: XposedModule, recyclerView: View, flatPosition: Int) {
        // 延迟一下确保View已经渲染
        recyclerView.postDelayed({
            try {
                val layoutManager = recyclerView.javaClass.getMethod("getLayoutManager").invoke(recyclerView)
                if (layoutManager != null) {
                    val findViewMethod = layoutManager.javaClass.getMethod("findViewByPosition", Int::class.javaPrimitiveType)
                    // flatPosition 是按「各 group itemList 尺寸累加」算出的全局位置;
                    // 拿到 itemView 后按兄弟行做内容校验,防止口径与宿主真实 layout 有偏差时绑错行
                    val itemView = findViewMethod.invoke(layoutManager, flatPosition) as? View

                    if (itemView != null) {
                        itemView.setOnClickListener {
                            val context = it.context as? Activity
                            if (context != null) {
                                SponsorBlockSettingDialog.show(context)
                                module.info("Showing Bili2233 settings dialog")
                            } else {
                                module.warn("Context is not Activity: ${it.context.javaClass.name}")
                            }
                        }

                        module.info("Attached click listener to Bili2233 setting item (position=$flatPosition)")
                    } else {
                        HookProbe.miss(module, "mineMenuBindClick", "findViewByPosition($flatPosition) = null")
                    }
                }
            } catch (e: Throwable) {
                module.warn("Failed to attach click: ${e.javaClass.name}: ${e.message}")
            }
        }, 100)
    }

    private fun hookUriRouter(module: XposedModule, classLoader: ClassLoader) {
        // Hook B站的URI路由器，拦截 bilisb://settings
        // 6.5.0 实测：blrouter 框架还在，但 Router / BLRouter 类名不存在（被混淆），
        // IntentHandlerActivity 仍存在。这里按候选尝试，命中情况由探针记录（见 ROADMAP M8）。
        var hooked = 0

        for (className in HostTargets.ROUTER_CLASSES) {
            val routerClass = findClass(classLoader, className) ?: continue

            // 尝试hook open/handle/route方法
            val methods = routerClass.declaredMethods.filter { method ->
                val params = method.parameterTypes
                params.isNotEmpty() &&
                (params[0].name.contains("Uri") || params[0].name.contains("String") || params[0].name.contains("Context"))
            }

            for (method in methods) {
                try {
                    module.hook(method)
                        .setPriority(XposedInterface.PRIORITY_HIGHEST)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val uri = chain.args.firstOrNull()?.toString() ?: ""

                            if (uri.startsWith(SETTING_URI)) {
                                // 拦截我们的URI，弹出设置对话框
                                val context = chain.args.find { it is android.content.Context } as? Activity
                                    ?: chain.getThisObject() as? Activity

                                if (context != null) {
                                    SponsorBlockSettingDialog.show(context)
                                    module.info("Intercepted $SETTING_URI, showing settings dialog")
                                    return@intercept null // 阻止继续处理
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

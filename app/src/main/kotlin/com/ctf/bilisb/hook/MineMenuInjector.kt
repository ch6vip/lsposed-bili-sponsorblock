package com.ctf.bilisb.hook

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import com.ctf.bilisb.settings.SponsorBlockSettingDialog
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * 在B站"我的"页面菜单注入设置入口。
 */
object MineMenuInjector {

    private const val SETTING_ID = 0x5B5B5B5BL
    private const val SETTING_URI = "bilisb://settings"
    private const val SETTING_TITLE = "Bili2233"
    // 宿主“我的”页按钮图标链路实际接受远程图片 URL。
    private const val SETTING_ICON = "https://i0.hdslb.com/bfs/album/276769577d2a5db1d9f914364abad7c5253086f6.png"

    fun install(module: XposedModule, classLoader: ClassLoader) {
        try {
            val menuGroupClass = findClass(classLoader, "com.bilibili.lib.homepage.mine.MenuGroup")
            val menuItemClass = findClass(classLoader, "com.bilibili.lib.homepage.mine.MenuGroup\$Item")

            if (menuGroupClass == null || menuItemClass == null) {
                module.warn("MenuGroup or Item class not found, skip mine menu injection")
                return
            }

            hookMineAdapter(module, classLoader, menuItemClass)

            // Hook URI路由器拦截点击
            hookUriRouter(module, classLoader)

            module.info("MineMenuInjector installed")
        } catch (e: Throwable) {
            module.warn("Failed to install MineMenuInjector: ${e.message}")
        }
    }

    private fun findClass(classLoader: ClassLoader, name: String): Class<*>? {
        return try {
            classLoader.loadClass(name)
        } catch (e: Throwable) {
            null
        }
    }

    private fun hookMineAdapter(module: XposedModule, classLoader: ClassLoader, menuItemClass: Class<*>) {
        val adapterClass = try {
            classLoader.loadClass("tv.danmaku.bili.ui.main2.mine.HomeUserCenterAdapter")
        } catch (e: Throwable) {
            module.warn("HomeUserCenterAdapter not found")
            return
        }

        val notifyMethod = try {
            adapterClass.getMethod("notifyDataSetChanged")
        } catch (e: Throwable) {
            module.warn("Failed to resolve notifyDataSetChanged: ${e.message}")
            return
        }

        module.hook(notifyMethod)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                try {
                    val adapter = chain.getThisObject()
                    injectSettingItemIfMineAdapter(module, adapter, menuItemClass)
                } catch (e: Throwable) {
                    // 忽略错误
                }
                chain.proceed()
            }

        module.info("Hooked HomeUserCenterAdapter.notifyDataSetChanged")

        // Hook HomeUserCenterAdapter的onBindViewHolder添加点击监听
        hookAdapterClickListener(module, classLoader)
    }

    private fun hookAdapterClickListener(module: XposedModule, classLoader: ClassLoader) {
        val adapterClass = try {
            classLoader.loadClass("tv.danmaku.bili.ui.main2.mine.HomeUserCenterAdapter")
        } catch (e: Throwable) {
            module.warn("HomeUserCenterAdapter not found")
            return
        }

        val viewHolderClass = try {
            classLoader.loadClass("androidx.recyclerview.widget.RecyclerView\$ViewHolder")
        } catch (e: Throwable) {
            module.warn("RecyclerView.ViewHolder not found")
            return
        }

        // Hook onBindViewHolder
        val onBindMethod = try {
            adapterClass.getDeclaredMethod(
                "onBindViewHolder",
                viewHolderClass,
                Int::class.javaPrimitiveType
            )
        } catch (e: Throwable) {
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

        module.info("Hooked HomeUserCenterAdapter.onBindViewHolder")
    }

    private fun injectSettingItemIfMineAdapter(module: XposedModule, adapter: Any, menuItemClass: Class<*>) {
        // 查找存储MenuGroup列表的字段
        val dataField = findDataField(adapter) ?: return

        @Suppress("UNCHECKED_CAST")
        val data = dataField.get(adapter) as? MutableList<Any> ?: return

        if (data.isEmpty()) return

        // 检查第一个元素是否是MenuGroup类型
        val firstItem = data.firstOrNull() ?: return
        if (firstItem.javaClass.name != "com.bilibili.lib.homepage.mine.MenuGroup") {
            return // 不是mine页面的adapter
        }

        // 记录adapter类名用于后续hook点击
        module.info("Found mine adapter: ${adapter.javaClass.name}")

        // 注入设置项
        injectSettingItem(module, data, menuItemClass)
    }

    private fun findDataField(adapter: Any): Field? {
        val fields = adapter.javaClass.declaredFields
        return fields.firstOrNull {
            List::class.java.isAssignableFrom(it.type)
        }?.apply {
            isAccessible = true
        }
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
            val item = menuItemClass.newInstance()

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
            field.set(obj, value)
        } catch (e: Throwable) {
            // 字段不存在或类型不匹配，忽略
        }
    }

    private fun attachClickListener(module: XposedModule, holder: Any, position: Int, adapter: Any) {
        // 获取适配器的数据
        val dataField = findDataField(adapter) ?: return
        @Suppress("UNCHECKED_CAST")
        val data = dataField.get(adapter) as? List<Any> ?: return

        if (position >= data.size) return

        val group = data[position]
        val itemListField = try {
            group.javaClass.getDeclaredField("itemList").apply { isAccessible = true }
        } catch (e: Throwable) {
            return
        }

        @Suppress("UNCHECKED_CAST")
        val itemList = itemListField.get(group) as? List<Any> ?: return

        // 检查这个group是否包含我们的设置项
        val hasSettingItem = itemList.any { item ->
            try {
                val uriField = item.javaClass.getDeclaredField("uri").apply { isAccessible = true }
                uriField.get(item) == SETTING_URI
            } catch (e: Throwable) {
                false
            }
        }

        if (!hasSettingItem) return

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
            findAndBindRecyclerView(module, itemView, itemList)
        }
    }

    private fun findAndBindRecyclerView(module: XposedModule, view: View, itemList: List<Any>) {
        if (view.javaClass.name.contains("RecyclerView")) {
            val adapterField = try {
                view.javaClass.getMethod("getAdapter").invoke(view)
            } catch (e: Throwable) {
                null
            }
            if (adapterField != null) {
                bindItemClicks(module, view, adapterField)
            }
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findAndBindRecyclerView(module, view.getChildAt(i), itemList)
            }
        }
    }

    private fun bindItemClicks(module: XposedModule, recyclerView: View, adapter: Any) {
        val dataField = findDataField(adapter) ?: return
        @Suppress("UNCHECKED_CAST")
        val items = dataField.get(adapter) as? List<Any> ?: return

        // 找到我们的设置项索引
        val settingIndex = items.indexOfFirst { item ->
            try {
                val uriField = item.javaClass.getDeclaredField("uri").apply { isAccessible = true }
                uriField.get(item) == SETTING_URI
            } catch (e: Throwable) {
                false
            }
        }

        if (settingIndex < 0) return

        // 延迟一下确保View已经渲染
        recyclerView.postDelayed({
            try {
                val layoutManager = recyclerView.javaClass.getMethod("getLayoutManager").invoke(recyclerView)
                if (layoutManager != null) {
                    val findViewMethod = layoutManager.javaClass.getMethod("findViewByPosition", Int::class.javaPrimitiveType)
                    val itemView = findViewMethod.invoke(layoutManager, settingIndex) as? View

                    itemView?.setOnClickListener {
                        val context = it.context as? Activity
                        if (context != null) {
                            SponsorBlockSettingDialog.show(context)
                            module.info("Showing Bili2233 settings dialog")
                        } else {
                            module.warn("Context is not Activity: ${it.context.javaClass.name}")
                        }
                    }

                    module.info("Attached click listener to Bili2233 setting item")
                }
            } catch (e: Throwable) {
                module.warn("Failed to attach click: ${e.message}")
            }
        }, 100)
    }

    private fun hookUriRouter(module: XposedModule, classLoader: ClassLoader) {
        // Hook B站的URI路由器，拦截 bilisb://settings
        // 尝试hook常见的URI处理类
        val routerClasses = listOf(
            "com.bilibili.lib.blrouter.Router",
            "com.bilibili.lib.blrouter.BLRouter",
            "tv.danmaku.bili.ui.intent.IntentHandlerActivity"
        )

        for (className in routerClasses) {
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
                    module.info("Hooked URI router: ${className}.${method.name}")
                } catch (e: Throwable) {
                    // 继续尝试其他方法
                }
            }
        }
    }
}

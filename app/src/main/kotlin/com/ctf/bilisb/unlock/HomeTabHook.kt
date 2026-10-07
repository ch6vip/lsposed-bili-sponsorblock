package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 添加其他地区番剧（add_bangumi）：在首页顶栏导航添加「追番（大陆）」和「追番（港澳台）」。
 *
 * 6.6.0 全新架构支持：
 * 首页采用 Kotlin Coroutines / Flow + kotlinx.serialization 架构：
 *  - 数据模型：fE1.l (HomeTabResponse) -> fE1.j (HomeTabData) -> fE1.k (HomeTabItemData)
 *  - 数据流转中心：tv.danmaku.bili.khomedata.repo.a (HomeFrameDataRepo)
 *  - 本地缓存反序列化：tv.danmaku.bili.khomedata.repo.cache.b (CacheTabDataStore) / tv.danmaku.bili.khomedata.repo.cache.a (CacheTabDataRepo)
 *  - 首页 ViewModel：tv.danmaku.bili.khome.vm.HomeFrameViewModel.v0(action)
 *
 * 旧版兼容：
 *  - CachedResourceResolver.a() / Companion.a() / fastjson parseObject
 *
 * 注入业务字段：
 *  - 追番（大陆）: tabId="50", uri="bilibili://pgc/home", reportId="bangumi", pos=50
 *  - 追番（港澳台）: tabId="60", uri="bilibili://following/home_activity_tab/6544", reportId="bangumi", pos=60
 */
object HomeTabHook {

    // 6.6.0 现代架构类
    private const val REPO_CLASS = "tv.danmaku.bili.khomedata.repo.a"
    private const val VIEW_MODEL_CLASS = "tv.danmaku.bili.khome.vm.HomeFrameViewModel"
    private const val CACHE_STORE_CLASS = "tv.danmaku.bili.khomedata.repo.cache.b"
    private const val CACHE_REPO_CLASS = "tv.danmaku.bili.khomedata.repo.cache.a"

    // 旧版兼容类
    private const val TAB_RESPONSE_CLASS = "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$TabResponse"
    private const val TAB_CLASS = "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$Tab"
    private const val CACHED_RESOLVER_CLASS = "tv.danmaku.bili.ui.main2.resource.CachedResourceResolver"
    private const val CACHED_RESOLVER_COMPANION_CLASS = "tv.danmaku.bili.ui.main2.resource.CachedResourceResolver\$Companion"
    private const val FASTJSON_CLASS = "com.alibaba.fastjson.JSON"

    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            var hookedAny = false

            // 1. Hook 6.6.0 HomeFrameViewModel.v0(action) - 页面与顶栏渲染入口（最关键兜底）
            hookedAny = hookViewModel(module, cl) || hookedAny

            // 2. Hook 6.6.0 HomeFrameDataRepo.e(lVar, continuation) - 网络数据源及存盘入口
            hookedAny = hookRepo(module, cl) || hookedAny

            // 3. Hook 6.6.0 CacheTabDataStore.b(continuation) / CacheTabDataRepo.b(continuation) - 磁盘缓存加载入口
            hookedAny = hookCache(module, cl) || hookedAny

            // 4. 旧版兼容挂载
            hookedAny = hookLegacy(module, cl) || hookedAny

            if (hookedAny) {
                HookProbe.ok(module, "homeTab:unlock", "installed")
            } else {
                HookProbe.miss(module, "homeTab:unlock", "target methods not found")
            }
        }.onFailure { t ->
            HookProbe.miss(module, "homeTab:unlock", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Hook 6.6.0 HomeFrameViewModel.v0(action)
     */
    private fun hookViewModel(module: XposedModule, cl: ClassLoader): Boolean {
        var hooked = false
        runCatching {
            val vmClass = Class.forName(VIEW_MODEL_CLASS, false, cl)
            for (m in vmClass.declaredMethods) {
                if (m.name == "v0" && m.parameterTypes.size == 1) {
                    m.isAccessible = true
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val action = chain.args.getOrNull(0)
                            if (action != null) {
                                findTabData(action)?.let { tabData ->
                                    applyInjection(module, tabData, null, "vm.v0")
                                }
                            }
                            chain.proceed()
                        }
                    hooked = true
                    HookProbe.ok(module, "homeTab:vmV0", "$VIEW_MODEL_CLASS.v0")
                }
            }
        }
        return hooked
    }

    /**
     * Hook 6.6.0 HomeFrameDataRepo.e(lVar, continuation)
     */
    private fun hookRepo(module: XposedModule, cl: ClassLoader): Boolean {
        var hooked = false
        runCatching {
            val repoClass = Class.forName(REPO_CLASS, false, cl)
            for (m in repoClass.declaredMethods) {
                if (m.name == "e" && m.parameterTypes.isNotEmpty() && Modifier.isStatic(m.modifiers)) {
                    m.isAccessible = true
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val lVar = chain.args.getOrNull(0)
                            if (lVar != null) {
                                findTabData(lVar)?.let { tabData ->
                                    applyInjection(module, tabData, null, "repo.e")
                                }
                            }
                            chain.proceed()
                        }
                    hooked = true
                    HookProbe.ok(module, "homeTab:repoE", "$REPO_CLASS.e")
                }
            }
        }
        return hooked
    }

    /**
     * Hook 6.6.0 CacheTabDataStore.b / CacheTabDataRepo.b
     */
    private fun hookCache(module: XposedModule, cl: ClassLoader): Boolean {
        var hooked = false
        // CacheTabDataStore.b
        runCatching {
            val storeClass = Class.forName(CACHE_STORE_CLASS, false, cl)
            for (m in storeClass.declaredMethods) {
                if (m.name == "b" && m.returnType == Any::class.java) {
                    m.isAccessible = true
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val res = chain.proceed()
                            if (res != null) {
                                findTabData(res)?.let { tabData ->
                                    applyInjection(module, tabData, null, "cacheStore.b")
                                }
                            }
                            res
                        }
                    hooked = true
                    HookProbe.ok(module, "homeTab:cacheStore", "$CACHE_STORE_CLASS.b")
                }
            }
        }
        // CacheTabDataRepo.b
        runCatching {
            val repoClass = Class.forName(CACHE_REPO_CLASS, false, cl)
            for (m in repoClass.declaredMethods) {
                if (m.name == "b" && m.returnType == Any::class.java) {
                    m.isAccessible = true
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val res = chain.proceed()
                            if (res != null) {
                                findTabData(res)?.let { tabData ->
                                    applyInjection(module, tabData, null, "cacheRepo.b")
                                }
                            }
                            res
                        }
                    hooked = true
                    HookProbe.ok(module, "homeTab:cacheRepo", "$CACHE_REPO_CLASS.b")
                }
            }
        }
        return hooked
    }

    /**
     * 旧版兼容入口挂载
     */
    private fun hookLegacy(module: XposedModule, cl: ClassLoader): Boolean {
        var hooked = false
        val tabClass = runCatching { Class.forName(TAB_CLASS, false, cl) }.getOrNull()

        // 1. CachedResourceResolver.a()
        hooked = hookResolverMethod(module, cl, CACHED_RESOLVER_CLASS, "homeTab:cache", tabClass) || hooked
        // 1b. CachedResourceResolver$Companion.a()
        hooked = hookResolverMethod(module, cl, CACHED_RESOLVER_COMPANION_CLASS, "homeTab:companion", tabClass) || hooked

        // 2. Fastjson JSON.parseObject(...)
        runCatching {
            val jsonClass = Class.forName(FASTJSON_CLASS, false, cl)
            var jsonHooked = false
            for (m in jsonClass.declaredMethods) {
                if (m.name == "parseObject" && m.parameterTypes.isNotEmpty() && m.parameterTypes[0] == String::class.java) {
                    m.isAccessible = true
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val res = chain.proceed()
                            if (res != null && res.javaClass.name == TAB_RESPONSE_CLASS) {
                                applyInjection(module, res, tabClass, "json")
                            }
                            res
                        }
                    jsonHooked = true
                }
            }
            if (jsonHooked) {
                HookProbe.ok(module, "homeTab:json", "JSON.parseObject")
                hooked = true
            }
        }
        return hooked
    }

    private fun hookResolverMethod(
        module: XposedModule,
        cl: ClassLoader,
        className: String,
        probeKey: String,
        tabClass: Class<*>?,
    ): Boolean {
        var hooked = false
        runCatching {
            val resolverClass = Class.forName(className, false, cl)
            for (m in resolverClass.declaredMethods) {
                if (m.name == "a" && m.parameterTypes.isEmpty() && m.returnType.name == TAB_RESPONSE_CLASS) {
                    m.isAccessible = true
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val res = chain.proceed()
                            applyInjection(module, res, tabClass, probeKey)
                            res
                        }
                    hooked = true
                    HookProbe.ok(module, probeKey, "$className.a")
                }
            }
        }
        return hooked
    }

    private fun applyInjection(
        module: XposedModule,
        target: Any?,
        tabClass: Class<*>?,
        via: String,
    ): Boolean {
        if (target == null) return false
        val config = UnlockConfig.load(module)
        if (!config.enabled || !config.addBangumi) {
            HookProbe.first(module, "homeTab:off", 6) {
                "via=$via enabled=${config.enabled} add=${config.addBangumi}"
            }
            return false
        }
        val before = tabCountOf(target)
        val changed = injectBangumiTabs(target, tabClass)
        HookProbe.first(module, "homeTab:inject", 10) {
            "via=$via before=$before after=${tabCountOf(target)} changed=$changed ${listDigest(target)}"
        }
        return changed
    }

    private fun tabCountOf(target: Any): Int {
        val tabData = findTabData(target) ?: return -1
        val list = (getFieldSafely(tabData, "b") ?: getFieldSafely(tabData, "tab")) as? List<*>
        return list?.size ?: -1
    }

    private fun listDigest(target: Any): String {
        val tabData = findTabData(target) ?: return "noTabData"
        val list = (getFieldSafely(tabData, "b") ?: getFieldSafely(tabData, "tab")) as? List<*> ?: return "noTabList"
        val items = list.mapNotNull { item ->
            if (item == null) return@mapNotNull null
            val id = (getFieldSafely(item, "a") ?: getFieldSafely(item, "tabId") ?: getFieldSafely(item, "id")) as? String ?: "?"
            val nm = (getFieldSafely(item, "b") ?: getFieldSafely(item, "name")) as? String ?: "?"
            "$id:$nm"
        }
        return "tab=${list.size}[${items.joinToString(",")}]"
    }

    /**
     * 查找目标对象中的 TabData 实体（支持 TabResponse、fE1.l、fE1.j、Action包装对象等）。
     */
    fun findTabData(obj: Any?): Any? {
        if (obj == null) return null
        // 1. 直接具有 tabData 字段（旧版 TabResponse）
        getFieldSafely(obj, "tabData")?.let { return it }
        // 2. 直接具有 d 字段（fE1.l 响应对象）
        getFieldSafely(obj, "d")?.let { d ->
            if (hasTabList(d)) return d
        }
        getFieldSafely(obj, "data")?.let { d ->
            if (hasTabList(d)) return d
        }
        // 3. 自身即为 TabData 实体（fE1.j 或 MockTabData）
        if (hasTabList(obj)) return obj
        // 4. 扫描非静态成员字段（处理 bE1.f 等 Action 包装类）
        for (f in obj.javaClass.declaredFields) {
            if (Modifier.isStatic(f.modifiers)) continue
            val v = runCatching {
                f.isAccessible = true
                f.get(obj)
            }.getOrNull()
            if (v != null && hasTabList(v)) {
                return v
            }
        }
        return null
    }

    private fun hasTabList(obj: Any?): Boolean {
        if (obj == null) return false
        val list = (getFieldSafely(obj, "b") ?: getFieldSafely(obj, "tab")) as? List<*>
        return list != null
    }

    /**
     * 注入番剧页签（幂等）。
     * 保持 (container, tabClass) 签名以保证旧版调用及现有单元测试无缝通过。
     */
    fun injectBangumiTabs(container: Any?, tabClass: Class<*>? = null): Boolean {
        if (container == null) return false
        val tabData = findTabData(container) ?: return false
        @Suppress("UNCHECKED_CAST")
        val rawList = (getFieldSafely(tabData, "b") ?: getFieldSafely(tabData, "tab")) as? List<Any> ?: return false

        var hasMainland = false
        var hasHkMoTw = false
        for (tab in rawList) {
            val tabId = (getFieldSafely(tab, "a") ?: getFieldSafely(tab, "tabId") ?: getFieldSafely(tab, "id")) as? String ?: ""
            val uri = (getFieldSafely(tab, "c") ?: getFieldSafely(tab, "uri")) as? String ?: ""
            if (tabId == "50" || uri == "bilibili://pgc/home" || uri == "bilibili://pgc/bangumi_v2") {
                hasMainland = true
            }
            if (tabId == "60" || uri == "bilibili://following/home_activity_tab/6544") {
                hasHkMoTw = true
            }
        }
        if (hasMainland && hasHkMoTw) return false

        val template = rawList.firstOrNull()
        val actualClass = tabClass ?: template?.javaClass ?: return false
        val mutableList = ArrayList(rawList)
        if (!hasMainland) {
            val tab = createTab(
                tabClass = actualClass,
                template = template,
                tabId = "50",
                name = "追番（大陆）",
                uri = "bilibili://pgc/home",
                reportId = "bangumi",
                pos = 50,
            )
            if (tab != null) mutableList.add(tab)
        }
        if (!hasHkMoTw) {
            val tab = createTab(
                tabClass = actualClass,
                template = template,
                tabId = "60",
                name = "追番（港澳台）",
                uri = "bilibili://following/home_activity_tab/6544",
                reportId = "bangumi",
                pos = 60,
            )
            if (tab != null) mutableList.add(tab)
        }

        // 按 pos 排序确保顶栏和 ViewPager2 顺序稳定一致
        mutableList.sortBy { item ->
            val p = getFieldSafely(item, "g") ?: getFieldSafely(item, "pos")
            when (p) {
                is Int -> p
                is Number -> p.toInt()
                else -> 0
            }
        }

        return setFieldSafely(tabData, "b", mutableList) || setFieldSafely(tabData, "tab", mutableList)
    }

    private val unsafe: Any? by lazy {
        runCatching {
            val f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            f.isAccessible = true
            f.get(null)
        }.getOrNull()
    }

    private val allocateInstanceMethod by lazy {
        runCatching {
            unsafe?.javaClass?.getMethod("allocateInstance", Class::class.java)
        }.getOrNull()
    }

    private fun allocateInstance(clazz: Class<*>): Any? {
        return runCatching {
            val u = unsafe ?: return null
            val m = allocateInstanceMethod ?: return null
            m.invoke(u, clazz)
        }.getOrNull() ?: runCatching {
            val ctor = clazz.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
                ?: clazz.declaredConstructors.firstOrNull() ?: return null
            ctor.isAccessible = true
            val params = arrayOfNulls<Any>(ctor.parameterTypes.size)
            for (idx in ctor.parameterTypes.indices) {
                val pt = ctor.parameterTypes[idx]
                if (pt == Int::class.javaPrimitiveType) params[idx] = 0
                else if (pt == Long::class.javaPrimitiveType) params[idx] = 0L
                else if (pt == Boolean::class.javaPrimitiveType) params[idx] = false
            }
            ctor.newInstance(*params)
        }.getOrNull()
    }

    private fun createTab(
        tabClass: Class<*>,
        template: Any?,
        tabId: String,
        name: String,
        uri: String,
        reportId: String,
        pos: Int,
    ): Any? = runCatching {
        val actualClass = template?.javaClass ?: tabClass
        val tab = allocateInstance(actualClass) ?: return null

        if (template != null) {
            for (f in actualClass.declaredFields) {
                if (Modifier.isStatic(f.modifiers)) continue
                runCatching {
                    f.isAccessible = true
                    f.set(tab, f.get(template))
                }
            }
        }

        // 覆盖 6.6.0 fE1.k 混淆字段
        setFieldSafely(tab, "a", tabId)
        setFieldSafely(tab, "b", name)
        setFieldSafely(tab, "c", uri)
        setFieldSafely(tab, "f", 0) // default_selected 必须为 0，防止抢占默认选中
        setFieldSafely(tab, "g", pos)
        setFieldSafely(tab, "h", reportId)
        setFieldSafely(tab, "p", 0L) // expired_at 必须为 0，防止被过期过滤

        // 同时覆盖语义字段（用于测试环境与未混淆/旧版模型）
        setFieldSafely(tab, "tabId", tabId)
        setFieldSafely(tab, "id", tabId)
        setFieldSafely(tab, "name", name)
        setFieldSafely(tab, "uri", uri)
        setFieldSafely(tab, "reportId", reportId)
        setFieldSafely(tab, "tab_id", reportId)
        setFieldSafely(tab, "pos", pos)
        setFieldSafely(tab, "default_selected", 0)
        setFieldSafely(tab, "defaultSelected", 0)
        setFieldSafely(tab, "expired_at", 0L)
        setFieldSafely(tab, "expiredAt", 0L)

        tab
    }.getOrNull()

    private fun getFieldSafely(obj: Any, fieldName: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            val field = c.declaredFields.firstOrNull { it.name.equals(fieldName, ignoreCase = true) }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(obj)
                }.getOrNull()
            }
            c = c.superclass
        }
        val getterName = "get" + fieldName.replaceFirstChar { it.uppercase() }
        val getter = obj.javaClass.methods.firstOrNull { it.name == getterName && it.parameterTypes.isEmpty() }
        return runCatching {
            getter?.isAccessible = true
            getter?.invoke(obj)
        }.getOrNull()
    }

    private fun setFieldSafely(obj: Any, fieldName: String, value: Any?): Boolean {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            val field = c.declaredFields.firstOrNull { it.name.equals(fieldName, ignoreCase = true) }
            if (field != null) {
                val ok = runCatching {
                    field.isAccessible = true
                    field.set(obj, value)
                    true
                }.getOrDefault(false)
                if (ok) return true
            }
            c = c.superclass
        }
        val setterName = "set" + fieldName.replaceFirstChar { it.uppercase() }
        val setter = obj.javaClass.methods.firstOrNull { it.name == setterName && it.parameterTypes.size == 1 }
        if (setter != null) {
            return runCatching {
                setter.isAccessible = true
                setter.invoke(obj, value)
                true
            }.getOrDefault(false)
        }
        return false
    }
}

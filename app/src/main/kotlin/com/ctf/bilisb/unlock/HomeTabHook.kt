package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 添加其他地区番剧（add_bangumi）：在首页顶栏导航添加「追番（大陆）」和「追番（港澳台）」。
 * 对齐 BiliRoaming fb.1.smali / C0122fb / C0012ab 实现：
 * 拦截 MainResourceManager$TabResponse 反序列化与缓存获取，
 * 在 tabData.tab 列表中注入：
 *  - 追番（大陆）: tabId="50", uri="bilibili://pgc/home", reportId="bangumi", pos=50
 *  - 追番（港澳台）: tabId="60", uri="bilibili://following/home_activity_tab/6544", reportId="bangumi", pos=60
 */
object HomeTabHook {

    private const val TAB_RESPONSE_CLASS = "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$TabResponse"
    private const val TAB_CLASS = "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$Tab"
    private const val CACHED_RESOLVER_CLASS = "tv.danmaku.bili.ui.main2.resource.CachedResourceResolver"
    private const val FASTJSON_CLASS = "com.alibaba.fastjson.JSON"

    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            var hookedAny = false
            val tabClass = runCatching { Class.forName(TAB_CLASS, false, cl) }.getOrNull()

            // 1. Hook CachedResourceResolver.a() -> TabResponse
            runCatching {
                val resolverClass = Class.forName(CACHED_RESOLVER_CLASS, false, cl)
                for (m in resolverClass.declaredMethods) {
                    if (m.name == "a" && m.parameterTypes.isEmpty() && m.returnType.name == TAB_RESPONSE_CLASS) {
                        m.isAccessible = true
                        runCatching { module.deoptimize(m) }
                        module.hook(m)
                            .setPriority(XposedInterface.PRIORITY_DEFAULT)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val res = chain.proceed()
                                val config = UnlockConfig.load(module)
                                if (config.enabled && config.addBangumi && tabClass != null) {
                                    injectBangumiTabs(res, tabClass)
                                }
                                res
                            }
                        hookedAny = true
                        HookProbe.ok(module, "homeTab:cache", "CachedResourceResolver.a")
                    }
                }
            }

            // 2. Hook Fastjson JSON.parseObject(...) returning TabResponse
            runCatching {
                val jsonClass = Class.forName(FASTJSON_CLASS, false, cl)
                for (m in jsonClass.declaredMethods) {
                    if (m.name == "parseObject" && m.returnType == Any::class.java) {
                        m.isAccessible = true
                        runCatching { module.deoptimize(m) }
                        module.hook(m)
                            .setPriority(XposedInterface.PRIORITY_DEFAULT)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .intercept { chain ->
                                val res = chain.proceed()
                                if (res != null && res.javaClass.name == TAB_RESPONSE_CLASS) {
                                    val config = UnlockConfig.load(module)
                                    if (config.enabled && config.addBangumi && tabClass != null) {
                                        injectBangumiTabs(res, tabClass)
                                    }
                                }
                                res
                            }
                        hookedAny = true
                    }
                }
                if (hookedAny) {
                    HookProbe.ok(module, "homeTab:json", "JSON.parseObject")
                }
            }

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
     * 注入番剧页签（幂等）。
     */
    fun injectBangumiTabs(tabResponse: Any?, tabClass: Class<*>): Boolean {
        if (tabResponse == null) return false
        val tabData = getFieldSafely(tabResponse, "tabData") ?: return false
        @Suppress("UNCHECKED_CAST")
        val rawList = getFieldSafely(tabData, "tab") as? List<Any> ?: return false

        var hasMainland = false
        var hasHkMoTw = false
        for (tab in rawList) {
            val uri = getFieldSafely(tab, "uri") as? String ?: ""
            if (uri == "bilibili://pgc/home" || uri == "bilibili://pgc/bangumi_v2") {
                hasMainland = true
            }
            if (uri == "bilibili://following/home_activity_tab/6544") {
                hasHkMoTw = true
            }
        }
        if (hasMainland && hasHkMoTw) return false

        val mutableList = ArrayList(rawList)
        if (!hasMainland) {
            val tab = createTab(
                tabClass = tabClass,
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
                tabClass = tabClass,
                tabId = "60",
                name = "追番（港澳台）",
                uri = "bilibili://following/home_activity_tab/6544",
                reportId = "bangumi",
                pos = 60,
            )
            if (tab != null) mutableList.add(tab)
        }
        setFieldSafely(tabData, "tab", mutableList)
        return true
    }

    private fun createTab(
        tabClass: Class<*>,
        tabId: String,
        name: String,
        uri: String,
        reportId: String,
        pos: Int,
    ): Any? = runCatching {
        val tab = tabClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        setFieldSafely(tab, "tabId", tabId)
        setFieldSafely(tab, "name", name)
        setFieldSafely(tab, "uri", uri)
        setFieldSafely(tab, "reportId", reportId)
        setFieldSafely(tab, "pos", pos)
        tab
    }.getOrNull()

    private fun getFieldSafely(obj: Any, fieldName: String): Any? = runCatching {
        val field = obj.javaClass.declaredFields.firstOrNull { it.name == fieldName } ?: return null
        field.isAccessible = true
        field.get(obj)
    }.getOrNull()

    private fun setFieldSafely(obj: Any, fieldName: String, value: Any?): Boolean = runCatching {
        val field = obj.javaClass.declaredFields.firstOrNull { it.name == fieldName } ?: return false
        field.isAccessible = true
        field.set(obj, value)
        true
    }.getOrDefault(false)
}

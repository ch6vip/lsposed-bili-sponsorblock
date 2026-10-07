package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 添加其他地区番剧（add_bangumi）：在首页顶栏导航添加「追番（大陆）」和「追番（港澳台）」。
 * 对齐 BiliRoaming fb.1.smali / C0122fb / C0012ab 实现：
 * 拦截 MainResourceManager$TabResponse 反序列化与缓存获取，
 * 在 tabData.tab 列表中注入：
 *  - 追番（大陆）: tabId="50", uri="bilibili://pgc/home", reportId="bangumi", pos=50
 *  - 追番（港澳台）: tabId="60", uri="bilibili://following/home_activity_tab/6544", reportId="bangumi", pos=60
 *
 * 6.6.0 上 TabResponse 路径可能已被 V2 服务取代（HomeTabServiceImplV2.e()/h() 直接返回页签列表），
 * 因此同时挂：
 *  - TabResponse 生产端：CachedResourceResolver.a() / Companion.a() / fastjson parseObject（旧路径 + 兜底）
 *  - TabResponse 注入：只改 tabData.tab，列表对象不渲染时由探针定位
 *  - V2 服务探测：homeTab:v2hit 打印 e()/h() 返回列表的内容摘要（确认顶栏/底栏数据源；
 *    确认后再做列表级注入，避免误注底栏）
 *
 * 探针键：
 *  - homeTab:cache / homeTab:companion / homeTab:json / homeTab:v2 = 挂载结果
 *  - homeTab:hit    = TabResponse 路径真的返回了对象
 *  - homeTab:inject = 注入前后 tab 列表长度 + tab/top/bottom 三列表摘要
 *  - homeTab:v2hit  = V2 服务返回列表的内容（size + tabId:name 前几项）
 *  - homeTab:off    = 命中但开关未开（诊断用）
 *
 * 注入对象用原生 tab 克隆（保留 icon/type/extras 等渲染必需字段），只覆盖五个业务字段。
 *
 * 注意：injectBangumiTabs 保持两参签名——带默认参的合成方法会把 XposedModule
 * 带进签名，而测试 classpath 没有 libxposed（compileOnly），会直接编译不过。
 */
object HomeTabHook {

    private const val TAB_RESPONSE_CLASS = "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$TabResponse"
    private const val TAB_CLASS = "tv.danmaku.bili.ui.main2.resource.MainResourceManager\$Tab"
    private const val CACHED_RESOLVER_CLASS = "tv.danmaku.bili.ui.main2.resource.CachedResourceResolver"
    private const val CACHED_RESOLVER_COMPANION_CLASS = "tv.danmaku.bili.ui.main2.resource.CachedResourceResolver\$Companion"
    private const val V2_SERVICE_CLASS = "tv.danmaku.bili.home.service.HomeTabServiceImplV2"
    private const val FASTJSON_CLASS = "com.alibaba.fastjson.JSON"

    private val installed = AtomicBoolean(false)

    fun install(module: XposedModule, cl: ClassLoader) {
        if (!installed.compareAndSet(false, true)) return
        runCatching {
            var hookedAny = false
            val tabClass = runCatching { Class.forName(TAB_CLASS, false, cl) }.getOrNull()
            if (tabClass == null) {
                HookProbe.miss(module, "homeTab:tabClass", "MainResourceManager\$Tab 不存在，注入将跳过")
            }

            // 1. 实例入口 CachedResourceResolver.a()
            hookedAny = hookResolverMethod(module, cl, CACHED_RESOLVER_CLASS, "homeTab:cache", tabClass) || hookedAny
            // 1b. 伴生静态入口 CachedResourceResolver$Companion.a()（preload 协程路径）
            hookedAny = hookResolverMethod(module, cl, CACHED_RESOLVER_COMPANION_CLASS, "homeTab:companion", tabClass) || hookedAny

            // 1c. 首页 V2 服务：e()/h() 返回 List（顶栏数据源候选），本轮只探测内容
            hookedAny = hookV2Service(module, cl) || hookedAny

            // 2. Hook Fastjson JSON.parseObject(...) returning TabResponse
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
                                applyInjection(module, chain.proceed(), tabClass, "json")
                            }
                        jsonHooked = true
                    }
                }
                if (jsonHooked) HookProbe.ok(module, "homeTab:json", "JSON.parseObject")
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

    /** 挂一个「无参且返回 TabResponse」的入口方法（实例或伴生静态）。 */
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
                            applyInjection(module, chain.proceed(), tabClass, probeKey)
                        }
                    hooked = true
                    HookProbe.ok(module, probeKey, "$className.a")
                }
            }
        }
        return hooked
    }

    /**
     * 首页 V2 服务探测（6.6.0）：HomeTabServiceImplV2.e()/h() 返回 List，
     * 本轮只打印内容摘要（size + 前几项 类名:tabId=name），用于确认首页顶栏/底栏的数据源；
     * 确认后再对目标方法做列表级注入——避免把追番页签误注进底栏。
     */
    private fun hookV2Service(module: XposedModule, cl: ClassLoader): Boolean {
        var hooked = false
        runCatching {
            val cls = Class.forName(V2_SERVICE_CLASS, false, cl)
            for (m in cls.declaredMethods) {
                if (m.name in setOf("e", "h") && m.parameterTypes.isEmpty() && m.returnType == List::class.java) {
                    m.isAccessible = true
                    runCatching { module.deoptimize(m) }
                    module.hook(m)
                        .setPriority(XposedInterface.PRIORITY_DEFAULT)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .intercept { chain ->
                            val res = chain.proceed()
                            (res as? List<*>)?.let { list ->
                                HookProbe.first(module, "homeTab:v2hit", 10) {
                                    val head = list.take(6).map { t ->
                                        if (t == null) {
                                            "?"
                                        } else {
                                            "${t.javaClass.simpleName}:${getFieldSafely(t, "tabId") ?: "?"}=${getFieldSafely(t, "name") ?: "?"}"
                                        }
                                    }
                                    "v2:${m.name} size=${list.size} [${head.joinToString(",")}]"
                                }
                            }
                            res
                        }
                    hooked = true
                    HookProbe.ok(module, "homeTab:v2", "$V2_SERVICE_CLASS.${m.name}")
                }
            }
        }
        return hooked
    }

    /** 对一次 TabResponse 返回值做注入（幂等）；非目标类型原样放行。 */
    private fun applyInjection(
        module: XposedModule,
        res: Any?,
        tabClass: Class<*>?,
        via: String,
    ): Any? {
        if (res != null && res.javaClass.name == TAB_RESPONSE_CLASS) {
            HookProbe.first(module, "homeTab:hit", 6) { "via=$via" }
            val config = UnlockConfig.load(module)
            if (config.enabled && config.addBangumi && tabClass != null) {
                val before = tabCountOf(res)
                injectBangumiTabs(res, tabClass)
                HookProbe.first(module, "homeTab:inject", 6) {
                    "via=$via before=$before after=${tabCountOf(res)} ${listDigest(res)}"
                }
            } else {
                HookProbe.first(module, "homeTab:off", 6) {
                    "via=$via enabled=${config.enabled} add=${config.addBangumi} tabClass=${tabClass != null}"
                }
            }
        }
        return res
    }

    /** 读 tabData.tab 的当前长度（诊断用；取不到返回 -1）。 */
    private fun tabCountOf(tabResponse: Any): Int {
        val tabData = getFieldSafely(tabResponse, "tabData") ?: return -1
        return (getFieldSafely(tabData, "tab") as? List<*>)?.size ?: -1
    }

    /** tab/top/bottom 三个列表的摘要（定位首页顶栏真正消费的列表）。 */
    private fun listDigest(tabResponse: Any): String {
        val tabData = getFieldSafely(tabResponse, "tabData") ?: return "noTabData"
        fun brief(key: String): String {
            val list = getFieldSafely(tabData, key) as? List<*> ?: return "$key=null"
            val items = list.take(6).map { t ->
                if (t == null) return@map "?"
                val id = getFieldSafely(t, "tabId") as? String ?: "?"
                val nm = getFieldSafely(t, "name") as? String ?: "?"
                "$id:$nm"
            }
            return "$key=${list.size}[${items.joinToString(",")}]"
        }
        return brief("tab") + " | " + brief("top") + " | " + brief("bottom")
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
            val tabId = getFieldSafely(tab, "tabId") as? String ?: ""
            val uri = getFieldSafely(tab, "uri") as? String ?: ""
            if (tabId == "50" || uri == "bilibili://pgc/home" || uri == "bilibili://pgc/bangumi_v2") {
                hasMainland = true
            }
            if (tabId == "60" || uri == "bilibili://following/home_activity_tab/6544") {
                hasHkMoTw = true
            }
        }
        if (hasMainland && hasHkMoTw) return false

        val template = rawList.firstOrNull()
        val mutableList = ArrayList(rawList)
        if (!hasMainland) {
            val tab = createTab(
                tabClass = tabClass,
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
                tabClass = tabClass,
                template = template,
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

    /**
     * 造一个 tab：优先克隆原生 [template]（保留 icon/type/extras 等渲染必需字段），
     * 无模板时退回无参构造；随后覆盖五个业务字段。
     */
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
        val ctor = actualClass.declaredConstructors.firstOrNull { it.parameterTypes.isEmpty() }
            ?: actualClass.declaredConstructors.firstOrNull() ?: return null
        ctor.isAccessible = true
        val tab = if (ctor.parameterTypes.isEmpty()) {
            ctor.newInstance()
        } else {
            val params = arrayOfNulls<Any>(ctor.parameterTypes.size)
            ctor.newInstance(*params)
        }
        if (template != null) {
            for (f in actualClass.declaredFields) {
                runCatching {
                    if (java.lang.reflect.Modifier.isStatic(f.modifiers)) return@runCatching
                    f.isAccessible = true
                    f.set(tab, f.get(template))
                }
            }
        }
        setFieldSafely(tab, "tabId", tabId)
        setFieldSafely(tab, "name", name)
        setFieldSafely(tab, "uri", uri)
        setFieldSafely(tab, "reportId", reportId)
        setFieldSafely(tab, "pos", pos)
        tab
    }.getOrNull()

    private fun getFieldSafely(obj: Any, fieldName: String): Any? = runCatching {
        val field = obj.javaClass.declaredFields.firstOrNull { it.name.equals(fieldName, ignoreCase = true) }
        if (field != null) {
            field.isAccessible = true
            return@runCatching field.get(obj)
        }
        val getterName = "get" + fieldName.replaceFirstChar { it.uppercase() }
        val getter = obj.javaClass.methods.firstOrNull { it.name == getterName && it.parameterTypes.isEmpty() }
        getter?.invoke(obj)
    }.getOrNull()

    private fun setFieldSafely(obj: Any, fieldName: String, value: Any?): Boolean = runCatching {
        val field = obj.javaClass.declaredFields.firstOrNull { it.name.equals(fieldName, ignoreCase = true) }
        if (field != null) {
            field.isAccessible = true
            field.set(obj, value)
            return@runCatching true
        }
        val setterName = "set" + fieldName.replaceFirstChar { it.uppercase() }
        val setter = obj.javaClass.methods.firstOrNull { it.name == setterName && it.parameterTypes.size == 1 }
        if (setter != null) {
            setter.invoke(obj, value)
            return@runCatching true
        }
        false
    }.getOrDefault(false)
}

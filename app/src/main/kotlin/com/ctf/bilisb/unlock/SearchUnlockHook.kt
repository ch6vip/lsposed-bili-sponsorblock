package com.ctf.bilisb.unlock

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 区域搜索：nav/type=810 + 独立页面参数，保留原生番剧(7)/影视(8)请求。
 * 协议和宿主路由证据见 docs/SEARCH_UNLOCK_PLAN.md。
 */
// Note: 路由隔离、错误回调及下载边界见 .agents/notes/implemented/bug-fix/2026-10-05-unlock-boundaries.md
object SearchUnlockHook {

    private const val MOSS_CLASS = "com.bapis.bilibili.polymer.app.search.v1.SearchMoss"
    private const val ALL_RESP_CLASS = "com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse"
    private const val BYTYPE_RESP_CLASS =
        "com.bapis.bilibili.polymer.app.search.v1.SearchByTypeResponse"

    const val MARKER_TYPE = SearchRequestPolicy.MARKER_TYPE
    private val routeReady = java.util.concurrent.atomic.AtomicBoolean(false)

    private const val MAX_RETRY = 20
    private const val RETRY_DELAY_MS = 1000L

    private val attempts = AtomicInteger(0)
    private val enumInjected = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 搜索替换的专用单线程池：moss 调用线程不阻塞，响应经 handler 异步回喂。 */
    private val executor by lazy {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "BiliSB-SearchUnlock").apply { isDaemon = true }
        }
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (attempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "search:unlock", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            val moss = Class.forName(MOSS_CLASS, false, cl)
            val hooked = mutableListOf<String>()
            for (name in listOf("searchAll", "searchByType")) {
                for (m in moss.declaredMethods.filter {
                        it.name == name && it.parameterTypes.size == 2
                    }) {
                    runCatching { m.isAccessible = true }
                    runCatching { module.deoptimize(m) }
                    if (name == "searchAll") hookSearchAll(module, cl, m) else hookSearchByType(module, cl, m)
                    hooked += m.name
                }
            }
            if (hooked.isEmpty()) {
                HookProbe.first(module, "search:unlockRetry", 3) {
                    "method not found yet, attempt=${attempts.get()}"
                }
                retry(module, cl)
                return
            }
            HookProbe.ok(module, "search:unlock", hooked.joinToString(", "))
            if (installPageRoute(module, cl)) injectPageTypeEnum(module, cl)
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "search:unlockRetry", 3) {
                "class not loaded yet, attempt=${attempts.get()}"
            }
            retry(module, cl)
        } catch (t: Throwable) {
            HookProbe.miss(module, "search:unlock", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun retry(module: XposedModule, cl: ClassLoader) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
        }
    }

    /** 6.6.0：Fragment 从 arguments.type 读 c0，loadData 将 c0 传给搜索请求。 */
    private fun installPageRoute(module: XposedModule, cl: ClassLoader): Boolean = runCatching {
        val cls = cl.loadClass(HostTargets.SEARCH_OGV_FRAGMENT_CLASS)
        val method = cls.getDeclaredMethod("onCreate", android.os.Bundle::class.java)
        method.isAccessible = true
        runCatching { module.deoptimize(method) }
        module.hook(method)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val fragment = chain.thisObject
                val args = fragment?.javaClass?.getMethod("getArguments")?.invoke(fragment) as? android.os.Bundle
                if (args != null && markRegionalPage(args)) {
                    HookProbe.first(module, "search:pageMarked", 3) { "page request type=$MARKER_TYPE" }
                }
                chain.proceed()
            }
        HookProbe.ok(module, "search:pageRoute", "OgvSearchResultFragment.onCreate")
        true
    }.getOrElse {
        HookProbe.miss(module, "search:pageRoute", "${it.javaClass.simpleName}: ${it.message}")
        false
    }

    internal fun markRegionalPage(arguments: android.os.Bundle): Boolean {
        if (arguments.getString(SearchRequestPolicy.ROUTE_MARKER) != "1") return false
        val area = arguments.getString("area")
        val markerType = if (area == "th") SearchRequestPolicy.MARKER_TYPE_TH else MARKER_TYPE
        arguments.putString("type", markerType.toString())
        return true
    }

    /** nav 的 type 必须在宿主页面枚举中有对应项，URI 上的私有参数区分页面来源。 */
    private fun injectPageTypeEnum(module: XposedModule, cl: ClassLoader) {
        if (!enumInjected.compareAndSet(false, true)) return
        runCatching {
            val cls = cl.loadClass("com.bilibili.search2.result.pages.BiliMainSearchResultPage\$PageTypes")
            val valuesField = cls.getDeclaredField("\$VALUES")
            valuesField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val old = valuesField.get(null) as Array<Any>
            val typeOf: (Any) -> Int = { e ->
                runCatching { cls.getMethod("getPageType").invoke(e) as? Int }.getOrDefault(null) ?: 0
            }
            if (old.any { typeOf(it) == MARKER_TYPE }) {
                routeReady.set(true)
                HookProbe.first(module, "search:enumInjected", 1) { "already present" }
                return
            }
            val ctor = cls.getDeclaredConstructor(
                String::class.java, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType, String::class.java,
            )
            ctor.isAccessible = true
            val addedTw = ctor.newInstance(
                "PAGE_TW_UNLOCK", old.size,
                "bilibili://search-result/new-bangumi?${SearchRequestPolicy.ROUTE_MARKER}=1&area=tw", MARKER_TYPE, "bangumi",
            )
            val addedTh = ctor.newInstance(
                "PAGE_TH_UNLOCK", old.size + 1,
                "bilibili://search-result/new-bangumi?${SearchRequestPolicy.ROUTE_MARKER}=1&area=th", SearchRequestPolicy.MARKER_TYPE_TH, "bangumi",
            )
            val newArr = java.lang.reflect.Array.newInstance(cls, old.size + 2) as Array<Any>
            System.arraycopy(old, 0, newArr, 0, old.size)
            newArr[old.size] = addedTw
            newArr[old.size + 1] = addedTh
            synchronized(cls) {
                valuesField.set(null, newArr)
                // $ENTRIES（kotlin EnumEntries）：经 kotlin.enums.a.a(Array) 工厂重建
                runCatching {
                    val entriesField = cls.getDeclaredField("\$ENTRIES")
                    entriesField.isAccessible = true
                    val factory = cl.loadClass("kotlin.enums.a")
                    val m = factory.declaredMethods.firstOrNull {
                        it.name == "a" && it.parameterTypes.size == 1 && it.parameterTypes[0].isArray
                    }
                    m?.isAccessible = true
                    if (m != null) entriesField.set(null, m.invoke(null, newArr))
                }
                // 清除 Class 的 enumConstants / enumConstantDirectory 缓存
                runCatching {
                    val enumConstantsField = Class::class.java.getDeclaredField("enumConstants")
                    enumConstantsField.isAccessible = true
                    enumConstantsField.set(cls, null)
                }
                runCatching {
                    val enumDirectoryField = Class::class.java.getDeclaredField("enumConstantDirectory")
                    enumDirectoryField.isAccessible = true
                    enumDirectoryField.set(cls, null)
                }
            }
            routeReady.set(true)
            HookProbe.ok(module, "search:enumInjected", "PageTypes ${old.size}+2 type=$MARKER_TYPE,${SearchRequestPolicy.MARKER_TYPE_TH}")
        }.onFailure { t ->
            HookProbe.miss(
                module, "search:enumInject",
                "${t.javaClass.simpleName}: ${t.message}",
            )
        }
    }

    // ---------------------------------------------------------------- S2：searchAll 页签注入

    private fun hookSearchAll(module: XposedModule, cl: ClassLoader, m: Method) {
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (chain.args.size < 2 || chain.args[1] == null) {
                    return@intercept chain.proceed(chain.args.toTypedArray())
                }
                val handler = chain.args[1]
                val wrapped = Proxy.newProxyInstance(
                    cl,
                    handler.javaClass.interfaces,
                    { _, method, args ->
                        if (method.name == "onNext" && args != null && args.isNotEmpty()) {
                            args[0] = injectAreaNav(module, cl, args[0])
                        }
                        method.invoke(handler, *(args ?: emptyArray()))
                    },
                )
                chain.proceed(arrayOf(chain.args[0], wrapped))
            }
    }

    /** nav 注入：字节级拼接（原响应零损），宿主类 parseFrom 回喂。失败放行原对象。 */
    private fun injectAreaNav(module: XposedModule, cl: ClassLoader, reply: Any?): Any? {
        if (reply == null) return null
        val config = UnlockConfig.load(module)
        if (!config.enabled || !config.searchEnabled || !routeReady.get()) return reply
        val itemsToInject = mutableListOf<Pair<String, Int>>()
        val hasHkTw = config.servers.any { it.area in setOf("hk", "tw") }
        val hasTh = config.servers.any { it.area == "th" }
        if (hasHkTw) itemsToInject.add("港澳台" to MARKER_TYPE)
        if (hasTh) itemsToInject.add("东南亚" to SearchRequestPolicy.MARKER_TYPE_TH)
        if (itemsToInject.isEmpty()) {
            val area = config.servers.firstOrNull()?.area ?: return reply
            if (area != "cn") itemsToInject.add(areaLabel(area) to MARKER_TYPE)
        }
        if (itemsToInject.isEmpty()) return reply

        return runCatching {
            val bytes = reply.javaClass.methods
                .firstOrNull { it.name == "toByteArray" && it.parameterTypes.isEmpty() }
                ?.invoke(reply) as? ByteArray ?: return@runCatching reply
            val spliced = spliceAreaNavs(bytes, itemsToInject)
                ?: return@runCatching reply
            val rebuilt = cl.loadClass(ALL_RESP_CLASS)
                .getMethod("parseFrom", ByteArray::class.java).invoke(null, spliced)
            HookProbe.first(module, "search:navInjected", 3) {
                "nav+${itemsToInject.size} (${bytes.size}B -> ${spliced.size}B)"
            }
            rebuilt
        }.onFailure { t ->
            HookProbe.first(module, "search:navFail", 2) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
        }.getOrDefault(reply)
    }

    /**
     * 把「{area}」页签插到第一个原生 nav 之后；已含标记 type 时返回 null（幂等）。
     * Nav{name=1,total=2,pages=3,type=4}（实拍反解：番剧={1:'番剧',2:2,3:1,4:7}）。
     */
    fun spliceAreaNav(bytes: ByteArray, label: String, markerType: Int): ByteArray? {
        return spliceAreaNavs(bytes, listOf(label to markerType))
    }

    fun spliceAreaNavs(bytes: ByteArray, items: List<Pair<String, Int>>): ByteArray? {
        if (items.isEmpty()) return null
        val elems = WireSplice.parse(bytes)
        var navSeen = 0
        var insertedCount = 0
        val out = mutableListOf<WireSplice.Elem>()
        val existingTypes = mutableSetOf<Long>()
        val itemTypes = items.map { it.second.toLong() }.toSet()
        for (e in elems) {
            if (e.field == 3 && e.wireType == 2) {
                val nav = WireSplice.parse(e.payload())
                val type = nav.firstOrNull { it.field == 4 && it.wireType == 0 }?.varint() ?: 0
                existingTypes.add(type)
            }
        }
        val remaining = items.filter { it.second.toLong() !in existingTypes }
        if (remaining.isEmpty()) return null // 已注入（幂等）

        val hasAnyExistingRegional = items.any { it.second.toLong() in existingTypes }
        for (e in elems) {
            out.add(e)
            if (e.field == 3 && e.wireType == 2) {
                navSeen++
                val nav = WireSplice.parse(e.payload())
                val type = nav.firstOrNull { it.field == 4 && it.wireType == 0 }?.varint() ?: 0
                val shouldInsertHere = if (hasAnyExistingRegional) {
                    type in itemTypes
                } else {
                    navSeen == 1
                }
                if (shouldInsertHere && insertedCount == 0) {
                    for ((lbl, mType) in remaining) {
                        out.add(WireSplice.Elem(3, 2, WireSplice.message(3, navBytes(lbl, mType))))
                        insertedCount++
                    }
                }
            }
        }
        if (insertedCount == 0 && remaining.isNotEmpty()) {
            for ((lbl, mType) in remaining) {
                out.add(WireSplice.Elem(3, 2, WireSplice.message(3, navBytes(lbl, mType))))
            }
        }
        return WireSplice.emit(out)
    }

    /**
     * Nav wire：name=1(str) total=2(varint) pages=3(varint) type=4(varint)。
     * total/pages 给 1（原生番剧 nav 是 total=2/pages=1；0 值会被 WireWriter 省略，
     * 曾疑似导致宿主把页签当空页签不发请求——2026-10-05 S3 首轮真机）。
     */
    fun navBytes(label: String, type: Int): ByteArray {
        val w = WireWriter()
        w.stringField(1, label)
        w.int64Field(2, 1)
        w.int64Field(3, 1)
        w.int64Field(4, type.toLong())
        return w.toByteArray()
    }

    private fun areaLabel(area: String): String = when (area) {
        "tw" -> "台"
        "hk" -> "港"
        "th" -> "泰"
        else -> "陆"
    }

    // ---------------------------------------------------------------- S3：searchByType 替换

    private fun hookSearchByType(module: XposedModule, cl: ClassLoader, m: Method) {
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val req = chain.args.getOrNull(0)
                val handler = chain.args.getOrNull(1)
                if (req == null || handler == null) return@intercept chain.proceed()
                val reqType = req.javaClass.getMethod("getType").invoke(req) as Int
                val config = UnlockConfig.load(module)
                val targetArea = if (reqType == SearchRequestPolicy.MARKER_TYPE_TH) "th" else "tw"
                val relevantArea = config.servers.firstOrNull {
                    if (targetArea == "th") it.area == "th" else it.area in setOf("tw", "hk")
                }?.area ?: config.servers.firstOrNull()?.area.orEmpty()
                val route = SearchRequestPolicy.route(
                    reqType, config.enabled, config.searchEnabled,
                    config.servers.isNotEmpty(), relevantArea,
                )
                if (route == SearchRequestPolicy.Route.ORIGINAL) return@intercept chain.proceed()
                if (route == SearchRequestPolicy.Route.NATIVE_BANGUMI) {
                    val builder = req.javaClass.getMethod("newBuilder", req.javaClass).invoke(null, req)
                    builder.javaClass.getMethod("setType", Int::class.javaPrimitiveType).invoke(builder, 7)
                    val native = builder.javaClass.getMethod("build").invoke(builder)
                    return@intercept chain.proceed(arrayOf(native, handler))
                }
                // 真正属于区域页的请求才转服务器；页码由本页响应游标往返。
                val keyword = req.javaClass.getMethod("getKeyword").invoke(req) as String
                executor.submit {
                    try {
                        val pagination = req.javaClass.getMethod("getPagination").invoke(req)
                        val next = pagination.javaClass.getMethod("getNext").invoke(pagination) as String
                        val size = pagination.javaClass.getMethod("getPageSize").invoke(pagination) as Int
                        val page = SearchRequestPolicy.page(next, size)
                        HookProbe.first(module, "search:markerHit", 6) { "type=$reqType page=${page.number} targetArea=$targetArea" }
                        val bytes = fetchServerSearch(config, keyword, page, targetArea)
                        val resp = parseHost(module, cl, BYTYPE_RESP_CLASS, bytes)
                            ?: error("Cannot rebuild search response")
                        invokeHandler(handler, "onNext", resp)
                        invokeHandler(handler, "onCompleted")
                        HookProbe.first(module, "search:rebuilt", 6) { "page=${page.number} ${bytes.size}B" }
                    } catch (failure: Exception) {
                        feedError(module, cl, handler, failure)
                    }
                }
                null
            }
    }

    private fun fetchServerSearch(
        config: UnlockConfig.Config,
        keyword: String,
        page: SearchRequestPolicy.Page,
        targetArea: String = "tw",
    ): ByteArray {
        val server = if (targetArea == "th") {
            config.servers.firstOrNull { it.area == "th" } ?: config.servers.firstOrNull()
        } else {
            config.servers.firstOrNull { it.area in setOf("tw", "hk") } ?: config.servers.firstOrNull()
        } ?: throw IllegalStateException("未配置可用的解析服务器")
        val area = if (targetArea == "th") "th" else if (server.area in setOf("tw", "hk")) server.area else "tw"
        val accessKey = server.accessKey.ifBlank { HostAccessKey.lastSeen() } ?: ""
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        var query = "keyword=$encoded&type=7&area=$area&pn=${page.number}&ps=${page.size}"
        if (accessKey.isNotEmpty()) query += "&access_key=$accessKey"
        val thailand = area == "th"
        if (thailand) query += "&s_locale=zh_SG&c_locale=zh_SG&sim_code=52004&lang=hans"
        val path = if (thailand) "/intl/gateway/v2/app/search/type" else "/x/v2/search/type"
        val signed = HostSigner.sign(area, query, emptyMap())
        val body = PlayViewHook.defaultFetch(
            server.baseUrl.trimEnd('/') + path + "?" + signed,
            if (thailand) "bstar_a" else "android", timeoutMs = 5000,
        )
        return buildSearchResponseBytes(keyword, body, page)
    }

    /**
     * 服务端 JSON → SearchByTypeResponse wire。
     * 结构对照实拍模板（TONIKAWA 393KB）：响应{trackid=1,pages=2,keyword=4,items=6}；
     * Item{param=2,goto=3,linktype=4,trackid=6,番剧卡=38}；
     * 卡体{title=1,cover=2,area=5,style=6,styles=7,ptime=14,类型名=15,badge=32,追番=30,集网格=26,grid=28}。
     */
    internal fun buildSearchResponseBytes(
        keyword: String,
        body: String,
        page: SearchRequestPolicy.Page = SearchRequestPolicy.Page(1, 20),
    ): ByteArray {
        val json = org.json.JSONObject(body)
        val code = json.optInt("code", -1)
        check(code == 0) { "server code=$code ${json.optString("message").take(80)}" }
        val data = json.optJSONObject("data") ?: org.json.JSONObject()
        val w = WireWriter()
        w.stringField(1, randomTrackid())
        val pages = data.optLong("pages", 0)
        if (pages > 0) w.int64Field(2, pages)
        w.stringField(4, keyword)
        // SearchByTypeResponse.page=10 / pagination=7；PaginationReply.next=1, prev=2。
        w.int64Field(10, page.number.toLong())
        val cursor = WireWriter()
        if (page.number < pages) cursor.stringField(1, (page.number + 1).toString())
        if (page.number > 1) cursor.stringField(2, (page.number - 1).toString())
        w.messageField(7, cursor.toByteArray())
        val recommended = data.optInt("result_is_recommend", 0)
        if (recommended != 0) w.int64Field(5, recommended.toLong())
        val items = data.optJSONArray("items")
        if (items != null) {
            for (i in 0 until items.length()) {
                val card = items.optJSONObject(i) ?: continue
                if (card.optString("goto") != "bangumi") continue
                val itemBytes = buildBangumiItem(card) ?: continue
                w.messageField(6, itemBytes)
            }
        }
        return w.toByteArray()
    }

    /** Item 包装层：{uri=1, param=2, goto=3, linktype=4, trackid=6, 卡体=38}（实拍形状）。 */
    private fun buildBangumiItem(card: org.json.JSONObject): ByteArray? {
        val param = card.optString("param")
        if (param.isEmpty()) return null
        val w = WireWriter()
        // uri：影视页点击分发不认 goto=bangumi 的卡（S3 二轮真机：渲染✅点击✗）——
        // 补显式路由 uri，点击走 uri 而非 goto+param 分发（P0 深链同款路由）。
        card.optLong("season_id", 0).takeIf { it > 0 }?.let {
            w.stringField(1, "bilibili://bangumi/season/$it")
        }
        w.stringField(2, param)
        w.stringField(3, "bangumi")
        w.stringField(4, "media_bangumi")
        card.optString("trackid").takeIf { it.isNotEmpty() }?.let { w.stringField(6, it) }
        w.messageField(38, buildBangumiCard(card))
        return w.toByteArray()
    }

    /** 番剧卡体（field 38）。标题剥 <em> 高亮标签（intl 原生响应不带，宿主按字面渲染）。 */
    private fun buildBangumiCard(card: org.json.JSONObject): ByteArray {
        val w = WireWriter()
        w.stringField(1, stripEm(card.optString("title")))
        card.optString("cover").takeIf { it.isNotEmpty() }?.let { w.stringField(2, it) }
        card.optString("area").takeIf { it.isNotEmpty() }?.let { w.stringField(5, it) }
        card.optString("style").takeIf { it.isNotEmpty() }?.let { w.stringField(6, it) }
        card.optString("styles").takeIf { it.isNotEmpty() }?.let { w.stringField(7, it) }
        val ptime = card.optLong("ptime", 0)
        if (ptime > 0) w.int64Field(14, ptime)
        card.optString("season_type_name").takeIf { it.isNotEmpty() }?.let { w.stringField(15, it) }
        // badge（实拍 32：text/text_color/text_color_night/bg_color/bg_color_night/bg_style）
        val badges = card.optJSONArray("badges_v2")
        if (badges != null && badges.length() > 0) {
            val b = badges.optJSONObject(0)
            if (b != null) {
                val bw = WireWriter()
                bw.stringField(1, b.optString("text"))
                bw.stringField(2, b.optString("text_color"))
                bw.stringField(3, b.optString("text_color_night"))
                bw.stringField(4, b.optString("bg_color"))
                bw.stringField(5, b.optString("bg_color_night"))
                bw.int64Field(8, b.optInt("bg_style", 1).toLong())
                w.messageField(32, bw.toByteArray())
            }
        }
        // 追番按钮（实拍 30：states[{state,text}] + goto；两态文案宿主按关注态取用）
        val fw = WireWriter()
        val s0 = WireWriter(); s0.stringField(1, "0"); s0.stringField(2, "追番")
        val s1 = WireWriter(); s1.stringField(1, "1"); s1.stringField(2, "已追番")
        fw.messageField(2, s0.toByteArray())
        fw.messageField(2, s1.toByteArray())
        fw.stringField(3, "bangumi")
        w.messageField(30, fw.toByteArray())
        // 集网格（实拍 26：{序号=1,uri=2,ep_id=3,position=7,badge=4}，折叠形态最多 6 格）
        val eps = card.optJSONArray("episodes") ?: card.optJSONArray("episodes_new")
        if (eps != null) {
            for (i in 0 until eps.length().coerceAtMost(6)) {
                val ep = eps.optJSONObject(i) ?: continue
                val epId = ep.optString("param")
                if (epId.isEmpty()) continue
                val ew = WireWriter()
                val epTitle = ep.optString("index").ifEmpty { ep.optString("title", (i + 1).toString()) }
                ew.stringField(1, epTitle)
                val uri = ep.optString("uri").ifEmpty {
                    "https://www.bilibili.com/bangumi/play/ep$epId"
                }
                ew.stringField(2, uri)
                ew.stringField(3, epId)
                ew.int64Field(7, ep.optInt("position", i + 1).toLong())
                val epBadges = ep.optJSONArray("badges") ?: ep.optJSONArray("badges_v2")
                if (epBadges != null && epBadges.length() > 0) {
                    val b = epBadges.optJSONObject(0)
                    if (b != null) {
                        val bw = WireWriter()
                        bw.stringField(1, b.optString("text"))
                        bw.stringField(2, b.optString("text_color"))
                        bw.stringField(3, b.optString("text_color_night"))
                        bw.stringField(4, b.optString("bg_color"))
                        bw.stringField(5, b.optString("bg_color_night"))
                        bw.int64Field(8, b.optInt("bg_style", 1).toLong())
                        ew.messageField(4, bw.toByteArray())
                    }
                }
                w.messageField(26, ew.toByteArray())
            }
        }
        card.optString("selection_style").takeIf { it.isNotEmpty() }?.let { w.stringField(28, it) }
        return w.toByteArray()
    }

    /** 剥 B 站搜索高亮 <em> 标签（服务端原样透传上游，宿主 intl 卡按字面渲染）。 */
    private fun stripEm(s: String): String = s.replace(Regex("</?em[^>]*>"), "")

    private fun randomTrackid(): String =
        String.format(
            java.util.Locale.US,
            "%019d",
            java.util.concurrent.ThreadLocalRandom.current().nextLong(1_000_000_000_000_000_000L, Long.MAX_VALUE),
        )

    // ---------------------------------------------------------------- 回喂基建

    private fun parseHost(module: XposedModule, cl: ClassLoader, clsName: String, bytes: ByteArray): Any? =
        runCatching {
            cl.loadClass(clsName).getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)
        }.onFailure { t ->
            HookProbe.first(module, "search:parseFail", 2) {
                "$clsName: ${t.javaClass.simpleName}: ${t.message}"
            }
        }.getOrNull()

    private fun invokeHandler(handler: Any, name: String, arg: Any? = null) {
        val method = handler.javaClass.methods.firstOrNull {
            it.name == name && it.parameterTypes.size == (if (arg == null) 0 else 1)
        } ?: throw NoSuchMethodException("Missing callback $name")
        method.isAccessible = true
        if (arg == null) method.invoke(handler) else method.invoke(handler, arg)
    }

    private val lastErrorAt = java.util.concurrent.atomic.AtomicLong(0)

    /** 失败是失败：交给宿主错误态，不再伪造空结果或继续发送 onCompleted。 */
    private fun feedError(module: XposedModule, cl: ClassLoader, handler: Any, failure: Exception) {
        HookProbe.first(module, "search:fetchFail", 5) { failure.javaClass.simpleName }
        runCatching {
            val error = cl.loadClass(HostTargets.MOSS_EXCEPTION_CLASS)
                .getConstructor(String::class.java, Throwable::class.java)
                .newInstance("Regional search failed", failure)
            invokeHandler(handler, "onError", error)
        }.onFailure {
            HookProbe.first(module, "search:errorCallbackFailed", 3) { it.javaClass.simpleName }
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = lastErrorAt.get()
        if (previous != 0L && now - previous < 30_000) return
        if (!lastErrorAt.compareAndSet(previous, now)) return
        runCatching {
            val context = cl.loadClass("android.app.ActivityThread").getMethod("currentApplication")
                .invoke(null) as? android.content.Context ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(
                    context,
                    com.ctf.bilisb.ui.ModuleStrings.get(context, com.ctf.bilisb.R.string.toast_search_unlock_failed),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

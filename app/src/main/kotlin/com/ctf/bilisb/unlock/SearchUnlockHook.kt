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
 * 搜索解锁（S 线 S2+S3，docs/SEARCH_UNLOCK_PLAN.md）。
 *
 * S1 实证：intl 6.6.0 搜索走非 K javalite `polymer.app.search.v1.SearchMoss`
 * （searchAll/searchByType + MossResponseHandler），且**原生搜索过滤受限标题**
 * （Kaguya 全空 / TONIKAWA 正常）。本钩做两件事，其余请求零改动：
 *
 *  - **S2 页签注入**：searchAll 响应的 nav（field 3）里插一个「台」页签
 *    （Nav{name,total,pages,type=810}，810 沿用 BiliRoaming tw×bangumi 标记惯例）；
 *  - **S3 搜索替换**：searchByType 请求 type==810 时**短路宿主 RPC**，改向解析服务器
 *    `/x/v2/search/type?keyword=..&type=7&area=tw`（appsign 本地签名，服务端现成路由）
 *    取全量台区结果，按宿主实拍模板（TONIKAWA 393KB 黄金样本）的字段号重建
 *    `SearchByTypeResponse` 回喂 handler。点击卡片进详情 → 既有 ViewTab/漫游链兜底。
 *
 * wire 依据（runtime *_FIELD_NUMBER dump + 实拍字节解析，计划文档 §4）：
 *  Item{uri=1,param=2,goto=3,linktype=4,trackid=6,bangumi卡=38}
 *  卡体(38){title=1,cover=2,area=5,style=6,styles=7,ptime=14,season_type_name=15,
 *          badge=32,badge2=24/31,集网格=26,selection_style=28,追番按钮=30}
 *  集(26){序号=1,uri=2,ep_id=3,position=5/7}
 *  Badge(32){text=1,text_color=2,text_color_night=3,bg_color=4,bg_color_night=5,bg_style=8}
 *
 * 失败语义：页签注入失败放行原响应；搜索替换失败回喂空响应（页面显示「暂无搜索结果」），
 * 探针留名。开关：unlock_enabled + unlock_search（默认关）。
 */
object SearchUnlockHook {

    private const val MOSS_CLASS = "com.bapis.bilibili.polymer.app.search.v1.SearchMoss"
    private const val ALL_RESP_CLASS = "com.bapis.bilibili.polymer.app.search.v1.SearchAllResponse"
    private const val BYTYPE_RESP_CLASS =
        "com.bapis.bilibili.polymer.app.search.v1.SearchByTypeResponse"

    /** 注入页签的标记 type（BiliRoaming tw×bangumi 惯例值，宿主原生不发送）。 */
    const val MARKER_TYPE = 810

    /** 原生番剧搜索的真实 type（S1 请求 wire 实测）。 */
    private const val NATIVE_BANGUMI_TYPE = 7

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
            injectPageTypeEnum(module, cl)
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

    /**
     * S2 前置：向宿主 `BiliMainSearchResultPage$PageTypes` 枚举追加 PAGE_TW_UNLOCK
     * （pageType=810，路由复用番页 + from=tw 标记）。S3 首轮真机实证：nav 注入的页签
     * 能渲染（页签行由 nav 驱动）但点击被宿主按 type 查枚举，未知 type 回落综合搜索
     * （searchAll from_source=app_count）——枚举是点击→请求的分发注册表，必须补位。
     * 枚举是 kotlinx 形态：$VALUES 与 $ENTRIES（kotlin.enums.a.a 工厂）都要更新。
     */
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
                HookProbe.first(module, "search:enumInjected", 1) { "already present" }
                return
            }
            val ctor = cls.getDeclaredConstructor(
                String::class.java, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType, String::class.java,
            )
            ctor.isAccessible = true
            val added = ctor.newInstance(
                "PAGE_TW_UNLOCK", old.size,
                "bilibili://search-result/new-bangumi?from=tw", MARKER_TYPE, "bangumi",
            )
            val newArr = java.lang.reflect.Array.newInstance(cls, old.size + 1) as Array<Any>
            System.arraycopy(old, 0, newArr, 0, old.size)
            newArr[old.size] = added
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
            HookProbe.ok(module, "search:enumInjected", "PageTypes ${old.size}+1 type=$MARKER_TYPE")
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
        if (!config.enabled || !config.searchEnabled) return reply
        val area = config.servers.firstOrNull()?.area ?: return reply
        if (area == "cn") return reply // 原生即本区，无需页签
        return runCatching {
            val bytes = reply.javaClass.methods
                .firstOrNull { it.name == "toByteArray" && it.parameterTypes.isEmpty() }
                ?.invoke(reply) as? ByteArray ?: return@runCatching reply
            val spliced = spliceAreaNav(bytes, areaLabel(area), MARKER_TYPE)
                ?: return@runCatching reply
            val rebuilt = cl.loadClass(ALL_RESP_CLASS)
                .getMethod("parseFrom", ByteArray::class.java).invoke(null, spliced)
            HookProbe.first(module, "search:navInjected", 3) {
                "nav+1 type=$MARKER_TYPE label=${areaLabel(area)} (${bytes.size}B -> ${spliced.size}B)"
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
        val elems = WireSplice.parse(bytes)
        var navSeen = 0
        var inserted = false
        val out = mutableListOf<WireSplice.Elem>()
        for (e in elems) {
            out.add(e)
            if (e.field == 3 && e.wireType == 2) {
                navSeen++
                val nav = WireSplice.parse(e.payload())
                val type = nav.firstOrNull { it.field == 4 && it.wireType == 0 }?.varint() ?: 0
                if (type == markerType.toLong()) return null // 已注入（幂等）
                if (navSeen == 1) {
                    out.add(WireSplice.Elem(3, 2, WireSplice.message(3, navBytes(label, markerType))))
                    inserted = true
                }
            }
        }
        if (!inserted) out.add(WireSplice.Elem(3, 2, WireSplice.message(3, navBytes(label, markerType))))
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
                if (req == null || handler == null) {
                    return@intercept chain.proceed(chain.args.toTypedArray())
                }
                val reqType = runCatching {
                    req.javaClass.methods.firstOrNull { it.name == "getType" }?.invoke(req) as? Int
                }.getOrNull()
                if (reqType != MARKER_TYPE) {
                    return@intercept chain.proceed(chain.args.toTypedArray())
                }
                // 短路宿主 RPC：异步取数回喂（moss handler 本就是异步回调，不阻塞调用线程）
                val config = UnlockConfig.load(module)
                val keyword = runCatching {
                    req.javaClass.methods.firstOrNull { it.name == "getKeyword" }?.invoke(req) as? String
                }.getOrDefault("") ?: ""
                if (!config.enabled || !config.searchEnabled || config.servers.isEmpty()) {
                    HookProbe.first(module, "search:replacedOff", 2) { "标记页签但开关关/无服务器，回空" }
                    feedEmpty(module, cl, handler, keyword)
                    return@intercept null
                }
                HookProbe.first(module, "search:markerHit", 4) { "type=$MARKER_TYPE keyword=$keyword" }
                executor.submit {
                    val bytes = runCatching {
                        fetchServerSearch(module, config, keyword)
                    }.onFailure { t ->
                        HookProbe.first(module, "search:fetchFail", 3) {
                            "${t.javaClass.simpleName}: ${t.message}"
                        }
                    }.getOrNull()
                    val resp = if (bytes != null) {
                        parseHost(module, cl, BYTYPE_RESP_CLASS, bytes)
                    } else {
                        null
                    }
                    if (resp != null) {
                        HookProbe.first(module, "search:rebuilt", 4) { "${bytes!!.size}B 回喂" }
                        invokeHandler(handler, "onNext", resp)
                        invokeHandler(handler, "onCompleted")
                    } else {
                        feedEmpty(module, cl, handler, keyword)
                    }
                }
                null
            }
    }

    /** 服务端搜索：/x/v2/search/type?keyword&type=7&area=..（appsign 本地签名，不依赖宿主）。 */
    private fun fetchServerSearch(
        module: XposedModule,
        config: UnlockConfig.Config,
        keyword: String,
    ): ByteArray {
        val server = config.servers.first()
        val area = server.area.ifBlank { "tw" }
        val accessKey = server.accessKey.ifBlank { HostAccessKey.lastSeen() } ?: ""
        // 注意：keyword 进签名串前须 URL 编码——签名按「编码后的查询串」算（服务端校验原样）
        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8")
            .replace("+", "%20")
        var query = "keyword=$encoded&type=$NATIVE_BANGUMI_TYPE&area=$area&build=6400000&pn=1&ps=20"
        if (accessKey.isNotEmpty()) query += "&access_key=$accessKey"
        val signed = HostSigner.sign(area, query, emptyMap())
        val url = server.baseUrl.trimEnd('/') + "/x/v2/search/type?" + signed
        android.util.Log.w("Bili2233URL", "SEARCH $url")
        val body = PlayViewHook.defaultFetch(url, "android", timeoutMs = 8000)
        return buildSearchResponseBytes(keyword, body)
    }

    /**
     * 服务端 JSON → SearchByTypeResponse wire。
     * 结构对照实拍模板（TONIKAWA 393KB）：响应{trackid=1,pages=2,keyword=4,items=6}；
     * Item{param=2,goto=3,linktype=4,trackid=6,番剧卡=38}；
     * 卡体{title=1,cover=2,area=5,style=6,styles=7,ptime=14,类型名=15,badge=32,追番=30,集网格=26,grid=28}。
     */
    fun buildSearchResponseBytes(keyword: String, body: String): ByteArray {
        val json = org.json.JSONObject(body)
        val code = json.optInt("code", -1)
        check(code == 0) { "server code=$code ${json.optString("message").take(80)}" }
        val data = json.optJSONObject("data") ?: org.json.JSONObject()
        val w = WireWriter()
        w.stringField(1, randomTrackid())
        val pages = data.optLong("pages", 0)
        if (pages > 0) w.int64Field(2, pages)
        w.stringField(4, keyword)
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

    /** Item 包装层：{param=2, goto=3, linktype=4, trackid=6, 卡体=38}（实拍形状）。 */
    private fun buildBangumiItem(card: org.json.JSONObject): ByteArray? {
        val param = card.optString("param")
        if (param.isEmpty()) return null
        val w = WireWriter()
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
        // 集网格（实拍 26：{序号=1,uri=2,ep_id=3,position=7}，折叠形态最多 6 格）
        val eps = card.optJSONArray("episodes_new")
        if (eps != null) {
            for (i in 0 until eps.length().coerceAtMost(6)) {
                val ep = eps.optJSONObject(i) ?: continue
                val epId = ep.optString("param")
                if (epId.isEmpty()) continue
                val ew = WireWriter()
                ew.stringField(1, ep.optString("title", (i + 1).toString()))
                val uri = ep.optString("uri").ifEmpty {
                    "https://www.bilibili.com/bangumi/play/ep$epId"
                }
                ew.stringField(2, uri)
                ew.stringField(3, epId)
                ew.int64Field(7, ep.optInt("position", i + 1).toLong())
                w.messageField(26, ew.toByteArray())
            }
        }
        card.optString("selection_style").takeIf { it.isNotEmpty() }?.let { w.stringField(28, it) }
        return w.toByteArray()
    }

    /** 剥 B 站搜索高亮 <em> 标签（服务端原样透传上游，宿主 intl 卡按字面渲染）。 */
    private fun stripEm(s: String): String = s.replace(Regex("</?em[^>]*>"), "")

    private fun randomTrackid(): String =
        (1..19).joinToString("") { (0..9).random().toString() }

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
        runCatching {
            val m = handler.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == (if (arg == null) 0 else 1)
            } ?: return
            m.isAccessible = true
            if (arg == null) m.invoke(handler) else m.invoke(handler, arg)
        }
    }

    /** 失败回喂：空响应（trackid+keyword，页面显示「暂无搜索结果」）。 */
    private fun feedEmpty(module: XposedModule, cl: ClassLoader, handler: Any, keyword: String) {
        runCatching {
            val w = WireWriter()
            w.stringField(1, randomTrackid())
            w.stringField(4, keyword)
            val resp = parseHost(module, cl, BYTYPE_RESP_CLASS, w.toByteArray()) ?: return
            invokeHandler(handler, "onNext", resp)
            invokeHandler(handler, "onCompleted")
            HookProbe.first(module, "search:fedEmpty", 3) { "keyword=$keyword" }
        }
    }
}

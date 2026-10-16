package com.ctf.bilisb.unlock

import android.os.Handler
import android.os.HandlerThread
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.PRIORITY_DEFAULT
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 选集面板解锁（ViewTab）：hook `app.viewunite.v1.ViewMoss.view/executeView` 响应，
 * 受限标题的 tab 缺正季选集区块时，经漫游服务端 `/pgc/view/web/season`（TW 出口，
 * 2026-10-04 实证：CN season API 的地区门按 IP 判定，出口视角全量可取且 CN/intl 共库
 * 编号）拉季数据，把 Module{type=13, section_data} 追加进 reply.tab 的简介页。
 *
 * 数据链实证（2026-10-04，輝夜姬 ss33088 vs 迷宫饭 ss47083 字节级对照）：
 *  - 选集卡片住在 `ViewReply.tab(5) → tab_module(1){tab_type=1} → introduction(2)
 *    → modules(2) → Module{type=1=13, section_data(12)=SectionData}`；
 *  - 受限时服务端仍下发完整 ogvData（seasonId/title/totalEp 都在）但 tab 里**没有
 *    正季 SectionData**（輝夜姬只剩 4 张 PV/特别篇卡）——页面因此不渲染选集，
 *    `pgc.gateway.view.v1.ViewMoss.seasonSections` 六方法全程 0 调用（两轮真机观测）；
 *  - `viewunite.common.ViewEpisode` 字段号与项目既有 SeasonParser/UnlockWire 完全对齐。
 *
 * 注入用 wire 级拼接（[WireSplice]）：reply→tab→tab_module→introduction→modules 逐层
 * 定点追加，未知字段零损；宿主类 parseFrom 重建。失败一律放行原响应。
 */
object ViewTabHook {

    private const val MAX_RETRY = 8
    private const val RETRY_DELAY_MS = 4000L

    /** 季拉取单次 HTTP 超时；两次拉取+1s 间隔要落在下方 executor.get 总超时内。 */
    private const val SEASON_FETCH_TIMEOUT_MS = 3000
    private const val SEASON_RETRY_DELAY_MS = 1000L
    private const val SEASON_FAIL_COOLDOWN_MS = 15_000L

    /** viewunite 的 PGC 载荷 typeUrl（classes18.dex 字符串池实证）。 */
    const val VIEW_PGC_ANY_TYPE_URL =
        "type.googleapis.com/bilibili.app.viewunite.pgcanymodel.ViewPgcAny"

    /** Module.type=13 = 选集 SectionData 区块（迷宫饭 ss47083 实拍）。 */
    private const val MODULE_TYPE_SECTION_DATA = 13L

    private val attempts = AtomicInteger(0)
    private val captured = AtomicInteger(0)

    /** season_id → 剧集（进程内缓存，避免每次进详情页都打服务端）。只存成功结果。 */
    private val episodesBySeasonId = ConcurrentHashMap<Int, List<SeasonEpisode>>()

    /** season_id → Bstar 完整季详情（包含封面、简介等）。 */
    private val seasonDetailBySeasonId = ConcurrentHashMap<Int, ThSeasonDetail>()

    /** season_id → 并发请求锁（防止 ViewUniteMoss 与 PlayViewHook 同时触发重复拉取）。 */
    private val seasonLocks = ConcurrentHashMap<Int, Any>()

    private fun lockForSeason(seasonId: Int): Any =
        seasonLocks.computeIfAbsent(seasonId) { Any() }

    /**
     * 按 sid 查询首集 ep_id。
     */
    fun epIdForSeason(seasonId: Int): Long? {
        if (seasonId <= 0) return null
        return episodesBySeasonId[seasonId]?.firstOrNull()?.epId
    }

    /**
     * 获取或网络拉取剧集列表（优先查缓存，次查 Bstar，最后回退 CN/TW 季接口）。
     */
    fun getOrFetchEpisodes(module: XposedModule, seasonId: Int): List<SeasonEpisode> {
        if (seasonId <= 0) return emptyList()
        episodesBySeasonId[seasonId]?.let { return it }
        val config = UnlockConfig.load(module)
        if (!config.enabled || config.servers.isEmpty()) return emptyList()
        val server = config.servers.firstOrNull { it.area == "th" } ?: config.servers.first()
        val serverBase = server.baseUrl.trimEnd('/')
        val accessKey = server.accessKey.ifBlank { HostAccessKey.lastSeen() } ?: ""
        return synchronized(lockForSeason(seasonId)) {
            episodesBySeasonId[seasonId]?.let { return@synchronized it }
            val detail = seasonDetailBySeasonId[seasonId]
                ?: fetchThSeasonDetail(module, seasonId, serverBase, accessKey)
            if (detail != null && detail.episodes.isNotEmpty()) {
                episodesBySeasonId[seasonId] = detail.episodes
                seasonDetailBySeasonId[seasonId] = detail
                return@synchronized detail.episodes
            }
            fetchEpisodes(module, seasonId, serverBase, accessKey)
        }
    }

    /**
     * 从 ViewReq 或 PlayViewUniteReq 提取 season_id。
     */
    fun extractSeasonId(req: Any?): Int {
        if (req == null) return 0
        return runCatching {
            val extra = req.javaClass.methods.firstOrNull { it.name == "getExtraContentMap" }?.invoke(req)
            @Suppress("UNCHECKED_CAST")
            val map = extra as? Map<String, String>
            map?.get("season_id")?.toIntOrNull()
                ?: map?.get("seasonId")?.toIntOrNull()
                ?: 0
        }.getOrDefault(0)
    }

    /**
     * 从 ViewReq 提取 ep_id。
     */
    fun extractEpId(req: Any?): Long {
        if (req == null) return 0L
        return runCatching {
            val extra = req.javaClass.methods.firstOrNull { it.name == "getExtraContentMap" }?.invoke(req)
            @Suppress("UNCHECKED_CAST")
            val map = extra as? Map<String, String>
            map?.get("ep_id")?.toLongOrNull()
                ?: map?.get("epId")?.toLongOrNull()
                ?: 0L
        }.getOrDefault(0L)
    }

    /**
     * 拉取 Bstar 季详情（投递到后台线程池，避免在主线程触发 NetworkOnMainThreadException）。
     */
    fun fetchThSeasonDetail(
        module: XposedModule,
        seasonId: Int,
        serverBase: String,
        accessKey: String,
    ): ThSeasonDetail? {
        val akParam = if (accessKey.isNotEmpty()) "&access_key=$accessKey" else ""
        val url = "$serverBase/intl/gateway/v2/ogv/view/app/season?season_id=$seasonId" +
            akParam +
            "&s_locale=zh_SG&c_locale=zh_SG&mobi_app=bstar_a&platform=android"
        val content = runCatching {
            BoundedCall.await(executor, SEASON_FETCH_TIMEOUT_MS + 1000L) {
                PlayViewHook.defaultFetch(url, "bstar_a", timeoutMs = SEASON_FETCH_TIMEOUT_MS)
            }
        }.onFailure { t ->
            HookProbe.first(module, "viewTab:thSeasonErr", 3) {
                "sid=$seasonId ${t.javaClass.simpleName}: ${t.message}"
            }
        }.getOrNull() ?: return null
        return SeasonParser.parseThSeasonDetail(content)
    }

    /**
     * 当主站 View 调用抛异常或返回 -404 时尝试救援：
     * 从请求提取 seasonId，通过 Bstar 拉取季详情，经 [ThViewReplyReconstructor] 重构出合法的 ViewReply 响应。
     */
    fun tryRescueError(module: XposedModule, cl: ClassLoader, req: Any?, error: Throwable?): Any? {
        var seasonId = extractSeasonId(req)
        if (seasonId <= 0) {
            val epId = extractEpId(req)
            if (epId > 0) {
                seasonId = episodesBySeasonId.entries.firstOrNull { entry -> entry.value.any { it.epId == epId } }?.key ?: 0
            }
        }
        if (seasonId <= 0) return null
        val config = UnlockConfig.load(module)
        if (!config.enabled || config.servers.isEmpty()) return null

        HookProbe.first(module, "viewTab:rescueError", 3) {
            "sid=$seasonId 触发 View 404 救援: ${error?.javaClass?.simpleName}: ${error?.message}"
        }

        val server = config.servers.firstOrNull { it.area == "th" } ?: config.servers.first()
        val serverBase = server.baseUrl.trimEnd('/')
        val accessKey = server.accessKey.ifBlank { HostAccessKey.lastSeen() } ?: ""

        val detail = synchronized(lockForSeason(seasonId)) {
            var d = seasonDetailBySeasonId[seasonId]
                ?: fetchThSeasonDetail(module, seasonId, serverBase, accessKey)
            if (d != null && d.episodes.isNotEmpty()) {
                seasonDetailBySeasonId[seasonId] = d
                episodesBySeasonId[seasonId] = d.episodes
            } else {
                val eps = episodesBySeasonId[seasonId]
                    ?: fetchEpisodes(module, seasonId, serverBase, accessKey)
                if (eps.isNotEmpty()) {
                    d = ThSeasonDetail(
                        seasonId = seasonId,
                        title = eps.first().title,
                        cover = eps.first().cover,
                        evaluate = "",
                        total = eps.size,
                        episodes = eps,
                    )
                }
            }
            d
        }
        if (detail == null || detail.episodes.isEmpty()) {
            HookProbe.first(module, "viewTab:rescueFailed", 3) { "sid=$seasonId 季数据拉取为空" }
            return null
        }

        val rawRebuiltBytes = ThViewReplyReconstructor.reconstruct(detail)
        val finalBytes = if (config.cacheUnlock) DownloadRightsPatch.patch(rawRebuiltBytes) else rawRebuiltBytes
        return runCatching {
            val rebuilt = cl.loadClass(HostTargets.VIEW_UNITE_REPLY_CLASS)
                .getMethod("parseFrom", ByteArray::class.java)
                .invoke(null, finalBytes)
            HookProbe.first(module, "viewTab:rescued", 3) {
                "sid=$seasonId title=${detail.title} eps=${detail.episodes.size} (${finalBytes.size}B)"
            }
            rebuilt
        }.onFailure { t ->
            HookProbe.first(module, "viewTab:rescueParseFailed", 3) {
                "${t.javaClass.simpleName}: ${t.message}"
            }
        }.getOrNull()
    }


    /**
     * 按 cid 反查 ep_id。
     *
     * 下载引擎取播放地址的请求只带 cid（season/ep 都是 0），而 PGC 播放接口只认 ep_id——
     * 播放路径的 cid+ep 是齐的，所以这条兜底只在下载路径用得上（2026-10-05 真机实证：
     * 只带 cid 去解析服务器会被上游按 -10403 拒掉）。
     */
    fun epIdForCid(cid: Long): Long? {
        if (cid == 0L) return null
        return episodesBySeasonId.values.asSequence().flatten()
            .firstOrNull { it.cid == cid }
            ?.epId
    }

    /** sid → 最近一次季拉取失败时刻：冷却期内不再打服务端，防失败重试风暴放大上游 -429。 */
    private val seasonFailAt = ConcurrentHashMap<Int, Long>()

    private val executor by lazy {
        Executors.newCachedThreadPool { r ->
            Thread(r, "BiliSB-ViewTabNet").apply { isDaemon = true }
        }
    }

    private val retryHandler: Handler by lazy {
        val thread = HandlerThread("BiliSB-ViewTabRetry").apply { isDaemon = true }
        thread.start()
        Handler(thread.looper)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
        installIntroDetailHook(module, cl)
    }

    private class IntroDetailApiHandler(
        private val module: XposedModule,
        private val cl: ClassLoader,
        private val origApi: Any,
    ) : java.lang.reflect.InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> origApi.toString()
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> method.invoke(origApi, *(args ?: emptyArray()))
                }
            }
            if (method.name == "requestMediaInfo" && !args.isNullOrEmpty() && args[0] is Number) {
                val mediaId = (args[0] as Number).toLong()
                val detail = seasonDetailBySeasonId[mediaId.toInt()]
                if (detail != null) {
                    val mocked = runCatching {
                        val vo = buildOgvIntroInfoVo(cl, detail)
                        val legacy = vo.javaClass.name == "${HostTargets.OGV_INTRO_FLOATLAYER_PKG}.bean.OGVIntroInfoVo"
                        val respClass = Class.forName(
                            if (legacy) HostTargets.LEGACY_OKRETRO_SUCCESS_RESPONSE_CLASS else HostTargets.OKRETRO_SUCCESS_RESPONSE_CLASS, false, cl,
                        )
                        respClass.getConstructor(Any::class.java).newInstance(vo)
                    }.onFailure { t ->
                        HookProbe.first(module, "viewTab:introBuildErr", 3) {
                            "mediaId=$mediaId ${t.javaClass.simpleName}: ${t.message}"
                        }
                    }.getOrNull()
                    if (mocked != null) {
                        HookProbe.first(module, "viewTab:introInjected", 5) {
                            "mediaId=$mediaId title=${detail.title}"
                        }
                        return mocked
                    }
                }
            }
            return try {
                method.invoke(origApi, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException ?: e
            }
        }
    }

    /**
     * 拦截播放页点击「详情 >」弹出的底部简介浮层（OgvIntroDetailInfoComponent）：
     * 当 mediaId 命中已缓存的 Bstar 东南亚番剧（[seasonDetailBySeasonId]）时，
     * 直接由 [ThSeasonDetail] 构造 `OgvIntroInfoVo` 并包装为 `com.bilibili.okretro.response.b$c` 返回，
     * 避免宿主向主站请求不存在的东南亚 media_id 导致详情浮层空白。
     */
    private fun installIntroDetailHook(module: XposedModule, cl: ClassLoader) {
        runCatching {
            val componentClass = runCatching {
                Class.forName(HostTargets.OGV_INTRO_DETAIL_BIND_CONT_CLASS, false, cl).getDeclaredField("this\$0").type
            }.getOrElse { Class.forName(HostTargets.LEGACY_OGV_INTRO_COMPONENT, false, cl) }
            val ctor = componentClass.declaredConstructors.firstOrNull()
                ?: run {
                    HookProbe.miss(module, "viewTab:introDetail", "no constructor on ${componentClass.name}")
                    return
                }
            val apiField = componentClass.declaredFields.firstOrNull { f ->
                f.type.isInterface && f.type.name.startsWith(HostTargets.OGV_INTRO_FLOATLAYER_PKG)
            } ?: componentClass.getDeclaredField("h")
            runCatching { ctor.isAccessible = true }
            runCatching { apiField.isAccessible = true }
            module.hook(ctor)
                .setPriority(PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val res = chain.proceed()
                    val instance = chain.thisObject
                    if (instance != null) {
                        runCatching {
                            val origApi = apiField.get(instance)
                            if (origApi != null &&
                                !(Proxy.isProxyClass(origApi.javaClass) &&
                                    Proxy.getInvocationHandler(origApi) is IntroDetailApiHandler)
                            ) {
                                val proxy = Proxy.newProxyInstance(
                                    cl,
                                    arrayOf(apiField.type),
                                    IntroDetailApiHandler(module, cl, origApi),
                                )
                                apiField.set(instance, proxy)
                            }
                        }.onFailure { t ->
                            HookProbe.first(module, "viewTab:introProxyErr", 3) {
                                "${t.javaClass.simpleName}: ${t.message}"
                            }
                        }
                    }
                    res
                }
            HookProbe.ok(module, "viewTab:introDetail", "${componentClass.simpleName}.${apiField.name}")
        }.onFailure { t ->
            HookProbe.miss(module, "viewTab:introDetail", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    internal fun buildOgvIntroInfoVo(cl: ClassLoader, detail: ThSeasonDetail): Any {
        val layout = HostTargets.OGV_INTRO_MODEL_LAYOUTS.first { (pkg, model) ->
            runCatching { Class.forName("$pkg.$model", false, cl) }.isSuccess
        }
        val pkg = layout.first
        val areaClass = Class.forName("$pkg.Area", false, cl)
        val areaCtor = areaClass.getConstructor(Long::class.javaPrimitiveType, String::class.java)
        val areas = detail.areas.split(" ").filter { it.isNotBlank() }.mapIndexed { idx, name ->
            areaCtor.newInstance((idx + 1).toLong(), name)
        }

        val publishClass = Class.forName("$pkg.Publish", false, cl)
        val publishCtor = runCatching { publishClass.getConstructor(
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
            String::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType,
        ) }.getOrNull()
        val timeLengthShow = detail.timeLengthShow.ifEmpty {
            if (detail.total > 0) "全${detail.total}话" else ""
        }
        val isFinish = timeLengthShow.contains("完结") || timeLengthShow.startsWith("全")
        val publish = if (publishCtor != null) {
            publishCtor.newInstance(0, 0, "", "", detail.publishDateShow, timeLengthShow, true, isFinish)
        } else {
            publishClass.getConstructor(
                String::class.java, String::class.java, Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, String::class.java, Int::class.javaPrimitiveType,
            ).newInstance("", "", true, isFinish, 0, detail.publishDateShow, timeLengthShow, 0)
        }

        val styleClass = Class.forName("$pkg.Style", false, cl)
        val styleCtor = styleClass.getConstructor(
            Long::class.javaPrimitiveType,
            String::class.java,
            String::class.java,
        )
        val styles = detail.styles.split(" ").filter { it.isNotBlank() }.mapIndexed { idx, name ->
            styleCtor.newInstance((idx + 1).toLong(), "", name)
        }

        val actor = if (detail.actorInfo.isNotBlank()) {
            val actorClass = Class.forName("$pkg.Actor", false, cl)
            actorClass.getConstructor(String::class.java, String::class.java)
                .newInstance(detail.actorTitle.ifEmpty { "角色声优" }, detail.actorInfo)
        } else {
            null
        }

        val staff = if (detail.staffInfo.isNotBlank()) {
            val staffClass = Class.forName("$pkg.Staff", false, cl)
            staffClass.getConstructor(String::class.java, String::class.java)
                .newInstance(detail.staffTitle.ifEmpty { "制作信息" }, detail.staffInfo)
        } else {
            null
        }

        val ratingClass = Class.forName("$pkg.Rating", false, cl)
        val rating = ratingClass.getConstructor(Float::class.javaPrimitiveType, String::class.java)
            .newInstance(0f, "")

        val voClass = Class.forName("$pkg.${layout.second}", false, cl)
        val voCtor = voClass.constructors.first { it.parameterTypes.size == 18 }
        val sidLong = detail.seasonId.toLong()
        return voCtor.newInstance(
            sidLong,                           // 1. seasonId: Long
            1,                                 // 2. mediaType: Int
            sidLong,                           // 3. mediaId: Long
            detail.cover,                      // 4. cover: String
            detail.title,                      // 5. mediaTitle: String
            "",                                // 6. seasonTitle: String
            detail.typeName.ifEmpty { "番剧" }, // 7. typeName: String
            areas,                             // 8. areas: List<Area>
            publish,                           // 9. publish: Publish
            null,                              // 10. mediaBadgeInfo: OgvBadgeInfo?
            detail.alias,                      // 11. alias: String
            detail.originName,                 // 12. originName: String
            styles,                            // 13. styles: List<Style>
            emptyList<Any>(),                  // 14. celebrities: List<Celebrity>
            actor,                             // 15. actor: Actor?
            staff,                             // 16. staff: Staff?
            detail.evaluate,                   // 17. evaluate: String
            rating,                            // 18. rating: Rating
        )
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (attempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "viewTab", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            val moss = Class.forName(HostTargets.VIEW_UNITE_MOSS_CLASS, false, cl)
            val hooked = mutableListOf<String>()
            for (m in moss.declaredMethods) {
                val isTwoArg = m.name == "view" && m.parameterTypes.size == 2
                val isOneArg = m.name == "executeView" && m.parameterTypes.size == 1
                if (!isTwoArg && !isOneArg) continue
                runCatching { m.isAccessible = true }
                runCatching { module.deoptimize(m) }
                hookOne(module, cl, m, wrapHandler = isTwoArg)
                hooked += "${m.name}(${m.parameterTypes.size})"
            }
            if (hooked.isEmpty()) {
                HookProbe.first(module, "viewTabRetry", 3) {
                    "view/executeView not found yet, attempt=${attempts.get()}"
                }
                retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
                return
            }
            HookProbe.ok(module, "viewTab", hooked.joinToString(", "))
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "viewTabRetry", 3) {
                "class not loaded yet, attempt=${attempts.get()}"
            }
            retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
        } catch (t: Throwable) {
            HookProbe.miss(module, "viewTab", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun hookOne(module: XposedModule, cl: ClassLoader, m: Method, wrapHandler: Boolean) {
        module.hook(m)
            .setPriority(PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val req = chain.args.getOrNull(0)
                if (wrapHandler && chain.args.size >= 2 && chain.args[1] != null) {
                    val handler = chain.args[1]
                    val wrapped = Proxy.newProxyInstance(
                        cl,
                        handler.javaClass.interfaces,
                        { _, method, args ->
                            if (args != null && args.isNotEmpty() && isCarrier(method.name)) {
                                args[0] = transform(module, cl, req, args[0])
                            }
                            if (method.name == "onError" && args != null && args.isNotEmpty()) {
                                val rescued = tryRescueError(module, cl, req, args[0] as? Throwable)
                                if (rescued != null) {
                                    val onNextMethod = handler.javaClass.methods.firstOrNull { it.name == "onNext" }
                                    val onCompletedMethod = handler.javaClass.methods.firstOrNull { it.name == "onCompleted" }
                                    onNextMethod?.invoke(handler, rescued)
                                    onCompletedMethod?.invoke(handler)
                                    return@newProxyInstance null
                                }
                            }
                            method.invoke(handler, *(args ?: emptyArray()))
                        },
                    )
                    return@intercept chain.proceed(arrayOf(req, wrapped))
                }
                try {
                    val reply = chain.proceed()
                    val transformed = transform(module, cl, req, reply)
                    transformed ?: tryRescueError(module, cl, req, null)
                } catch (t: Throwable) {
                    val rescued = tryRescueError(module, cl, req, t)
                    rescued ?: throw t
                }
            }
    }


    private fun isCarrier(name: String): Boolean =
        name == "onNext" || name == "onCompleted" || name == "resumeWith" || name == "onSuccess"

    // ---- 变换主链 ----

    private fun transform(module: XposedModule, cl: ClassLoader, req: Any?, reply: Any?): Any? {
        if (reply == null) return null
        var fallback = reply
        return runCatching {
            val config = UnlockConfig.load(module)
            if (!config.enabled || config.servers.isEmpty()) return@runCatching fallback
            val originalBytes = reply.javaClass.methods.firstOrNull { f -> f.name == "toByteArray" }
                ?.invoke(reply) as? ByteArray ?: return@runCatching fallback
            val replyBytes = if (config.cacheUnlock) DownloadRightsPatch.patch(originalBytes) else originalBytes
            if (!replyBytes.contentEquals(originalBytes)) {
                fallback = cl.loadClass(HostTargets.VIEW_UNITE_REPLY_CLASS)
                    .getMethod("parseFrom", ByteArray::class.java).invoke(null, replyBytes)
                HookProbe.first(module, "viewTab:downloadRights", 3) { "season/episode download rights updated" }
            }
            captureOnce(module, reply, "pre")

            // supplement 须为 ViewPgcAny 且 ogvData 带 seasonId
            val ogvData = extractOgvData(replyBytes) ?: return@runCatching fallback
            val seasonId = WireSplice.parse(ogvData)
                .firstOrNull { it.field == 2 && it.wireType == 0 }?.varint()?.toInt() ?: 0
            if (seasonId == 0) {
                HookProbe.first(module, "viewTab:skipNoSeason", 3) { "ogvData 缺 seasonId" }
                return@runCatching fallback
            }

            val tabBytes = WireSplice.firstMessage(replyBytes, 5)
            if (tabBytes == null || panelPresent(tabBytes)) {
                HookProbe.first(module, "viewTab:skipHasPanel", 3) { "sid=$seasonId tab 已有选集区块" }
                return@runCatching fallback
            }

            val serverBase = config.servers.first().baseUrl.trimEnd('/')
            val accessKey = config.servers.first().accessKey.ifBlank { HostAccessKey.lastSeen() } ?: ""
            val episodes = episodesBySeasonId[seasonId]
                ?: fetchEpisodes(module, seasonId, serverBase, accessKey)
            if (episodes.isEmpty()) {
                HookProbe.first(module, "viewTab:seasonFail", 3) { "sid=$seasonId 服务端季数据为空" }
                return@runCatching fallback
            }

            val moduleBytes = buildSectionModuleBytes(seasonId, episodes)
            val newTab = appendSectionModule(tabBytes, moduleBytes)
            val spliced = WireSplice.transformMessage(replyBytes, 5) { newTab }
            val newReplyBytes = if (config.cacheUnlock) {
                val patched = DownloadRightsPatch.patch(spliced)
                if (!patched.contentEquals(spliced)) {
                    UnlockNotifier.toastDownloadAllowed(module)
                }
                patched
            } else spliced
            val rebuilt = cl.loadClass(HostTargets.VIEW_UNITE_REPLY_CLASS)
                .getMethod("parseFrom", ByteArray::class.java)
                .invoke(null, newReplyBytes)
            HookProbe.first(module, "viewTab:injected", 3) {
                "sid=$seasonId eps=${episodes.size} (${replyBytes.size}B -> ${newReplyBytes.size}B)"
            }
            captureOnce(module, reply, "sid=$seasonId")
            // 诊断：宿主 parseFrom 重建后的字节——若宿主解析丢弃了注入区块，
            // 这里会比 newReplyBytes 明显缩水
            runCatching {
                val hostBytes = rebuilt.javaClass.methods.firstOrNull { f -> f.name == "toByteArray" }
                    ?.invoke(rebuilt) as? ByteArray
                if (hostBytes != null) {
                    val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
                    dir.mkdirs()
                    java.io.File(dir, "viewtab_host_roundtrip.bin")
                        .outputStream().use { it.write(hostBytes) }
                    HookProbe.first(module, "viewTab:roundtrip", 3) {
                        "new=${newReplyBytes.size}B host=${hostBytes.size}B"
                    }
                }
            }
            rebuilt
        }.onFailure { t ->
            HookProbe.first(module, "viewTab:failed", 5) {
                var root = t
                while (root.cause != null) root = root.cause!!
                "${t.javaClass.simpleName}: ${t.message} <- ${root.javaClass.simpleName}: ${root.message}"
            }
        }.getOrNull() ?: fallback
    }

    /**
     * 服务端拉季数据：成功才进缓存（getOrPut 把空结果也缓存进去的旧实现，会让一次
     * seasonFail 后该 sid 整个进程不再真正拉取——2026-10-04 实锤的坑）。上游 CN API
     * 对切季瞬间的 view+playurl 并发风暴会间歇 -412/-429 落成季空响应，为空 1s 后
     * 重试一次；仍失败进 [SEASON_FAIL_COOLDOWN_MS] 冷却，窗口过后自然重估自愈。
     */
    private fun fetchEpisodes(
        module: XposedModule,
        seasonId: Int,
        serverBase: String,
        accessKey: String,
    ): List<SeasonEpisode> {
        val failedAt = seasonFailAt[seasonId]
        if (failedAt != null &&
            android.os.SystemClock.elapsedRealtime() - failedAt < SEASON_FAIL_COOLDOWN_MS
        ) {
            HookProbe.first(module, "viewTab:seasonCool", 3) { "sid=$seasonId 失败冷却期内，跳过拉取" }
            return emptyList()
        }
        val url = "$serverBase/pgc/view/web/season?season_id=$seasonId" +
            (if (accessKey.isNotEmpty()) "&access_key=$accessKey" else "") +
            "&build=9070300&mobi_app=android&platform=android"
        // 3s+1s+3s 的重试链要落在 8s 总超时内；超时同样按失败处理进冷却
        val task = java.util.concurrent.Callable {
            var episodes = fetchEpisodesOnce(url)
            if (episodes.isEmpty()) {
                HookProbe.first(module, "viewTab:seasonRetry", 3) {
                    "sid=$seasonId 首拉为空，${SEASON_RETRY_DELAY_MS}ms 后重试"
                }
                Thread.sleep(SEASON_RETRY_DELAY_MS)
                episodes = fetchEpisodesOnce(url)
            }
            episodes
        }
        val episodes = runCatching { BoundedCall.await(executor, 8000) { task.call() } }
            .getOrElse {
                HookProbe.first(module, "viewTab:seasonTimeout", 3) {
                    "sid=$seasonId ${it.javaClass.simpleName}: ${it.message}"
                }
                emptyList()
            }
        if (episodes.isNotEmpty()) {
            episodesBySeasonId[seasonId] = episodes
        } else {
            seasonFailAt[seasonId] = android.os.SystemClock.elapsedRealtime()
        }
        return episodes
    }

    private fun fetchEpisodesOnce(url: String): List<SeasonEpisode> = runCatching {
        SeasonParser.parseFlatEpisodes(
            PlayViewHook.defaultFetch(url, "android", timeoutMs = SEASON_FETCH_TIMEOUT_MS),
        )
    }.getOrDefault(emptyList())

    /** supplement(6) → Any.value → ViewPgcAny.ogvData(1)；形态不符返回 null。 */
    private fun extractOgvData(replyBytes: ByteArray): ByteArray? {
        val supplement = WireSplice.firstMessage(replyBytes, 6) ?: return null
        val sf = WireSplice.parse(supplement)
        val typeUrl = sf.firstOrNull { it.field == 1 && it.wireType == 2 }?.payload()
            ?.toString(Charsets.UTF_8)
        if (typeUrl != VIEW_PGC_ANY_TYPE_URL) return null
        val value = sf.firstOrNull { it.field == 2 && it.wireType == 2 }?.payload() ?: return null
        return WireSplice.firstMessage(value, 1)
    }

    /** tab 的简介页里是否已有选集 SectionData 区块（Module.type=13）。 */
    private fun panelPresent(tabBytes: ByteArray): Boolean {
        for (tm in WireSplice.allMessages(tabBytes, 1)) {
            val tabType = WireSplice.parse(tm).firstOrNull()?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
            if (tabType != 1L) continue
            val intro = WireSplice.firstMessage(tm, 2) ?: continue
            if (WireSplice.hasMessageWithFirstVarint(intro, 2, MODULE_TYPE_SECTION_DATA)) return true
        }
        return false
    }

    /**
     * Module{type=1:13, section_data(12)}：SectionData{id=1, section_id=2, title=3,
     * module_style(9)={1:1}, episodes(7)}——层级与取值照迷宫饭 ss47083 实拍模板。
     */
    internal fun buildSectionModuleBytes(seasonId: Int, episodes: List<SeasonEpisode>): ByteArray {
        val sd = WireWriter()
        sd.int32Field(1, seasonId)
        sd.int32Field(2, seasonId)
        sd.stringField(3, "选集")
        sd.messageField(9, byteArrayOf(0x08, 0x01)) // Style{1:1}
        for (ep in episodes) sd.messageField(7, UnlockWire.buildViewEpisodeBytes(ep))
        val m = WireWriter()
        m.int32Field(1, 13)
        m.messageField(12, sd.toByteArray())
        return m.toByteArray()
    }

    /**
     * 把 Module 插入 tab：tab_module{tab_type=1} 的 introduction.modules 里，
     * 位置照服务端全量响应的实拍（輝夜姬 ss33088 受限 vs 全量逐字段 diff）：插在
     * 第一个 type=9 模块之前（原生顺序 …serial_season(21) → 选集(13) → (9)…）。
     * 追加到末尾不渲染（theseus 页面按序取槽位）。其余 tab 字节零损保留。
     */
    private fun appendSectionModule(tabBytes: ByteArray, moduleBytes: ByteArray): ByteArray {
        val newTms = mutableListOf<ByteArray>()
        var touched = false
        for (tm in WireSplice.allMessages(tabBytes, 1)) {
            val tabType = WireSplice.parse(tm).firstOrNull()?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
            if (tabType == 1L && !touched) {
                touched = true
                val intro = WireSplice.firstMessage(tm, 2)
                val newIntro = if (intro != null) {
                    val elems = WireSplice.parse(intro).toMutableList()
                    // 插入点：第一个 type=9 模块的元素下标（原生顺序 …21 → 13 → 9…）
                    var insertAt = elems.size
                    for ((idx, e) in elems.withIndex()) {
                        if (e.field == 2 && e.wireType == 2) {
                            val t = WireSplice.parse(e.payload()).firstOrNull()
                                ?.takeIf { it.field == 1 && it.wireType == 0 }?.varint()
                            if (t == 9L) { insertAt = idx; break }
                        }
                    }
                    elems.add(insertAt, WireSplice.Elem(2, 2, WireSplice.message(2, moduleBytes)))
                    WireSplice.emit(elems)
                } else {
                    WireSplice.message(2, moduleBytes)
                }
                newTms += WireSplice.transformMessage(tm, 2) { newIntro }
            } else {
                newTms += tm
            }
        }
        if (!touched) return tabBytes
        return WireSplice.emit(
            WireSplice.parse(tabBytes).map { e ->
                if (e.field == 1 && e.wireType == 2 && newTms.isNotEmpty()) {
                    WireSplice.Elem(1, 2, WireSplice.message(1, newTms.removeAt(0)))
                } else {
                    e
                }
            },
        )
    }

    private fun captureOnce(module: XposedModule, reply: Any, tag: String) {
        val n = captured.incrementAndGet()
        if (n > 6) return
        runCatching {
            val bytes = reply.javaClass.methods.firstOrNull { f -> f.name == "toByteArray" }
                ?.invoke(reply) as? ByteArray ?: return
            val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
            dir.mkdirs()
            val f = java.io.File(dir, "viewtab_reply_$n.bin")
            f.outputStream().use { it.write(bytes) }
            HookProbe.first(module, "viewTab:capture", 6) { "${f.absolutePath} ${bytes.size}B $tag" }
        }
    }
}

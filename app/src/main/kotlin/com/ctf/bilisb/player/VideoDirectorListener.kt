package com.ctf.bilisb.player

import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HookResolve
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.info
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * video id(aid / cid)获取链路。
 *
 * ## 6.5.0（`com.bilibili.app.in`）实测结论
 *
 * - 旧接口 `VideoDirectorObserver` / `addVideoDirectorObserver` / `getLogDescription()` **都不存在**。
 * - 观察者接口是 `tv.danmaku.biliplayerv2.service.E0`（`j0(E0)` 注册 / `z0(E0)` 注销），
 *   回调形态 `b(Video$e current, Video$e previous)`、`c(Video$e)`、`e(Video$e)`。
 * - 服务实现类是 `tv.danmaku.biliplayerimpl.videodirector.PlayDirectorServiceV3`（类名未混淆），
 *   `D()` 返回当前 `Video$e`（字节码：`getItem()?.a()`）。
 * - `Video$e#z()` 返回 `Video$a`（`DanmakuResolveParams`），字段 `a`=avid、`b`=cid（`toString` 实测）。
 *
 * ## 关联 contextHash 的取舍（推测实现）
 *
 * 6.5.0 里 director 服务与播放器容器之间没有稳定的可反射映射，所以这里用
 * 「最近一次绑定容器的 contextHash」作为回落目标：容器 `bindPlayerContainer(f)` 时记下 hash，
 * 观察者回调到来时把 aid/cid 应用到该 hash。单播放页场景与旧行为一致；
 * 小窗/多实例场景需要在真机上用探针日志确认后再细化（见 docs/ROADMAP.md）。
 *
 * Note: 离开播放页必须对服务调 z0 注销观察者 — 见 .agents/notes/implemented/bug-fix/2026-03-22-full-audit-round2.md
 */
object VideoDirectorListener {
    /**
     * 已挂过代理的 director 服务 → (观察者 Proxy, 注册时的接口)。
     * z0 成功后才从表摘掉,失败则保留以免重复挂代理。
     */
    private val observersByService: MutableMap<Any, Pair<Any, Class<*>>> =
        Collections.synchronizedMap(WeakHashMap<Any, Pair<Any, Class<*>>>())

    /** 最近一次绑定的播放器 contextHash（0 表示还没有播放器）。 */
    @Volatile
    private var lastContextHash: Int = 0

    @Volatile
    private var idSink: ((contextHash: Int, aid: Long, cid: Long) -> Unit)? = null

    private val dispatchCount = AtomicInteger(0)

    /** 最近一次拿到的 director 服务实例（用于回调时补探当前条目 / 读 core 字段）。 */
    @Volatile
    private var lastService: Any? = null

    /** 最近一次解析出的 video id：容器还没绑定时先缓存，等绑定后再补发。 */
    @Volatile
    private var pendingIds: Pair<Long, Long>? = null

    /** pendingIds 的采集时刻(uptimeMillis):补发前做时效校验,上一播放会话的残留 id 不再补发。 */
    @Volatile
    private var pendingIdsAtMs: Long = 0L

    /**
     * 已收到过 ids 的 contextHash → ids。
     *
     * dispatch 直接派发 / flushPendingIds 补发成功后都记一笔;flush 重发前先查账,
     * 同一 context 拿过同一份 ids 就跳过 —— 否则每次 bindPlayer(全屏切换会反复 bind)
     * 都会把同一视频重喂一遍,onVideoIds 的「同一视频重进」分支会清空已跳过记录
     * (倒计时重弹等)。不同 context(玩家重建后 Context 实例变了、hash 变了)仍会正常补发。
     */
    private val deliveredIdsByContext = java.util.concurrent.ConcurrentHashMap<Int, Pair<Long, Long>>()

    /** pendingIds 有效期:超过它认为残留自上一播放会话,丢弃(足够覆盖 bind 早于 director 回调的正常窗口)。 */
    private const val PENDING_TTL_MS = 10_000L

    fun lastDirectorService(): Any? = lastService

    /** 播放器离开时调用：断开服务引用，避免长期持有宿主对象。 */
    fun clearDirectorService() {
        lastService = null
    }

    /**
     * 供容器绑定时补发：如果此前已经拿到 id 但当时还没有 contextHash。
     *
     * **补发后立即清空**：pendingIds 只服务"id 早于容器绑定"这一小段时间窗，
     * 若不清空，下一个视频绑定容器时会把**上一个视频**的 aid/cid 注册到新 context 上
     * （旧片段缓存 TTL 还在 → 用错视频的区间跳过/标记）。
     */
    fun flushPendingIds(module: XposedModule, contextHash: Int) {
        val ids = pendingIds ?: return
        val sink = idSink ?: return
        if (contextHash == 0) return
        // 时效校验:hook 链路是尽力而为的,destroy/unregister 可能没触发;
        // 上一个播放会话的残留 id 若被补发到新 context,会用错误视频的片段跳过新视频。
        // 超过 PENDING_TTL_MS(10s,足够覆盖「bind 早于 director 回调」的正常窗口)直接丢弃。
        val age = android.os.SystemClock.uptimeMillis() - pendingIdsAtMs
        pendingIds = null
        if (age > PENDING_TTL_MS) {
            HookProbe.first(module, "pendingIdsExpired", 3) {
                "expired pending aid=${ids.first} cid=${ids.second} age=${age}ms, dropped"
            }
            return
        }
        // 同一 context 已经拿过同一份 ids:重发只会触发 onVideoIds 的「同视频重进」
        // 清空已跳过记录,跳过。玩家重建后是新 context(hash 变了),不会被这里挡住。
        if (deliveredIdsByContext[contextHash] == ids) {
            HookProbe.first(module, "flushPendingIdsSkipped", 3) {
                "context=$contextHash already delivered aid=${ids.first} cid=${ids.second}"
            }
            return
        }
        module.info("videoDirector: flush pending aid=${ids.first} cid=${ids.second} context=$contextHash")
        deliveredIdsByContext[contextHash] = ids
        sink(contextHash, ids.first, ids.second)
    }

    /**
     * 播放器切换/离开时清掉待补发的 id（防止跨视频串台）。
     */
    fun clearPendingIds() {
        pendingIds = null
        pendingIdsAtMs = 0L
    }

    /** 注册 aid/cid 消费方（由 BiliSponsorBlockHooks 在进入播放页时设置）。 */
    fun setVideoIdSink(sink: (contextHash: Int, aid: Long, cid: Long) -> Unit) {
        idSink = sink
    }

    /** 播放器容器绑定时调用，记录当前活跃 contextHash。 */
    fun noteContextHash(contextHash: Int) {
        if (contextHash != 0) lastContextHash = contextHash
    }

    fun activeContextHash(): Int = lastContextHash

    /**
     * 把观察者代理挂到 director 服务实例上（6.5.0 主路径）。
     *
     * 调用点：Hook `PlayDirectorServiceV3#j0(E0)` 之后拿到服务实例时调用。
     */
    fun registerDirectorService(module: XposedModule, directorService: Any): Boolean {
        if (observersByService.containsKey(directorService)) return true

        val classLoader = directorService.javaClass.classLoader ?: run {
            HookProbe.miss(module, "director", "classLoader null")
            return false
        }
        // 观察者接口跨版本撞名（6.5.0 的 F0 是无关的媒体资源接口，6.6.0 的 E0 是空标记接口），
        // 不能按名字顺序挑类：对每个候选接口解析注册方法，「能解析出 (add 方法, 接口) 对」的
        // 即当前版本的观察者接口，代理也实现它 —— 方法解析成功本身就是接口正确性的证明。
        val addPair = HostTargets.DIRECTOR_OBSERVER_INTERFACES.firstNotNullOfOrNull { ifaceName ->
            val iface = runCatching { Class.forName(ifaceName, false, classLoader) }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            val method = HookResolve.forTarget(
                directorService,
                HostTargets.DIRECTOR_ADD_OBSERVER_METHODS,
                iface,
            ) ?: return@firstNotNullOfOrNull null
            method to iface
        }
        if (addPair == null) {
            HookProbe.miss(module, "director", "observer interface / addObserver not resolvable")
            return false
        }
        val (addMethod, observerInterface) = addPair

        val observer = Proxy.newProxyInstance(
            classLoader,
            arrayOf(observerInterface),
            ObserverHandler(module),
        )

        // 必须先登记再 invoke：我们挂在 l0/j0 上的 after 回调里调用本函数，而 invoke 走的
        // 就是这个被 hook 的方法（LSPosed 对反射调用同样生效）→ 回调重入本函数。
        // 登记先行使重入在 containsKey 处终止（等价 0.6.3 registeredHosts.add 的旧序职责）；
        // 若登记挪到 invoke 之后，6.6.0 在 onCreate 主线程内同步注册时无限重入，
        // 详情页黑屏 + 输入 ANR（2026-09-29 真机 MIUIScout 栈定位）。
        observersByService[directorService] = observer to observerInterface

        val ok = runCatching {
            addMethod.invoke(directorService, observer)
            true
        }.getOrElse {
            module.info("director: addObserver(${addMethod.name}) failed: ${it.message}")
            false
        }
        if (ok) {
            lastService = directorService
            HookProbe.ok(
                module,
                "director",
                "${directorService.javaClass.name}#${addMethod.name}(${observerInterface.simpleName})",
            )
            probeCurrentVideo(module, directorService)
        } else {
            // 注册失败回滚登记，避免留下无法注销的死账（removeObserver 依赖这张表）
            observersByService.remove(directorService, observer to observerInterface)
        }
        return ok
    }

    /** 尝试从 widget / 容器上取 director 服务并挂代理（旧目标路径，6.5.0 部分 widget 也有）。 */
    fun tryRegisterFromHost(module: XposedModule, host: Any): Boolean {
        val service = HookResolve.invokeNoArg(host, HostTargets.DIRECTOR_GET_SERVICE_METHODS) ?: run {
            HookProbe.miss(module, "directorFromHost", host.javaClass.name)
            return false
        }
        module.info("director: obtained ${service.javaClass.name} from ${host.javaClass.name}")
        return registerDirectorService(module, service)
    }

    /**
     * 播放器销毁时调用：对 director 服务调 z0 摘掉我们的观察者，并断开待补发 id。
     *
     * [host] 可能是 widget / 容器 / 服务本身。优先从 host 上取服务实例精确注销;
     * 取不到则注销 [lastService]。只从弱表里 remove(host) 是空操作 —— 键是服务不是 widget。
     */
    fun unregister(module: XposedModule, host: Any) {
        val service = resolveService(host) ?: lastService
        if (service != null) {
            removeObserver(module, service)
        }
        clearDirectorService()
        clearPendingIds()
    }

    private fun resolveService(host: Any): Any? {
        if (observersByService.containsKey(host)) return host
        return HookResolve.invokeNoArg(host, HostTargets.DIRECTOR_GET_SERVICE_METHODS)
    }

    private fun removeObserver(module: XposedModule, directorService: Any) {
        val registered = observersByService[directorService] ?: return
        val (observer, observerInterface) = registered
        val removeMethod = HookResolve.forTarget(
            directorService,
            HostTargets.DIRECTOR_REMOVE_OBSERVER_METHODS,
            observerInterface,
        )
        if (removeMethod == null) {
            HookProbe.miss(module, "directorRemove", "z0 not found on ${directorService.javaClass.name}")
            return
        }
        val removed = runCatching {
            removeMethod.invoke(directorService, observer)
            true
        }.onFailure { module.info("director: removeObserver failed: ${it.message}") }
            .getOrDefault(false)
        if (removed) {
            observersByService.remove(directorService, registered)
        }
    }

    /** 探针：`service.D()` -> `Video$e` -> `z()` -> `Video$a`（DanmakuResolveParams）。 */
    fun probeCurrentVideo(module: XposedModule, directorService: Any) {
        if (currentVideoOf(directorService) == null) {
            HookProbe.first(module, "directorCurrentVideo", 3) { "null (还没有播放条目)" }
            return
        }
        HookProbe.first(module, "directorCurrentVideo", 5) { probeInfo(module, directorService) }
    }

    private fun currentVideoOf(directorService: Any): Any? = runCatching {
        HookResolve.forTarget(directorService, HostTargets.DIRECTOR_CURRENT_VIDEO_METHODS)
            ?.invoke(directorService)
    }.getOrNull()

    private fun probeInfo(module: XposedModule, directorService: Any): String {
        val video = currentVideoOf(directorService) ?: return "null (还没有播放条目)"
        return "video=${video.javaClass.name} ids=${extractVideoIds(module, video)}"
    }

    /** 诊断用：打印对象的 toString 与部分标量字段（截断，避免刷屏/超长）。 */
    private fun describe(value: Any): String {
        val text = runCatching { value.toString() }.getOrNull() ?: "<toString failed>"
        val fields = StringBuilder()
        var count = 0
        for (field in value.javaClass.declaredFields) {
            if (count >= 14) break
            val fieldValue = runCatching {
                field.isAccessible = true
                field.get(value)
            }.getOrNull()
            if (fieldValue is Number || fieldValue is String || fieldValue is Boolean) {
                fields.append(field.name).append('=').append(fieldValue).append(' ')
                count++
            }
        }
        return "toString=${text.take(160)} fields=[$fields]"
    }

    /**
     * 从 `Video$e` 提取 aid/cid。
     *
     * 主路径：`z()` -> `Video$a`(`DanmakuResolveParams`) 的 `a`/`b` 字段；
     * 兜底：扫 long 字段取两个大于 0 的值（旧实现的启发式，保留以防实现类不覆写 `z()`）。
     */
    fun extractVideoIds(module: XposedModule, video: Any): Pair<Long, Long>? {
        val params = runCatching {
            HookResolve.forTarget(video, listOf(HostTargets.VIDEO_PARAMS_ACCESSOR))?.invoke(video)
        }.getOrNull()

        HookProbe.first(module, "idDiagnostics", 4) {
            buildString {
                append("video=").append(video.javaClass.name).append(' ').append(describe(video))
                append(" || z()=").append(params?.javaClass?.name ?: "null")
                if (params != null) append(' ').append(describe(params))
            }
        }

        if (params != null && params.javaClass.name == HostTargets.VIDEO_PARAMS_CLASS) {
            val aid = (HookResolve.readField(params, HostTargets.VIDEO_PARAMS_AID_FIELD) as? Number)?.toLong()
            val cid = (HookResolve.readField(params, HostTargets.VIDEO_PARAMS_CID_FIELD) as? Number)?.toLong()
            if (aid != null && aid > 0 && cid != null && cid > 0) {
                HookProbe.first(module, "idsPrimary", 3) {
                    "${params.javaClass.simpleName} aid=$aid cid=$cid（${params.javaClass.simpleName}.a/.b）"
                }
                return aid to cid
            }
            HookProbe.first(module, "extractVideoIds", 5) {
                "params=${params.javaClass.name} aid=$aid cid=$cid ($params)"
            }
        }

        var aid = 0L
        var cid = 0L
        var aidField = ""
        var cidField = ""
        for (field in video.javaClass.declaredFields) {
            val value = runCatching {
                field.isAccessible = true
                field.get(video)
            }.getOrNull() as? Number ?: continue
            if (field.type != java.lang.Long.TYPE && field.type != java.lang.Integer.TYPE) continue
            val longValue = value.toLong()
            if (longValue <= 0 || longValue == aid) continue
            if (aid == 0L) {
                aid = longValue
                aidField = field.name
            } else if (cid == 0L) {
                cid = longValue
                cidField = field.name
            }
        }
        if (aid > 0 && cid > 0 && plausibleVideoIds(aid, cid)) {
            HookProbe.first(module, "extractVideoIdsFallback", 5) {
                "video=${video.javaClass.name} aid=$aid($aidField) cid=$cid($cidField) (字段扫描兜底)"
            }
            return aid to cid
        }
        if (aid > 0 && cid > 0) {
            HookProbe.first(module, "extractVideoIdsRejected", 5) {
                "video=${video.javaClass.name} aid=$aid($aidField) cid=$cid($cidField) 不合量级,放弃"
            }
        }

        HookProbe.first(module, "extractVideoIdsFailed", 5) {
            "video=${video.javaClass.name} params=${params?.javaClass?.name}"
        }
        return null
    }

    /**
     * 量级校验:B 站 aid/cid 都是 1e8~1e13 级的正整数。
     * duration 是 1e5~1e7 毫秒级,seasonId 可能到 1e15+;都不该被当成 aid/cid。
     */
    private fun plausibleVideoIds(aid: Long, cid: Long): Boolean {
        fun plausible(value: Long): Boolean = value in 10_000_000L..999_999_999_999_999L
        return plausible(aid) && plausible(cid) && aid != cid
    }

    private fun dispatch(module: XposedModule, video: Any?) {
        lastService?.let { service ->
            HookProbe.first(module, "directorCurrentVideoOnCallback", 5) {
                probeInfo(module, service)
            }
        }

        if (video == null) return
        val ids = extractVideoIds(module, video) ?: return
        pendingIds = ids
        pendingIdsAtMs = android.os.SystemClock.uptimeMillis()
        val contextHash = lastContextHash
        if (contextHash == 0) {
            HookProbe.first(module, "videoIdsWithoutContext", 3) { "aid=${ids.first} cid=${ids.second}（还没有容器绑定，已缓存）" }
            return
        }
        val sink = idSink
        if (sink == null) {
            HookProbe.first(module, "videoIdsWithoutSink", 3) { "aid=${ids.first} cid=${ids.second}（controller 未就绪）" }
            return
        }
        module.info(
            "videoDirector: FOUND aid=${ids.first} cid=${ids.second} context=$contextHash " +
                "(dispatch #${dispatchCount.incrementAndGet()})",
        )
        deliveredIdsByContext[contextHash] = ids
        sink(contextHash, ids.first, ids.second)
    }

    /**
     * 从最近一次注册的 director 服务上直接提取当前条目的 aid/cid。
     *
     * 供「state 缺失后的补绑」使用:补绑时进度回调已经在飞,但 director 回调不一定会再来
     * (dispatch 已经消费过、或 pendingIds 已过期),这里主动拉一次当前视频,
     * 补绑路径才能自愈。取不到返回 null(服务未注册/条目未就绪)。
     */
    fun currentIdsFromService(module: XposedModule): Pair<Long, Long>? {
        val service = lastService ?: return null
        val video = currentVideoOf(service) ?: return null
        return extractVideoIds(module, video)
    }

    private class ObserverHandler(private val module: XposedModule) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            return try {
                handle(proxy, method, args)
            } catch (t: Throwable) {
                module.info("director observer error: ${t.javaClass.name}: ${t.message}")
                null
            }
        }

        private fun handle(proxy: Any, method: Method, args: Array<out Any>?): Any? {
            if (method.declaringClass == Any::class.java) {
                return when (method.name) {
                    "toString" -> "Bili2233DirectorObserverProxy"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            HookProbe.first(module, "directorCallback", 12) {
                "${method.name}(${describeArgs(args)})"
            }
            if (args != null && args.isNotEmpty()) {
                dispatch(module, args[0])
            }
            return null
        }

        private fun describeArgs(args: Array<out Any>?): String {
            if (args == null) return ""
            val builder = StringBuilder()
            for (i in args.indices) {
                if (i > 0) builder.append(", ")
                @Suppress("SENSELESS_COMPARISON", "CONDITION_ALWAYS_FALSE")
                val arg = args[i]
                @Suppress("SENSELESS_COMPARISON", "CONDITION_ALWAYS_FALSE")
                if (arg == null) {
                    builder.append("null")
                } else {
                    builder.append(arg.javaClass.name)
                }
            }
            return builder.toString()
        }
    }
}

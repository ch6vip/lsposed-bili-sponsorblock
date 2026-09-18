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
 */
object VideoDirectorListener {
    /**
     * 已挂过代理的对象集合。
     *
     * 用「弱引用集合」而不是 `identityHashCode` 集合：identityHash 会被复用，
     * 一旦复用，新服务会被误判为"已注册"而**静默不再挂代理**（aid/cid 链路整体失效）；
     * 弱引用同时避免长期强引用 director 服务（它间接持有 Activity/View）。
     */
    private val registeredHosts: MutableSet<Any> =
        Collections.newSetFromMap(Collections.synchronizedMap(WeakHashMap<Any, Boolean>()))

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
        // 弱引用集合：同一对象重复注册直接复用；旧对象被回收后不会留下"假去重"
        if (!registeredHosts.add(directorService)) return true

        val classLoader = directorService.javaClass.classLoader ?: run {
            HookProbe.miss(module, "director", "classLoader null")
            return false
        }
        val observerInterface = HookResolve.findClass(
            classLoader,
            listOf(HostTargets.DIRECTOR_OBSERVER_INTERFACE, HostTargets.LEGACY_OBSERVER_INTERFACE),
        )
        if (observerInterface == null) {
            HookProbe.miss(module, "director", "observer interface not found")
            registeredHosts.remove(directorService)
            return false
        }

        val observer = Proxy.newProxyInstance(
            classLoader,
            arrayOf(observerInterface),
            ObserverHandler(module),
        )
        val addMethod = HookResolve.forTarget(
            directorService,
            HostTargets.DIRECTOR_ADD_OBSERVER_METHODS,
            observerInterface,
        )
        if (addMethod == null) {
            HookProbe.miss(module, "director", "addObserver method not found")
            registeredHosts.remove(directorService)
            return false
        }

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
            // 探针：服务实例上直接读一次当前视频，确认 D() -> Video$e -> Video$a 链路
            probeCurrentVideo(module, directorService)
        } else {
            registeredHosts.remove(directorService)
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
     * 播放器销毁时调用：断开 director 服务与待补发 id 的引用。
     *
     * 说明：注册集合里存的是 director **服务实例**（不是容器），这里不做按容器的精确移除，
     * 而是断开全局引用；旧服务由弱引用集合自动回收（避免长期持有 Activity/View）。
     */
    fun unregister(host: Any) {
        registeredHosts.remove(host)
        clearDirectorService()
        clearPendingIds()
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
        HookResolve.forTarget(directorService, listOf(HostTargets.DIRECTOR_CURRENT_VIDEO_METHOD))
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

        // 诊断：真机上取到的 id 量级可疑（1e14 级），打印实际对象/字段以确认字段语义
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

        // 兜底：逐字段找 long（aid 先出现，随后是 cid）。
        // 危险路径:任意「第一个/第二个非零且不相等的 long/int 字段」都可能是 duration、
        // epid、seasonId 等其它 id,误选会拉取并应用**错误视频**的片段(seek/静音/提交全错)。
        // 所以这里加两道校验:量级范围(aid/cid 是 1e8~1e13 级的 id,不是 1e3 级的时长,
        // 也不是 1e15+ 的 seasonId)+ 结果只经 probe 记录后采用。
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
            // 扫描到了但不合量级:宁可放弃这次 id 采集(下次 z() 主路径可能就绪),
            // 也不能拿错误的 id 去拉片段
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
        // 观察者回调是「播放条目已经就绪」的信号，顺便再探一次当前条目
        // （注册那一刻 D() 往往还是 null，真机已复现）
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
        // 不清 pendingIds:玩家重建(全屏切换/重进)会换新的 Context 实例(contextHash 变了),
        // 而这里的派发用的是「当时」的 lastContextHash —— 新 context 只能靠 bindPlayer 时的
        // flushPendingIds 补发。清了它,context 一换 ids 就断链,功能死到下一集
        // (2026-09-19 真机复现)。重发危害由 deliveredIdsByContext 按账去重兜住。
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
            // 这是宿主主动调用的代理对象：任何异常都会顺着宿主的调用栈抛出去（曾把宿主进程崩掉过），
            // 所以这里整体兜住，绝不向外抛。
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
            // E0: a() / b(current, previous) / c(video) / e(video)
            HookProbe.first(module, "directorCallback", 12) {
                "${method.name}(${describeArgs(args)})"
            }
            // 只处理第一个实参：`b(current, previous)` 的第二个参数是**上一个**条目，
            // 若也 dispatch，会把上一集的 aid/cid 覆盖成当前状态（用错视频的片段区间）。
            if (args != null && args.isNotEmpty()) {
                dispatch(module, args[0])
            }
            return null
        }

        /**
         * 安全描述实参：**不要用 `joinToString { it -> ... }`**。
         *
         * `args` 的元素在 Kotlin 视角是平台类型，但 `joinToString` 的 lambda 参数会被推断为非空类型，
         * 编译器会在 lambda 入口插 `checkNotNullParameter`；宿主回调里确实会出现 null 实参，
         * 于是探针自己抛 NPE 把宿主崩掉（真机已复现，见 docs/STATUS.md）。
         * 这里用数组下标读取（平台类型，无隐式非空检查）+ 显式 null 判断。
         */
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

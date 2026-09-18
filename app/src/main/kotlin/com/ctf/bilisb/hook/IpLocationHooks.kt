package com.ctf.bilisb.hook

import android.os.Handler
import android.os.HandlerThread
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.settings.EnhanceFlags
import com.ctf.bilisb.util.info
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 评论区与用户主页 IP 属地显示（移植自 BiliTamer (MIT) 的 IpLocationHooks.java）。
 *
 * 核心原理（BiliTamer 逆向结论，务必保留）：B 站服务端按请求身份
 * (mobi_app/build/channel/appId) 决定是否返回 IP 属地字段。国际版客户端默认以
 * android_i 身份请求，服务端不返回 location；本模块把请求身份改写为国内版
 * android_hd/2001100/master(appId=5, version=2.0.1)，服务端即返回 location 字段，
 * 而国际版 UI 已内置 IP 属地渲染。
 *
 * 6.3.0 落点（真实类名，jadx deobfuscation 会显示为 p488mq0.a / p061ip1.h）：
 *  - KMP moss gRPC（评论 Reply/ DmView 等走 KMossServiceImp -> ip1.h.a）：身份头
 *    x-bili-metadata-bin / x-bili-device-bin 由 up1.a.a()（KMetadata/KDevice 提供者）
 *    生成。hook up1.a.a() 在返回的 jp1.c(key, byte[]) 上直接改写 protobuf 字节中的
 *    mobiApp（android_i -> android，含长度前缀重建）。
 *  - 旧 moss / REST 路径：mq0.a.e()(Metadata) / d()(Device) 生成身份头，经 okhttp
 *    Aq0.a 注入；同步改写。
 * 6.4.0 / 6.5.0 漂移（见 installKmpHeaderValue / computeWantedService 候选表）：
 *  提供者基类 up1.a -> kr1.a -> kr1.d；包装 jp1.c -> Zq1.c -> kr1.c；方法描述符
 *  jp1.g -> Zq1.g -> kr1.g（字段语义 a=包名/b=服务名/c=方法名不变）。拦截器
 *  kntr.base.moss.ignet.impl.header.b 与 MossInterceptor$e/grpc.c 为真名，跨版本稳定。
 *  6.5.0 主改写路径：kntr.base.moss.ignet.impl.grpc.c.f(String, byte[]) 是二进制头
 *  入存储的唯一入口（类为真名，6.3.0-6.5.0 未漂移；6.5.0 起提供者层变为接口 kr1.d
 *  + 5 个具体类，无单点可 hook，此层是正解）。
 *
 * 与源码的刻意差异：
 *  1. BiliTamer 有 IP_SCOPE_COMMENT / IP_SCOPE_ALL 双模式；本项目开关只有布尔
 *     [com.ctf.bilisb.settings.SettingsSnapshot.ipLocation]，移植后恒为「评论区限定」
 *     语义：评论/空间/主页服务按 ThreadLocal scope 武装 + 空间页 UI 时间窗。
 *  2. 本项目无 verbose 日志开关：verbose 分支去掉，「每类首条改写必打」的探针
 *     （probeKmp/probeCommon/probeIdp/probeRest/probeGrpc 等）保留为进程内一次性日志
 *     —— 这是真机确认活体的唯一手段。
 *  3. 开关关闭时所有 hook 直接放行原调用（scope 类 hook 也加了提前放行；源码部分
 *     hooker 不检查开关、靠下游消费者检查，语义等价）。
 *  4. 源码 rewriteHeaderEntry(Map,String) 是无调用点的死代码，未移植。
 *
 * 每组 hook 独立安装 + 类未加载时延迟重试（上限 30 次、500ms 间隔，自建后台线程）。
 */
object IpLocationHooks {

    /** URL 探针去重集合软上限。 */
    private const val URL_PROBE_CAP = 64


    // 国内版评论客户端身份（与国内版 HD 一致）
    private const val MOBI_APP = "android_hd"
    private const val BUILD = 2001100
    private const val CHANNEL = "master"
    private const val APP_ID = 5
    private const val VERSION_NAME = "2.0.1"

    // 评论 RPC 服务与方法
    private const val REPLY_SERVICE = "bilibili.main.community.reply.v1"
    private val REPLY_METHODS = arrayOf(
        "MainList", "DetailList", "DialogList", "PreviewList", "ReplyInfo",
        "SearchItem", "SearchItemPreHook", "ShareRepliesInfo", "FoldList", "HotspotPage",
    )

    // MossCommonHeadersProvider（kntr.base.moss.ignet.impl.header.j）
    private const val COMMON_HEADERS_CLS = "kntr.base.moss.ignet.impl.header.b"

    private const val RETRY_DELAY_MS = 500L
    private const val MAX_RETRY = 30

    /** 空间页 UI 定域窗口：页面打开后的放行时长（毫秒）。 */
    private const val SPACE_UI_WINDOW_MS = 15000L

    private const val HDR_METADATA_BIN = "x-bili-metadata-bin"
    private const val HDR_DEVICE_BIN = "x-bili-device-bin"

    // ------------------------------------------------------------------ 开关

    /** hook 回调内实时读快照（不缓存布尔值），保证热生效。 */
    private fun enabled(module: XposedModule): Boolean =
        runCatching { EnhanceFlags.snapshot(module).ipLocation }.getOrDefault(false)

    // ------------------------------------------------------------------ 安装编排

    fun install(module: XposedModule, cl: ClassLoader) {
        installGroup(module, "ip.restIdentity") { installRest(module, cl) }
        // moss 部分：立即尝试，失败则延迟重试
        installMossScope(module, cl)
        installIdentityProvider(module, cl)
        installKmpHeaderValue(module, cl)
        installRestParams(module, cl)
        installCommonHeadersScope(module, cl)
        installGrpcBinHeaderWrite(module, cl)
        module.info("IpLocationHooks installed")
    }

    private inline fun installGroup(module: XposedModule, name: String, block: () -> Unit) {
        runCatching { block() }
            .onSuccess { module.info("ip: hook group ready: $name") }
            .onFailure { t ->
                HookProbe.miss(module, name, "hook group unavailable: ${t.javaClass.simpleName}: ${t.message}")
            }
    }

    /** 延迟重试用的自建后台 handler：重试会做类加载 + 全类方法扫描，不能压主线程。 */
    private val retryHandler: Handler by lazy {
        val thread = HandlerThread("BiliSB-IpRetry")
        thread.isDaemon = true
        thread.start()
        Handler(thread.looper)
    }

    private fun retry(module: XposedModule, tag: String, block: () -> Unit) {
        runCatching { retryHandler.postDelayed(block, RETRY_DELAY_MS) }
            .onFailure { t -> module.warn("ip: retry scheduling failed ($tag): ${t.message}") }
    }

    // ------------------------------------------------------------------ 状态 / 限流集合

    private val mossScopeReady = AtomicBoolean(false)
    private val mossScopeAttempts = AtomicInteger(0)
    private val identityReady = AtomicBoolean(false)
    private val identityAttempts = AtomicInteger(0)
    private val kmpHeaderReady = AtomicBoolean(false)
    private val kmpHeaderAttempts = AtomicInteger(0)
    private val restParamsAttempts = AtomicInteger(0)
    private val grpcWriteReady = AtomicBoolean(false)
    private val grpcWriteAttempts = AtomicInteger(0)

    /** 运行时探针：每类改写的第一条必打一行（进程生命周期内），确认活体。 */
    private val probeKmp = AtomicBoolean(false)
    private val probeCommon = AtomicBoolean(false)
    private val probeIdp = AtomicBoolean(false)
    private val probeRest = AtomicBoolean(false)
    private val probeGrpc = AtomicBoolean(false)
    private val probeKmpEntry = AtomicBoolean(false)
    private val probeGrpcEntry = AtomicBoolean(false)
    private val probeIdpFire = AtomicBoolean(false)
    private val spaceParamFired = AtomicBoolean(false)

    /** 探针：j.a 观察到的服务名（每服务名只记一次，上限防刷屏）。 */
    private val seenServices: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet<String>())

    /** URL 探针去重（每 URL 前缀只记一次；feed 类 URL 的 query 在变,须有软上限防无界增长）。 */
    private val urlProbeSeen: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    /** 记一次 URL 前缀(超过软上限整体清空,探针重打一轮无害)。 */
    private fun rememberUrlProbe(prefix: String): Boolean {
        if (urlProbeSeen.size >= URL_PROBE_CAP) {
            urlProbeSeen.clear()
        }
        return urlProbeSeen.add(prefix)
    }

    /** 同一问题每次进程只报一次，避免刷屏。 */
    private val loggedHdrTypes: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    private val mossRpcCount = AtomicLong(0)

    /** 全量 Activity 探针：记录去重类名（cap 40）。 */
    private val seenActivities: MutableSet<String> =
        Collections.synchronizedSet(HashSet<String>())

    /** 评论区限定模式：moss 发送入口 ip1.h.a 标记的本次 RPC 服务名。 */
    private val sScope = ThreadLocal<String>()

    /**
     * 评论区限定模式：moss-common-headers 拦截器在 proceed 前设置的本次 RPC 服务名。
     * up1.a.a() 在该拦截器内部被同步调用（同线程），凭此标记精确改写。
     */
    private val sCommonScope = ThreadLocal<String>()

    /**
     * 空间页 UI 定域：页面打开后的时间窗（毫秒时间戳），窗口内 kr1.a.a 全部改写。
     * 6.4.0 空间 REST 走 kntr 直连 provider，不经过 header.b，svc 无法定位，只能按 UI 定位。
     */
    @Volatile
    private var sUiSpaceUntil = 0L

    // ------------------------------------------------------------------ moss scope（KMP 发送入口）

    /** 标记当前线程为评论 RPC scope（KMP moss 发送入口 ip1.h.a）。 */
    private fun installMossScope(module: XposedModule, cl: ClassLoader) {
        if (mossScopeReady.get()) return
        if (mossScopeAttempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "ip.mossScope", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            val ip1h = Class.forName("ip1.h", false, cl)
            val m = ip1h.declaredMethods.firstOrNull { it.name == "a" && it.parameterTypes.size == 4 }
                ?: throw NoSuchMethodException("ip1.h.a(4-arg) not found")
            runCatching { m.isAccessible = true }
            runCatching { module.deoptimize(m) }
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // 决策与 proceed 分离：判定（含开关）异常时退化为放行
                    val scopeKind = runCatching {
                        val g = chain.getArg(0) ?: return@runCatching null
                        if (!enabled(module)) return@runCatching null
                        val svc = strField(g, "a")
                        val method = strField(g, "c")
                        if (svc != null && REPLY_SERVICE == svc && isReplyMethod(method)) {
                            "rpc:$method"
                        } else {
                            null
                        }
                    }.getOrNull()
                    if (scopeKind == null) return@intercept chain.proceed()
                    val old = sScope.get()
                    if (old == null) {
                        sScope.set(scopeKind)
                        try {
                            return@intercept chain.proceed()
                        } finally {
                            sScope.remove()
                        }
                    } else {
                        try {
                            return@intercept chain.proceed()
                        } finally {
                            sScope.set(old)
                        }
                    }
                }
            mossScopeReady.set(true)
            HookProbe.ok(module, "ip.mossScope", "${ip1h.name}.a (attempt=${mossScopeAttempts.get()})")
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "ip.mossScopeRetry", 3) {
                "class not loaded yet, retry in ${RETRY_DELAY_MS}ms attempt=${mossScopeAttempts.get()}"
            }
            retry(module, "mossScope") { installMossScope(module, cl) }
        } catch (t: Throwable) {
            HookProbe.miss(module, "ip.mossScope", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ KMP 头提供者兜底

    /**
     * KMP KMetadata/KDevice 头提供者：a() 返回头包装（String key + byte[] value）。
     * 这是评论 gRPC 的 x-bili-metadata-bin / x-bili-device-bin 实际来源。
     * hook 后在返回的字节上改写 mobiApp（android_i -> android）。
     *
     * 提供者 hook 仅作为兜底（主改写点见 installGrpcBinHeaderWrite 的上下文头存储直改，
     * 那条路全用真名类，跨版本稳定）。提供者基类随构建漂移（BiliTamer PITFALLS #3/#16）：
     * 6.3.0=up1.a（具体类）；6.4.0=kr1.a（final a() 具体方法）；6.5.0 起变成接口
     * kr1.d + 抽象中转 vr1.a + 5 个具体提供者，无单点可 hook，故 6.5.0 上本兜底
     * 自然弃用（形状校验 + 抽象拒绝让它安静跳过）。6.5.0 的 up1.a 已被无关类占用，
     * 仅凭方法名 a 会挂错类——必须过形状校验。
     */
    private fun installKmpHeaderValue(module: XposedModule, cl: ClassLoader) {
        if (kmpHeaderReady.get()) return
        if (kmpHeaderAttempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "ip.kmpHeaderValue", "give up after $MAX_RETRY attempts")
            return
        }
        var cls: Class<*>? = null
        var clsUsed: String? = null
        for (cn in listOf("kr1.a", "up1.a")) {
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: continue
            if (!isHeaderProviderBase(c)) {
                module.info("ip: kmp provider candidate $cn shape mismatch, skip")
                continue
            }
            cls = c
            clsUsed = cn
            break
        }
        if (cls == null) {
            if (kmpHeaderAttempts.get() >= MAX_RETRY) {
                HookProbe.miss(module, "ip.kmpHeaderValue", "give up (no provider candidate found)")
            } else {
                HookProbe.first(module, "ip.kmpHeaderValueRetry", 3) {
                    "providers not present yet, retry in ${RETRY_DELAY_MS}ms attempt=${kmpHeaderAttempts.get()}"
                }
                retry(module, "kmpHeaderValue") { installKmpHeaderValue(module, cl) }
            }
            return
        }
        try {
            // 形状校验已保证存在非抽象 0 参 a()
            val m = cls.declaredMethods.firstOrNull {
                it.name == "a" && it.parameterTypes.isEmpty() && !Modifier.isAbstract(it.modifiers)
            } ?: throw NoSuchMethodException("${cls.name}.a(0-arg) not found")
            runCatching { m.isAccessible = true }
            runCatching { module.deoptimize(m) }
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        if (!enabled(module)) return@runCatching
                        // 本移植恒为评论区限定模式：仅当本次 RPC 已被 common-headers scope 武装时改写
                        if (sCommonScope.get() == null) {
                            // 空间页 UI 定域：窗口内放行（6.4.0 空间通道不经过 header.b，无法按 svc 定位）
                            if (System.currentTimeMillis() >= sUiSpaceUntil) return@runCatching
                            if (probeKmpEntry.compareAndSet(false, true)) {
                                module.info("ip: kmp hook fired (ui:space window)")
                            }
                        }
                        if (result == null) return@runCatching
                        if (probeKmpEntry.compareAndSet(false, true)) {
                            var k0: String? = null
                            var len0 = -1
                            var hasOld = false
                            for (f0 in result.javaClass.declaredFields) {
                                runCatching {
                                    f0.isAccessible = true
                                    val v = f0.get(result)
                                    if (f0.type == String::class.java && k0 == null && v != null) {
                                        k0 = v.toString()
                                    }
                                    if (f0.type == ByteArray::class.java && v is ByteArray) {
                                        len0 = v.size
                                        hasOld = indexOfBytes(v, "android_i") >= 0
                                    }
                                }
                            }
                            module.info(
                                "ip: kmp hook fired key=$k0 bytes=$len0 " +
                                    "containsAndroidI=$hasOld scope=${sCommonScope.get()}",
                            )
                        }
                        // 头包装 = 第一个 String 字段(key) + byte[] 字段(value)
                        var keyStr: String? = null
                        var valF: Field? = null
                        for (f in result.javaClass.declaredFields) {
                            runCatching { f.isAccessible = true }
                            if (f.type == String::class.java && keyStr == null) {
                                val k = runCatching { f.get(result) }.getOrNull()
                                keyStr = k?.toString()
                            } else if (f.type == ByteArray::class.java) {
                                valF = f
                            }
                        }
                        if (keyStr != null && valF != null &&
                            (keyStr == HDR_METADATA_BIN || keyStr == HDR_DEVICE_BIN)
                        ) {
                            val src = valF?.let { runCatching { it.get(result) }.getOrNull() }
                            if (src is ByteArray) {
                                val out = rewriteMobiAppBytes(src)
                                if (out != null) {
                                    runCatching { valF?.set(result, out) }
                                    logRewrite(module, probeKmp, "kmp $keyStr 改写生效")
                                }
                            }
                        }
                    }.onFailure { t ->
                        module.warn("ip: kmp header value rewrite failed: ${t.message}")
                    }
                    result
                }
            kmpHeaderReady.set(true)
            HookProbe.ok(module, "ip.kmpHeaderValue", "$clsUsed.a (attempt=${kmpHeaderAttempts.get()})")
        } catch (t: Throwable) {
            HookProbe.miss(module, "ip.kmpHeaderValue", "hook failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 提供者基类形状校验：存在无参且非抽象的 a()，其返回类型带 (String, byte[]) 构造器
     * （= 头包装）。抽象 a() 不能 hook（6.5.0 kr1.d 变接口即此形态）；
     * 单字母类名跨构建会撞名（6.5.0 up1.a 已被无关类占用），仅凭名字会把 hook
     * 挂到不相关类上且静默无效。
     */
    private fun isHeaderProviderBase(c: Class<*>): Boolean {
        return try {
            for (mm in c.declaredMethods) {
                if (mm.name != "a" || mm.parameterTypes.isNotEmpty()) continue
                if (Modifier.isAbstract(mm.modifiers)) continue
                val rt = mm.returnType
                if (rt.isPrimitive || rt.isArray || rt == String::class.java) continue
                for (k in rt.declaredConstructors) {
                    val ps = k.parameterTypes
                    if (ps.size == 2 && ps[0] == String::class.java && ps[1] == ByteArray::class.java) {
                        return true
                    }
                }
            }
            false
        } catch (ignored: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------ moss-common-headers 拦截器 scope

    /**
     * 评论区限定身份改写（BiliTamer v1.3，方向2 的正解）。
     *
     * 挂点：MossCommonHeadersProvider 拦截器（kntr.base.moss.ignet.impl.header.b，name=
     * "moss-common-headers"，priority 0）。它是 GrpcEngine 拦截器链的一员，b(chain, cont)
     * 内部同步遍历 jp1.b/jp1.d 头提供者（含 up1.a -> x-bili-metadata-bin / x-bili-device-bin）
     * 把头写进本次调用上下文 grpc.c 的头存储（grpc.d.a=String 头 / d.b=byte[] 头），
     * 然后 chain.proceed() 交给后续拦截器。因此：
     *  - proceed 返回后头存储里必有本次请求的最终身份头（可改）；
     *  - chain.a() 即 grpc.c（继承 MossInterceptor.e，字段 b=jp1.g method 描述符），
     *    service/method 判定与改写同线程同帧，无跨线程问题；
     *  - 一元 RPC 走这里；stream tunnel 走 header.j（不受影响）。
     *
     * 方法为 suspend，可能在后续拦截器处挂起返回 COROUTINE_SUSPENDED——但头在本拦截器
     * proceed 之前已入存储，两种返回形态下改写同样有效（重复进入幂等）。
     */
    private fun installCommonHeadersScope(module: XposedModule, cl: ClassLoader) {
        try {
            val cls = Class.forName(COMMON_HEADERS_CLS, false, cl)
            val m = cls.declaredMethods.firstOrNull {
                it.name == "b" && it.parameterTypes.size == 2 && !it.isSynthetic
            } ?: throw NoSuchMethodException("$COMMON_HEADERS_CLS.b(2-arg) not found")
            runCatching { m.isAccessible = true }
            runCatching { module.deoptimize(m) }
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    // up1/kr1 提供者在原始 b() 体内被同步调用：proceed 前设 ThreadLocal 标记，
                    // 供 grpc.c.f 改写点与 6.3.0/6.4.0 提供者兜底 hook 使用。
                    // 注意：本 hook 的 pre-proceed 阶段跑在原始方法体（提供者写头）之前，
                    // 此时上下文头存储还是空的——改写不能落在这里。
                    val want = runCatching {
                        val n = mossRpcCount.incrementAndGet()
                        if (n % 200 == 1L) {
                            module.info("ip: moss rpc count=$n")
                        }
                        computeWantedService(module, chain.getArg(0))
                    }.getOrElse { t ->
                        module.warn("ip: common headers scope callback failed: ${t.javaClass.simpleName}: ${t.message}")
                        null
                    }
                    val old = sCommonScope.get()
                    if (want != null) sCommonScope.set(want)
                    try {
                        return@intercept chain.proceed()
                    } finally {
                        if (want != null) {
                            if (old == null) sCommonScope.remove() else sCommonScope.set(old)
                        }
                    }
                }
            HookProbe.ok(module, "ip.commonHeadersScope", "${cls.name}.b")
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "ip.commonHeadersScopeRetry", 3) {
                "$COMMON_HEADERS_CLS not loaded yet, retry in ${RETRY_DELAY_MS}ms"
            }
            retry(module, "commonHeadersScope") { installCommonHeadersScope(module, cl) }
        } catch (t: Throwable) {
            HookProbe.miss(module, "ip.commonHeadersScope", "hook failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 解析 chain（MossInterceptor$b）上的 grpc 上下文：a() 返回 MossInterceptor$e，
     *  其实现（grpc.c）即持有描述符与头存储的同一实例。 */
    private fun resolveCtx(chainObj: Any?): Any? {
        if (chainObj == null) return null
        var ctx = callNoArg(chainObj, "a", "MossInterceptor\$e")
        if (ctx == null) ctx = callNoArg(chainObj, "a", "ignet.impl.grpc.c")
        if (ctx == null) ctx = fieldInHierarchy(chainObj, "a")
        return ctx
    }

    /** 判定本次 RPC 是否需要改写身份：需要则返回服务名（作 ThreadLocal 标记），否则 null。
     *  在 chain.proceed() 之前调用；改写本身由 grpc.c.f / up1.a.a() hook 完成。 */
    private fun computeWantedService(module: XposedModule, chainObj: Any?): String? {
        return try {
            if (chainObj == null) return null
            val ip = enabled(module)
            if (!ip) return null
            // chain -> grpc.c 上下文（MossInterceptor$b.a()）
            val ctx = resolveCtx(chainObj)
            if (ctx == null) {
                logRewriteOnce(module, "ctx", "ip: common headers ctx not found (chain=${chainObj.javaClass.name})")
                return null
            }
            // 方法描述符随构建漂移：6.3.0=jp1.g / 6.4.0=Zq1.g / 6.5.0=kr1.g。
            // 字段语义一致（a=packageName, b=serviceName, c=methodName，kr1.g 经
            // KMethodDescriptor toString 实证），故只按类型名提示逐一尝试。
            val g = fieldTypedAnyHint(ctx, "b", arrayOf("kr1.g", "Zq1.g", "jp1.g"))
            // 6.3.0 jp1.g：service 在字段 a；6.4.0/6.5.0：a=packageName, b=serviceName，
            // c=methodName —— 语义移位过，两个都试，取像服务名的那个
            var svc = if (g == null) null else strField(g, "b")
            val method = if (g == null) null else strField(g, "c")
            if (!isReplyService(svc)) {
                val alt = strField(g, "a")
                if (isReplyService(alt)) svc = alt
            }
            if (svc == null) {
                // 兜底：k 也有服务名字段（6.3.0=jp1.k / 6.4.0=Zq1.k / 6.5.0=kr1.k）
                val k = fieldTypedAnyHint(ctx, "a", arrayOf("kr1.k", "Zq1.k", "jp1.k"))
                svc = if (k == null) null else strField(k, "a")
            }
            if (svc == null && g != null) {
                logRewriteOnce(
                    module, "svc",
                    "ip: method descriptor resolved but no service string (cls=${g.javaClass.name})",
                )
            }
            val pkg = strField(g, "a")
            rememberService(module, svc, pkg)
            // 评论区限定：评论区 + 空间页 + 主页（各按服务名/包名识别，不做全局声明）
            val want = ip && (isReplyService(svc) || isSpaceService(svc, pkg) || isHomeService(svc, pkg))
            if (!want) return null
            logRewrite(module, probeCommon, "common-headers 评论区限定改写待生效: $svc (method=$method)")
            svc
        } catch (t: Throwable) {
            module.warn("ip: computeWantedService failed: ${t.message}")
            null
        }
    }

    // ------------------------------------------------------------------ grpc.c.f 二进制头写入（6.5.0 主改写路径）

    /**
     * 6.5.0 主改写路径：hook kntr.base.moss.ignet.impl.grpc.c.f(String, byte[])。
     * header.b.b() 原始方法体内，每个二进制提供者产出头包装后经 ctx.f(key, bytes)
     * 写入存储（grpc.d），然后才 proceed 发请求——f 是二进制头入存储的唯一入口，
     * 参数替换即等于改写请求头，时序必然赶得上。类为真名，6.3.0-6.5.0 未漂移。
     * （6.5.0 起提供者层变为接口 kr1.d + 5 个具体类，无单点可 hook，此层是正解。）
     */
    private fun installGrpcBinHeaderWrite(module: XposedModule, cl: ClassLoader) {
        if (grpcWriteReady.get()) return
        if (grpcWriteAttempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "ip.grpcBinHeaderWrite", "give up after $MAX_RETRY attempts")
            return
        }
        val f: Method = try {
            val grpcC = Class.forName("kntr.base.moss.ignet.impl.grpc.c", false, cl)
            grpcC.declaredMethods.firstOrNull { mm ->
                mm.name == "f" &&
                    mm.parameterTypes.size == 2 &&
                    mm.parameterTypes[0] == String::class.java &&
                    mm.parameterTypes[1] == ByteArray::class.java
            } ?: throw NoSuchMethodException("grpc.c.f(String,byte[]) not found")
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "ip.grpcBinHeaderWriteRetry", 3) {
                "grpc.c not loaded yet, retry in ${RETRY_DELAY_MS}ms attempt=${grpcWriteAttempts.get()}"
            }
            retry(module, "grpcBinHeaderWrite") { installGrpcBinHeaderWrite(module, cl) }
            return
        } catch (t: Throwable) {
            HookProbe.miss(module, "ip.grpcBinHeaderWrite", "resolve failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        try {
            runCatching { f.isAccessible = true }
            runCatching { module.deoptimize(f) }
            module.hook(f)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val args = chain.getArgs()
                    var replace: ByteArray? = null
                    runCatching {
                        val key = args.getOrNull(0)
                        if (key !is String) return@runCatching
                        if (key != HDR_METADATA_BIN && key != HDR_DEVICE_BIN) return@runCatching
                        if (!enabled(module)) return@runCatching
                        // 评论区限定（本移植恒为限定模式）：仅头 scope 标记在身的请求；
                        // 空间页 moss 通道走 UI 时间窗
                        if (sCommonScope.get() == null) {
                            if (System.currentTimeMillis() >= sUiSpaceUntil) return@runCatching
                        }
                        val v = args.getOrNull(1)
                        if (v !is ByteArray) return@runCatching
                        if (probeGrpcEntry.compareAndSet(false, true)) {
                            module.info("ip: grpc write fired key=$key bytes=${v.size} scope=${sCommonScope.get()}")
                        }
                        val out = rewriteMobiAppBytes(v)
                        if (out == null) return@runCatching
                        logRewrite(module, probeGrpc, "grpc $key 改写生效")
                        replace = out
                    }.onFailure { t ->
                        // 改写失败退化为放行原参数，绝不二次 proceed
                        replace = null
                        module.warn("ip: grpc bin header write callback failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    val out = replace
                    val key = args.getOrNull(0)
                    if (out != null && key is String) {
                        chain.proceed(arrayOf<Any?>(key, out))
                    } else {
                        chain.proceed()
                    }
                }
            grpcWriteReady.set(true)
            HookProbe.ok(module, "ip.grpcBinHeaderWrite", "kntr.base.moss.ignet.impl.grpc.c.f")
        } catch (t: Throwable) {
            HookProbe.miss(module, "ip.grpcBinHeaderWrite", "hook failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 旧 moss / REST 身份 provider

    /** 改写 Metadata/Device 身份头（旧 moss / REST 路径）。 */
    private fun installIdentityProvider(module: XposedModule, cl: ClassLoader) {
        if (identityReady.get()) return
        if (identityAttempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "ip.identityProvider", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            try {
                installByteProvider(module, cl, "mq0.a", "e", true)  // metadata (6.3.0)
                installByteProvider(module, cl, "mq0.a", "d", false) // device
            } catch (oldMissing: Throwable) {
                // 6.4.0: mq0.a 另作他用；REST 身份 provider 迁到 oq0.a（e/d 同名，
                // 见 Cq0.a.intercept 对 oq0.a.e()/d() 的调用）
                installByteProvider(module, cl, "oq0.a", "e", true)  // metadata (6.4.0)
                installByteProvider(module, cl, "oq0.a", "d", false) // device
            }
            identityReady.set(true)
            HookProbe.ok(module, "ip.identityProvider", "mq0.a|oq0.a e/d (attempt=${identityAttempts.get()})")
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "ip.identityProviderRetry", 3) {
                "identity class not loaded yet, retry in ${RETRY_DELAY_MS}ms attempt=${identityAttempts.get()}"
            }
            retry(module, "identityProvider") { installIdentityProvider(module, cl) }
        } catch (t: Throwable) {
            HookProbe.miss(module, "ip.identityProvider", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun installByteProvider(
        module: XposedModule,
        cl: ClassLoader,
        clsName: String,
        methodName: String,
        isMetadata: Boolean,
    ) {
        val c = Class.forName(clsName, false, cl)
        val m = c.declaredMethods.firstOrNull { it.name == methodName }
            ?: throw NoSuchMethodException("$clsName.$methodName not found")
        runCatching { m.isAccessible = true }
        runCatching { module.deoptimize(m) }
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val result = chain.proceed()
                val rewritten = runCatching {
                    if (!enabled(module)) return@runCatching null
                    if (probeIdpFire.compareAndSet(false, true)) {
                        module.info("ip: identity provider fired $clsName.$methodName scope=${sScope.get()}")
                    }
                    if (sScope.get() == null) return@runCatching null
                    if (result !is ByteArray) return@runCatching null
                    rewriteIdentity(module, cl, result, isMetadata)?.also {
                        logRewrite(module, probeIdp, "identity/$methodName 改写生效")
                    }
                }.getOrNull()
                rewritten ?: result
            }
        module.info("ip: identity provider hook ok -> $clsName.$methodName")
    }

    /** 用 protobuf 解析并改写身份字段（旧 moss / REST）。
     *  6.4.0 proto 类改名：Metadata->KMetadata、Device->KDevice；类加载失败时
     *  兜底用字节级 mobi_app 替换（长度前缀校验，安全幂等）。 */
    private fun rewriteIdentity(module: XposedModule, cl: ClassLoader, src: ByteArray, isMetadata: Boolean): ByteArray? {
        val candidates = if (isMetadata) {
            arrayOf("com.bapis.bilibili.metadata.Metadata", "com.bapis.bilibili.metadata.KMetadata")
        } else {
            arrayOf("com.bapis.bilibili.metadata.device.Device", "com.bapis.bilibili.metadata.device.KDevice")
        }
        try {
            for (cn in candidates) {
                val cls = loadQuiet(cl, cn) ?: continue
                val msg = invokeStatic(cls, "parseFrom", arrayOf<Class<*>>(ByteArray::class.java), src) ?: continue
                val builder = call(msg, "toBuilder") ?: continue
                call(builder, "setMobiApp", MOBI_APP)
                call(builder, "setBuild", BUILD)
                call(builder, "setChannel", CHANNEL)
                if (!isMetadata) {
                    call(builder, "setAppId", APP_ID)
                    call(builder, "setVersionName", VERSION_NAME)
                }
                val built = call(builder, "build") ?: continue
                val out = call(built, "toByteArray")
                if (out is ByteArray) return out
            }
        } catch (t: Throwable) {
            module.warn("ip: protobuf rewrite: ${t.message}")
        }
        return rewriteMobiAppBytes(src)
    }

    // ------------------------------------------------------------------ REST 拦截器 scope

    /** REST 评论/主页：按 URL 判定并改写 okhttp 请求参数。
     *  6.3.0: Aq0.a.intercept；6.4.0: Aq0.a.intercept 消失，okhttp 拦截器迁到 Cq0.a。 */
    private fun installRest(module: XposedModule, cl: ClassLoader) {
        installNewCallProbe(module, cl)
        installSpaceUiScope(module, cl)
        installActivityProbe(module, cl)
        installSpaceRestParams(module, cl)
        var restCls: Class<*>? = null
        var restClsUsed: String? = null
        for (cn in listOf("Aq0.a", "Cq0.a")) {
            val c = runCatching { Class.forName(cn, false, cl) }.getOrNull() ?: continue
            val has = c.declaredMethods.any { it.name == "intercept" && it.parameterTypes.size == 1 }
            if (has) {
                restCls = c
                restClsUsed = cn
                break
            }
        }
        if (restCls == null) {
            HookProbe.miss(module, "ip.restInterceptor", "rest interceptor (Aq0.a/Cq0.a) not present; skip")
            return
        }
        val m = restCls.declaredMethods.firstOrNull { it.name == "intercept" && it.parameterTypes.size == 1 }
            ?: run {
                HookProbe.miss(module, "ip.restInterceptor", "${restCls.name}.intercept(1-arg) not found")
                return
            }
        runCatching { m.isAccessible = true }
        runCatching { module.deoptimize(m) }
        val chainCls = m.parameterTypes[0]
        val reqMethod = reqMethodOf(chainCls)
        val urlMethod = urlMethodOfRequest(reqMethod)
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                // 决策与 proceed 分离：判定异常时退化为放行
                val kind = runCatching {
                    if (!enabled(module)) return@runCatching null
                    val chainObj = chain.getArg(0)
                    val req = reqMethod?.let { rm -> runCatching { rm.invoke(chainObj) }.getOrNull() }
                    val url = urlString(urlMethod, req)
                    if (url != null) {
                        val low = url.lowercase()
                        if ((low.contains("space") || low.contains("feed") || low.contains("region")) &&
                            rememberUrlProbe(low.take(120))
                        ) {
                            module.info("ip: rest url probe: ${url.take(160)}")
                        }
                    }
                    classifyRest(url)
                }.getOrNull()
                if (kind == null) return@intercept chain.proceed()
                val old = sScope.get()
                if (old == null) {
                    sScope.set(kind)
                    try {
                        return@intercept chain.proceed()
                    } finally {
                        sScope.remove()
                    }
                } else {
                    try {
                        return@intercept chain.proceed()
                    } finally {
                        sScope.set(old)
                    }
                }
            }
        HookProbe.ok(module, "ip.restInterceptor", "$restClsUsed.intercept")
    }

    /** REST 公共参数注入点：XA0.a 是 okretro 所有参数拦截器的基类。
     *  addCommonParamToUrl(t, z.a) 接收 URL 并在内部调用 addCommonParam(Map)（同线程）。
     *  hook addCommonParamToUrl 记录 URL（ThreadLocal），
     *  hook addCommonParam 按 URL 判定主页/评论并改写 map。
     *  主页 -> android（国内版普通），评论 -> android。 */
    private fun installRestParams(module: XposedModule, cl: ClassLoader) {
        if (restParamsAttempts.incrementAndGet() > MAX_RETRY) {
            // 6.4.0 okretro 参数基类移除；REST 公共参数路径已由 Cq0.a 拦截器覆盖
            HookProbe.miss(module, "ip.restParams", "rest params (XA0.a) not present in this version; give up")
            return
        }
        try {
            val xa0 = Class.forName("XA0.a", false, cl)
            // addCommonParamToUrl(t, z.a)：记录 URL
            val toUrl = xa0.declaredMethods.firstOrNull {
                it.name == "addCommonParamToUrl" && it.parameterTypes.size == 2
            }
            if (toUrl != null) {
                runCatching { toUrl.isAccessible = true }
                runCatching { module.deoptimize(toUrl) }
                module.hook(toUrl)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val kind = runCatching {
                            if (!enabled(module)) return@runCatching null
                            val tVar = chain.getArg(0)
                            classifyRest(tVar?.toString())
                        }.getOrNull()
                        if (kind == null) return@intercept chain.proceed()
                        val old = sScope.get()
                        if (old == null) {
                            sScope.set(kind)
                            try {
                                return@intercept chain.proceed()
                            } finally {
                                sScope.remove()
                            }
                        } else {
                            try {
                                return@intercept chain.proceed()
                            } finally {
                                sScope.set(old)
                            }
                        }
                    }
                module.info("ip: rest url scope hook ok")
            }
            // addCommonParam(Map)：改写 mobi_app/build/channel
            val acp = xa0.declaredMethods.firstOrNull {
                it.name == "addCommonParam" && it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == java.util.Map::class.java
            }
            if (acp != null) {
                runCatching { acp.isAccessible = true }
                runCatching { module.deoptimize(acp) }
                module.hook(acp)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            if (!enabled(module)) return@runCatching
                            val scope = sScope.get() ?: return@runCatching
                            val arg = chain.getArg(0)
                            if (arg is Map<*, *>) {
                                @Suppress("UNCHECKED_CAST")
                                val map = arg as MutableMap<Any?, Any?>
                                if (scope.startsWith("profile")) {
                                    map["mobi_app"] = "android"
                                    map["build"] = BUILD.toString()
                                    map["channel"] = "master"
                                    logRewrite(module, probeRest, "rest/profile 改写生效")
                                } else if (scope.startsWith("comment")) {
                                    map["mobi_app"] = "android"
                                    map["build"] = BUILD.toString()
                                    map["channel"] = CHANNEL
                                    logRewrite(module, probeRest, "rest/comment 改写生效")
                                }
                            }
                        }
                        result
                    }
                module.info("ip: rest params hook ok -> XA0.a.addCommonParam")
            }
            if (toUrl == null && acp == null) {
                HookProbe.miss(module, "ip.restParams", "XA0.a has neither addCommonParamToUrl nor addCommonParam(Map)")
            } else {
                HookProbe.ok(module, "ip.restParams", "XA0.a addCommonParamToUrl=${toUrl != null} addCommonParam=${acp != null}")
            }
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "ip.restParamsRetry", 3) {
                "XA0.a not loaded yet, retry attempt=${restParamsAttempts.get()}"
            }
            retry(module, "restParams") { installRestParams(module, cl) }
        } catch (t: Throwable) {
            HookProbe.miss(module, "ip.restParams", "hook failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 空间页 REST 参数

    /** 6.4.0 空间页 REST 参数改写：空间身份走 URL 参数（mobi_app=android_i），
     *  不走 moss/proto 头。挂空间页专属拦截器 e.addCommonParam（天然定域：只有空间请求经过它）。 */
    private fun installSpaceRestParams(module: XposedModule, cl: ClassLoader) {
        val cand = arrayOf("com.bilibili.app.comm.list.common.api.e")
        for (cn in cand) {
            try {
                val c = Class.forName(cn, false, cl)
                val m = c.declaredMethods.firstOrNull { mm ->
                    mm.name == "addCommonParam" &&
                        mm.parameterTypes.size == 1 &&
                        java.util.Map::class.java.isAssignableFrom(mm.parameterTypes[0])
                }
                if (m == null) {
                    module.info("ip: space rest params $cn.addCommonParam not found")
                    continue
                }
                runCatching { m.isAccessible = true }
                runCatching { module.deoptimize(m) }
                module.hook(m)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        runCatching {
                            if (!enabled(module)) return@runCatching
                            val mapObj = chain.getArg(0)
                            if (mapObj is Map<*, *>) {
                                val cur = mapObj["mobi_app"]
                                if (cur != null && cur.toString().contains("android_i")) {
                                    @Suppress("UNCHECKED_CAST")
                                    val raw = mapObj as MutableMap<Any?, Any?>
                                    raw["mobi_app"] = "android"
                                    if (spaceParamFired.compareAndSet(false, true)) {
                                        module.info("ip: space rest params rewritten mobi_app $cur -> android")
                                    }
                                }
                            }
                        }.onFailure { t ->
                            module.info("ip: space rest params rewrite failed: ${t.message}")
                        }
                        result
                    }
                HookProbe.ok(module, "ip.spaceRestParams", "$cn.addCommonParam")
            } catch (t: Throwable) {
                module.info("ip: space rest params $cn unavailable: ${t.message}")
            }
        }
    }

    // ------------------------------------------------------------------ 空间页 UI 定域时间窗

    /** 6.4.0 空间页 UI 定域：AuthorSpaceActivity 打开期间放行 kr1.a.a 改写（窗口制）。
     *  6.4.0 空间 REST 由 kntr 直连 provider，svc 无法定位，只能按页面定位。 */
    private fun installSpaceUiScope(module: XposedModule, cl: ClassLoader) {
        // 6.4.0 实测用户打开的空间页 = LocalAuthorSpaceActivity；AuthorSpaceActivity 为 6.3.0/遗留候选
        val acts = arrayOf(
            "com.bilibili.app.authorspace.local.LocalAuthorSpaceActivity",
            "com.bilibili.app.authorspace.ui.AuthorSpaceActivity",
        )
        for (actName in acts) {
            try {
                val act = Class.forName(actName, false, cl)
                val up = runCatching { act.getDeclaredMethod("onResume") }.getOrNull()
                val down = runCatching { act.getDeclaredMethod("onPause") }.getOrNull()
                hookSpaceActivity(module, actName, act, up, down)
            } catch (t: Throwable) {
                module.info("ip: space ui scope $actName unavailable: ${t.message}")
            }
        }
    }

    private fun hookSpaceActivity(
        module: XposedModule,
        actName: String,
        act: Class<*>,
        up: Method?,
        down: Method?,
    ) {
        try {
            if (up != null) {
                runCatching { up.isAccessible = true }
                runCatching { module.deoptimize(up) }
                module.hook(up)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        runCatching {
                            val host = chain.getThisObject()
                            if (host != null) EnhanceFlags.captureContext(host)
                            if (sUiSpaceUntil == 0L) {
                                module.info("ip: space page open -> identity armed (15s window)")
                            }
                            sUiSpaceUntil = System.currentTimeMillis() + SPACE_UI_WINDOW_MS
                        }
                        chain.proceed()
                    }
                HookProbe.ok(module, "ip.spaceUiScope", "$actName.onResume")
            }
            if (down != null) {
                runCatching { down.isAccessible = true }
                runCatching { module.deoptimize(down) }
                module.hook(down)
                    .setPriority(XposedInterface.PRIORITY_DEFAULT)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        runCatching {
                            if (sUiSpaceUntil != 0L) {
                                module.info("ip: space page close -> identity disarmed")
                            }
                            sUiSpaceUntil = 0L
                        }
                        chain.proceed()
                    }
                HookProbe.ok(module, "ip.spaceUiScopeClose", "$actName.onPause")
            }
        } catch (t: Throwable) {
            module.info("ip: space ui scope $actName hook failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 探针

    /** 空间页传输通道探测：全局 OkHttpClient.newCall 抓 space 相关 URL（一次性/URL）。 */
    private fun installNewCallProbe(module: XposedModule, cl: ClassLoader) {
        try {
            val client = Class.forName("okhttp3.OkHttpClient", false, cl)
            val nc = client.declaredMethods.firstOrNull { mm ->
                mm.name == "newCall" &&
                    mm.parameterTypes.size == 1 &&
                    mm.parameterTypes[0].name == "okhttp3.Request"
            }
            if (nc == null) {
                module.info("ip: OkHttpClient.newCall not found")
                return
            }
            val reqUrl = urlMethodOfClient(client)
            runCatching { nc.isAccessible = true }
            runCatching { module.deoptimize(nc) }
            module.hook(nc)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    runCatching {
                        val req = chain.getArg(0)
                        if (req != null && reqUrl != null) {
                            val u = runCatching { reqUrl.invoke(req) }.getOrNull()
                            val us = u?.toString()
                            if (us != null) {
                                val low = us.lowercase()
                                if (low.contains("space") && rememberUrlProbe(low.take(120))) {
                                    module.info("ip: newcall url probe: ${us.take(160)}")
                                }
                            }
                        }
                    }
                    chain.proceed()
                }
            HookProbe.ok(module, "ip.newCallProbe", "OkHttpClient.newCall")
        } catch (t: Throwable) {
            module.info("ip: newcall probe unavailable: ${t.message}")
        }
    }

    /** 全量 Activity 探针：记录去重类名，定位用户真实打开的页面（cap 40）。 */
    private fun installActivityProbe(module: XposedModule, cl: ClassLoader) {
        try {
            val act = Class.forName("android.app.Activity", false, cl)
            val up = act.getDeclaredMethod("onResume")
            runCatching { up.isAccessible = true }
            runCatching { module.deoptimize(up) }
            module.hook(up)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    runCatching {
                        val thiz = chain.getThisObject()
                        if (thiz != null) {
                            EnhanceFlags.captureContext(thiz)
                            val n = thiz.javaClass.name
                            if (seenActivities.size < 40 && seenActivities.add(n)) {
                                module.info("ip: activity: $n")
                            }
                        }
                    }
                    chain.proceed()
                }
            HookProbe.ok(module, "ip.activityProbe", "Activity.onResume")
        } catch (t: Throwable) {
            module.info("ip: activity probe unavailable: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ 判定与限流

    private fun rememberService(module: XposedModule, svc: String?, pkg: String?) {
        if (svc.isNullOrEmpty()) return
        val key = "$svc|${pkg ?: ""}"
        if (seenServices.size >= 80) return
        if (seenServices.add(key)) {
            module.info("ip: moss svc=$svc pkg=$pkg")
        }
    }

    /** 评论区服务判定（按服务名，覆盖该服务全部方法）。 */
    private fun isReplyService(svc: String?): Boolean {
        if (svc == null) return false
        return svc == REPLY_SERVICE || svc.startsWith("bilibili.main.community.reply")
    }

    /** 空间页服务判定（个人主页 IP 属地；REST 之外 6.4.0 可能走 moss）。 */
    private fun isSpaceService(svc: String?, pkg: String?): Boolean {
        val s = svc?.lowercase()
        val p = pkg?.lowercase()
        if (s != null && (s.startsWith("bilibili.app.space") || s.contains(".space."))) return true
        return p != null && (p.startsWith("bilibili.app.space") || p.contains(".space."))
    }

    /** 主页推荐服务判定（6.4.0 国际版首页 feed；限定武装，不做全局声明）。 */
    private fun isHomeService(svc: String?, pkg: String?): Boolean {
        val s = svc?.lowercase()
        val p = pkg?.lowercase()
        if (s != null && (s.startsWith("bilibili.app.interfaces") || s.contains("pegasus"))) return true
        return p != null && (p.contains("bilibili.app.interfaces") || p.contains("pegasus"))
    }

    private fun isReplyMethod(method: String?): Boolean {
        if (method == null) return false
        return REPLY_METHODS.any { it == method }
    }

    private fun classifyRest(url: String?): String? {
        if (url == null) return null
        return try {
            val lower = url.lowercase()
            if (lower.contains("/x/v2/space")) {
                "profile-rest"
            } else if (REST_COMMENT_PATHS.any { lower.contains(it) }) {
                "comment-rest"
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        }
    }

    private val REST_COMMENT_PATHS = arrayOf(
        "/x/v2/reply", "/x/v2/reply/main", "/x/v2/reply/reply",
        "/x/v2/reply/reply/cursor", "/x/v2/reply/folded",
        "/x/v2/reply/reply/folded", "/x/v2/reply/msg_feed_list",
    )

    /** 改写成功日志：每类只打首条（进程生命周期内）。 */
    private fun logRewrite(module: XposedModule, once: AtomicBoolean, msg: String) {
        if (!once.compareAndSet(false, true)) return
        module.info("[探针] ip: $msg")
    }

    /** 同一问题每次进程只报一次，避免刷屏。 */
    private fun logRewriteOnce(module: XposedModule, key: String, msg: String) {
        if (!loggedHdrTypes.add(key)) return
        module.info(msg)
    }

    // ------------------------------------------------------------------ protobuf 字节改写

    /** 字节流内查找子串，返回下标（无则 -1）。 */
    private fun indexOfBytes(hay: ByteArray, needle: String): Int {
        val n = needle.toByteArray()
        var i = 0
        outer@ while (i <= hay.size - n.size) {
            for (j in n.indices) {
                if (hay[i + j] != n[j]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    /** 在 protobuf 字节流中把 android_i 替换为 android。
     *  protobuf string 字段格式：tag(1) + length(varint) + bytes。
     *  长度变化需重建数组并更新 length 前缀。
     *  返回新数组（未改写则返回 null）。 */
    private fun rewriteMobiAppBytes(src: ByteArray?): ByteArray? {
        if (src == null) return null
        val oldStr = "android_i".toByteArray()
        val newStr = "android".toByteArray() // 统一普通版身份（评论区/主页均生效）
        var foundIdx = -1
        var i = 0
        while (i <= src.size - oldStr.size) {
            var match = true
            for (j in oldStr.indices) {
                if (src[i + j] != oldStr[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                foundIdx = i
                break
            }
            i++
        }
        if (foundIdx < 0) return null
        val lenPos = foundIdx - 1
        if (lenPos < 0) return null
        val oldLen = src[lenPos].toInt() and 0xFF
        if (oldLen != oldStr.size) {
            return null
        }
        val newLen = newStr.size
        val out = ByteArray(src.size + (newLen - oldLen))
        System.arraycopy(src, 0, out, 0, lenPos)
        out[lenPos] = newLen.toByte()
        System.arraycopy(src, lenPos + 1, out, lenPos + 1, foundIdx - (lenPos + 1))
        System.arraycopy(newStr, 0, out, foundIdx, newLen)
        System.arraycopy(src, foundIdx + oldLen, out, foundIdx + newLen, src.size - (foundIdx + oldLen))
        return out
    }

    // ------------------------------------------------------------------ 反射辅助

    private fun loadQuiet(cl: ClassLoader, name: String): Class<*>? {
        return runCatching { Class.forName(name, false, cl) }.getOrNull()
    }

    private fun strField(obj: Any?, name: String): String? {
        if (obj == null) return null
        val v = getField(obj, name) ?: return null
        return runCatching { v.toString() }.getOrNull()
    }

    private fun getField(obj: Any, name: String): Any? {
        return runCatching {
            val f = obj.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.get(obj)
        }.getOrNull()
    }

    /** 调无参方法，按返回类型名过滤歧义重载。 */
    private fun callNoArg(obj: Any?, name: String, retHint: String): Any? {
        if (obj == null) return null
        return try {
            for (mm in obj.javaClass.methods) {
                if (mm.name != name) continue
                if (mm.parameterTypes.isNotEmpty()) continue
                val rt = mm.returnType.name
                if (!rt.contains(retHint)) continue
                mm.isAccessible = true
                return mm.invoke(obj)
            }
            null
        } catch (ignored: Throwable) {
            null
        }
    }

    /** 在类层级里按字段名+类型名找字段值。 */
    private fun fieldTypedInHierarchy(obj: Any?, name: String, typeHint: String?): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                if (typeHint == null || f.type.name.contains(typeHint)) {
                    return f.get(obj)
                }
            } catch (ignored: NoSuchFieldException) {
            } catch (ignored: Throwable) {
            }
            c = c.superclass
        }
        return null
    }

    private fun fieldInHierarchy(obj: Any?, name: String): Any? {
        return fieldTypedInHierarchy(obj, name, null)
    }

    /** 按字段名 + 多个类型名提示（跨版本候选）依次查找字段值。 */
    private fun fieldTypedAnyHint(obj: Any?, name: String, typeHints: Array<String>): Any? {
        for (hint in typeHints) {
            val v = fieldTypedInHierarchy(obj, name, hint)
            if (v != null) return v
        }
        return null
    }

    private fun invokeStatic(cls: Class<*>, name: String, sig: Array<Class<*>>, vararg args: Any?): Any? {
        return try {
            val m = cls.getMethod(name, *sig)
            runCatching { m.isAccessible = true }
            m.invoke(null, *args)
        } catch (t: Throwable) {
            null
        }
    }

    private fun call(obj: Any?, name: String, vararg args: Any?): Any? {
        if (obj == null) return null
        return try {
            val m = obj.javaClass.methods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size
            } ?: return null
            runCatching { m.isAccessible = true }
            m.invoke(obj, *args)
        } catch (t: Throwable) {
            null
        }
    }

    /** okhttp 拦截器 chain 上的 request()。 */
    private fun reqMethodOf(chainCls: Class<*>): Method? {
        return runCatching {
            chainCls.methods.firstOrNull { it.name == "request" && it.parameterTypes.isEmpty() }
        }.getOrNull()
    }

    /** okhttp3 Request.l() -> HttpUrl（混淆名），回落 url()。 */
    private fun urlMethodOfRequest(reqMethod: Method?): Method? {
        if (reqMethod == null) return null
        return try {
            val reqCls = reqMethod.returnType
            reqCls.methods.firstOrNull { it.name == "l" && it.parameterTypes.isEmpty() }
                ?: reqCls.methods.firstOrNull { it.name == "url" && it.parameterTypes.isEmpty() }
        } catch (t: Throwable) {
            null
        }
    }

    /** OkHttpClient 探针用的 Request -> HttpUrl 方法（真名 Url()/url()）。 */
    private fun urlMethodOfClient(client: Class<*>): Method? {
        return try {
            val loader = client.classLoader ?: return null
            val req = loader.loadClass("okhttp3.Request")
            val hu = loader.loadClass("okhttp3.HttpUrl")
            req.methods.firstOrNull {
                it.parameterTypes.isEmpty() && it.returnType == hu &&
                    (it.name == "Url" || it.name == "url")
            }?.apply { isAccessible = true }
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun urlString(urlMethod: Method?, req: Any?): String? {
        if (urlMethod == null || req == null) return null
        return try {
            urlMethod.invoke(req)?.toString()
        } catch (t: Throwable) {
            null
        }
    }
}

package com.ctf.bilisb.unlock

import android.os.Handler
import android.os.HandlerThread
import com.ctf.bilisb.host.HookProbe
import com.ctf.bilisb.host.HostTargets
import com.ctf.bilisb.util.warn
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * `PlayerMoss.playViewUnite` 的解锁钩（U4 最小闭环，见 docs/UNLOCK_PLAN.md）。
 *
 * ## 响应形态
 * 宿主实机（6.6.0）的 `playViewUnite` 是 **(req, 回调) 双参形态**：响应经第二参的
 * 回调/continuation 回来，`proceed()` 的返回值不可用（U1 实测 usable=false）。
 * 因此响应拦截走 **包装第二参** 的路线：`Proxy` 挂上其全部接口，拦
 * `resumeWith`/`onNext`/`onCompleted` 三种响应携带方法，对 reply 应用变换后转发。
 * 1 参的 `executePlayViewUnite`（若有同步返回）走返回值替换路径。
 *
 * ## 变换语义
 * 受限判定（[PlayViewDecision]）为 RESTRICTED/THAI_REDIRECT 且开关开启时：
 * req 七参数 → [RoamingClient]（HostSigner 按区域本地签名）→
 * 经典 playurl JSON → [PlayurlParser] → [ResponseReconstructor] 用宿主类重建 reply。
 * **任何失败退化为放行原响应**（宁可不解锁不黑屏），探针留名。
 *
 * 网络与重建的实现在 [RoamingUnlockCore]（传输无关核心，P0 抽出）——本文件是 MOSS 侧适配器。
 * 开关与服务器配置来自设置镜像的 `unlock_*` 键（[UnlockConfig]，U7 并入设置管线），
 * **默认关闭**；当前实现与验证范围见 docs/UNLOCK_PLAN.md。
 */
object PlayViewHook {

    private const val MAX_RETRY = 30
    private const val RETRY_DELAY_MS = 1000L

    private val attempts = AtomicInteger(0)

    /**
     * 解锁网络/重构的专用单线程池：playview 的响应回调跑在主线程
     * （U4 实测 NetworkOnMainThreadException），网络必须挪到工作线程；
     * 回调线程限时等待（3s，D1：8s 曾伴随疑似 ANR 的进程重启），超时放行原响应
     * 并弹失败 Toast——不黑屏优先。
     */
    private val unlockExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "BiliSB-UnlockNet").apply { isDaemon = true }
        }
    }

    /** 延迟重试用的自建后台线程：重试做类加载 + 方法扫描，不压主线程。 */
    private val retryHandler: Handler by lazy {
        val thread = HandlerThread("BiliSB-UnlockRetry")
        thread.isDaemon = true
        thread.start()
        Handler(thread.looper)
    }

    fun install(module: XposedModule, cl: ClassLoader) {
        tryInstall(module, cl)
    }

    private fun tryInstall(module: XposedModule, cl: ClassLoader) {
        if (attempts.incrementAndGet() > MAX_RETRY) {
            HookProbe.miss(module, "unlock:playViewUnite", "give up after $MAX_RETRY attempts")
            return
        }
        try {
            installAccessKeyCapture(module, cl)
            val moss = Class.forName(HostTargets.PLAYER_MOSS_CLASS, false, cl)
            val hooked = mutableListOf<String>()
            for (name in HostTargets.PLAY_VIEW_UNITE_METHODS) {
                for (m in moss.declaredMethods.filter {
                    it.name == name && it.parameterTypes.isNotEmpty()
                }) {
                    runCatching { m.isAccessible = true }
                    runCatching { module.deoptimize(m) }
                    hookOne(module, cl, m)
                    hooked += "${m.name}(${m.parameterTypes.size} args)"
                }
            }
            if (hooked.isEmpty()) {
                HookProbe.first(module, "unlock:playViewUniteRetry", 3) {
                    "method not found yet, retry attempt=${attempts.get()}"
                }
                retry(module, cl)
                return
            }
            HookProbe.ok(module, "unlock:playViewUnite", hooked.joinToString(", "))
            dumpFieldNumbers(module, cl)
        } catch (e: ClassNotFoundException) {
            HookProbe.first(module, "unlock:playViewUniteRetry", 3) {
                "class not loaded yet, retry in ${RETRY_DELAY_MS}ms attempt=${attempts.get()}"
            }
            retry(module, cl)
        } catch (t: Throwable) {
            HookProbe.miss(module, "unlock:playViewUnite", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** U2 校准：Stream 族的字段号运行时实测（手工 wire 字节的字段号以此为准）。 */
    private fun dumpFieldNumbers(module: XposedModule, cl: ClassLoader) {
        runCatching {
            for (name in listOf(
                "com.bapis.bilibili.playershared.Stream",
                "com.bapis.bilibili.playershared.DashVideo",
                "com.bapis.bilibili.playershared.DashItem",
                "com.bapis.bilibili.playershared.VodInfo",
            )) {
                val cls = runCatching { Class.forName(name, true, cl) }.getOrNull() ?: continue
                val dump = cls.declaredFields
                    .filter { it.name.endsWith("_FIELD_NUMBER") }
                    .sortedBy { it.name }
                    .joinToString(", ") { f ->
                        "${f.name.removeSuffix("_FIELD_NUMBER").lowercase()}=" +
                            runCatching { f.get(null) }.getOrDefault("?")
                    }
                HookProbe.first(module, "unlock:fieldNumbers:${name.substringAfterLast('.')}", 1) { dump }
            }
        }
    }

    /**
     * U4.7：宿主 access_key 捕获钩——hook gripper 账户门面的 getAccessKey()（after），
     * 返回值即宿主当前账户的令牌。App 任何鉴权请求都会经过它，播放页前必然已触发。
     * 与 addCommonParam 被动捕获互补（那条路在播放页路径不经过）。
     */
    private fun installAccessKeyCapture(module: XposedModule, cl: ClassLoader) {
        if (akCaptureInstalled.getAndSet(true)) return
        try {
            val cls = Class.forName(HostTargets.GRIPPER_ACCOUNT_CLASS, false, cl)
            val m = cls.declaredMethods.firstOrNull {
                it.name == HostTargets.GRIPPER_GET_ACCESS_KEY && it.parameterTypes.isEmpty()
            } ?: run {
                HookProbe.miss(module, "unlock:akCapture", "getAccessKey not found")
                return
            }
            runCatching { m.isAccessible = true }
            runCatching { module.deoptimize(m) }
            module.hook(m)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    runCatching {
                        (result as? String)?.takeIf { it.isNotEmpty() }?.let {
                            com.ctf.bilisb.unlock.HostAccessKey.capture(it)
                            HookProbe.first(module, "unlock:akCapture", 1) {
                                "len=${it.length} hex32=${it.length == 32 && it.all { c -> c.isDigit() || c in 'a'..'f' }} head4=${it.take(4)}"
                            }
                            // 诊断：令牌落盘（root-only 目录，供真实链路验证）
                            runCatching {
                                val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
                                dir.mkdirs()
                                java.io.File(dir, "access_key_runtime.txt")
                                    .writeText(it)
                            }
                        }
                    }
                    result
                }
            HookProbe.ok(module, "unlock:akCapture", "getAccessKey")
        } catch (t: Throwable) {
            HookProbe.miss(module, "unlock:akCapture", "install threw: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private val akCaptureInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun retry(module: XposedModule, cl: ClassLoader) {
        runCatching {
            retryHandler.postDelayed({ tryInstall(module, cl) }, RETRY_DELAY_MS)
        }.onFailure { t -> module.warn("unlock: retry scheduling failed: ${t.message}") }
    }

    private fun hookOne(module: XposedModule, cl: ClassLoader, m: Method) {
        module.hook(m)
            .setPriority(XposedInterface.PRIORITY_DEFAULT)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val req = chain.args.getOrNull(0)
                val reqFacts = extractRequestFacts(module, req)
                HookProbe.first(module, "unlock:reqFacts", 5) {
                    "${m.name}: cid=${reqFacts.vodCid} season=${reqFacts.seasonId} " +
                        "ep=${reqFacts.epId} download=${reqFacts.isDownload}"
                }
                val config = UnlockConfig.load(module)

                // U6 缓存解锁：请求补参（fnval 拉满 + fourk + download=0），wire bytes 往返
                val effectiveReq = if (config.enabled && config.cacheUnlock && reqFacts.isDownload && req != null) {
                    patchRequestForCache(module, cl, req)
                } else {
                    req
                }

                // 代理使用实际发送的参数，同时保留原请求“下载”语义用于判定。
                val effectiveFacts = if (effectiveReq !== req) {
                    extractRequestFacts(module, effectiveReq).copy(isDownload = reqFacts.isDownload)
                } else reqFacts
                val transformer = ResponseTransformer(module, cl, effectiveReq, effectiveFacts)

                // 双参形态（req, 回调）：包装回调，在回调里对 reply 做变换
                if (chain.args.size >= 2 && chain.args[1] != null) {
                    val handler = chain.args[1]
                    val wrapped = Proxy.newProxyInstance(
                        cl,
                        handler.javaClass.interfaces,
                        { _, method, args ->
                            if (args != null && args.isNotEmpty() && isResponseCarrier(method.name)) {
                                args[0] = transformer.apply(method.name, args[0])
                            }
                            method.invoke(handler, *(args ?: emptyArray()))
                        },
                    )
                    val result = chain.proceed(arrayOf(effectiveReq, wrapped))
                    if (result == null || isSuspendedMarker(result)) return@intercept null
                    return@intercept transformer.apply("return", result)
                }

                // 同步形态：proceed 后对返回值做变换。
                // 宿主 `PlayerMoss.executePlayViewUnite(req)` 同步返回 PlayViewUniteReply 且声明抛
                // MossException —— 受限内容上这一步可能直接抛（拿不到 reply 就没法做解锁变换），
                // 所以这里必须把「返回 null / 抛异常」显式记下来，否则下载路径静默失效。
                val syncReply = try {
                    chain.proceed(arrayOf(effectiveReq))
                } catch (failure: Throwable) {
                    HookProbe.first(module, "unlock:syncFail", 5) {
                        "download=${reqFacts.isDownload} ep=${reqFacts.epId} " +
                            "${failure.javaClass.simpleName}: ${failure.message}"
                    }
                    // 宿主取地址直接抛异常（下载引擎路径）——先按「无原始响应」漫游重建：
                    // 重建成功即替代该异常，下载才可能继续；否则维持宿主原语义抛回，不改变非解锁场景。
                    val rebuilt = transformer.rebuildAfterHostFailure()
                    if (rebuilt != null) return@intercept rebuilt
                    throw failure
                }
                HookProbe.first(module, "unlock:syncReturn", 5) {
                    "download=${reqFacts.isDownload} ep=${reqFacts.epId} " +
                        "reply=${syncReply?.javaClass?.simpleName ?: "null"}"
                }
                transformer.apply("return", syncReply)
            }
    }

    // ---- U4.5：真实 CDN 流地址提取（reply.vodInfo.streamList[].dashVideo.baseUrl）----

    private val realStreamsLogged = AtomicInteger(0)

    private fun captureRealStreams(module: XposedModule, cl: ClassLoader, reply: Any) {
        if (realStreamsLogged.get() >= 2) return
        runCatching {
            val vodInfo = reply.javaClass.methods.firstOrNull { f -> f.name == "getVodInfo" }
                ?.invoke(reply) ?: return
            val streams = vodInfo.javaClass.methods.firstOrNull { f -> f.name == "getStreamListList" }
                ?.invoke(vodInfo) as? List<*> ?: return
            val urls = streams.mapNotNull { stream ->
                val dv = stream?.javaClass?.methods?.firstOrNull { f -> f.name == "getDashVideo" }
                    ?.invoke(stream) ?: return@mapNotNull null
                dv.javaClass.methods.firstOrNull { f -> f.name == "getBaseUrl" }
                    ?.invoke(dv) as? String
            }.filter { it.isNotEmpty() }
            if (urls.isEmpty()) return
            urls.forEachIndexed { idx, u ->
                HookProbe.first(module, "unlock:realUrl:$idx", 1) { u }
            }
            realStreamsLogged.incrementAndGet()
        }
    }

    // ---- 样本采集：受限/强制路径的原始 reply 落盘（每判定限 1 份，U2 管线回归素材）----

    private val capturedRestricted = AtomicInteger(0)

    private fun captureReplyOnce(module: XposedModule, reply: Any, verdictName: String) {
        // 诊断：前 4 个 PGC 响应全部落盘（定位受限形态的真实字节位置）
        val n = capturedRestricted.incrementAndGet()
        if (n > 4) return
        runCatching {
            val bytes = reply.javaClass.methods.firstOrNull { f -> f.name == "toByteArray" }
                ?.invoke(reply) as? ByteArray ?: return
            val dir = java.io.File(HostTargets.HOST_DATA_DIRS.first(), "unlock_capture")
            dir.mkdirs()
            val f = java.io.File(dir, "pgc_reply_$n.bin")
            f.outputStream().use { it.write(bytes) }
            HookProbe.first(module, "unlock:capture:pgc", 4) {
                "${f.absolutePath} ${bytes.size}B verdict=$verdictName"
            }
        }
    }

    /**
     * U6 缓存解锁：req 补参（wire bytes 往返——自备 schema 补参后由宿主类 parseFrom，
     * 与 ResponseReconstructor 同款技术）。fnval 拉满 + fourk + download=0。
     */
    // Note: 下载失败回退与搜索隔离的取舍见 .agents/notes/implemented/bug-fix/2026-10-05-unlock-boundaries.md
    private fun patchRequestForCache(module: XposedModule, cl: ClassLoader, req: Any): Any =
        CacheRequestPatch.apply(
            request = req,
            serialize = { it.javaClass.getMethod("toByteArray").invoke(it) as ByteArray },
            parse = { bytes ->
                cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REQ_CLASS)
                    .getMethod("parseFrom", ByteArray::class.java).invoke(null, bytes)
                    .also { HookProbe.first(module, "unlock:reqPatched", 3) { "download=0; capabilities merged" } }
            },
            onFailure = { failure ->
                HookProbe.first(module, "unlock:patchFailed", 3) {
                    "${failure.javaClass.simpleName}; original request preserved"
                }
            },
        )

    /** 回调接口里携带响应的方法名（moss KCall 与 Kotlin Continuation 两种家族）。 */
    private fun isResponseCarrier(name: String): Boolean =
        name == "onNext" || name == "onCompleted" || name == "resumeWith" || name == "onSuccess"

    /** COROUTINE_SUSPENDED 标记（类名比对，避免依赖 kotlin.coroutines 运行时符号）。 */
    private fun isSuspendedMarker(result: Any): Boolean =
        result.javaClass == Any::class.java && result.toString() == "COROUTINE_SUSPENDED"

    /**
     * 响应变换器：对一次 playViewUnite 调用的 reply 应用解锁变换。
     * 每次调用新建（轻量）；只在命中受限且开关开启时做网络与重构。
     */
    private class ResponseTransformer(
        private val module: XposedModule,
        private val cl: ClassLoader,
        private val req: Any?,
        private val reqFacts: ExtractedRequestFacts,
    ) {
        fun apply(via: String, reply: Any?): Any? {
            if (reply == null) return reply
            val respFacts = extractResponseFacts(module, cl, reply)
            val effectiveEpId = reqFacts.epId.toLongOrNull()?.takeIf { it != 0L }
                ?: respFacts.supplementEpId
            val facts = PlayViewDecision.Facts(
                reqVodCid = reqFacts.vodCid,
                seasonId = reqFacts.seasonId,
                epId = effectiveEpId.toString(),
                isDownload = reqFacts.isDownload,
                respUsable = respFacts.usable,
                respPlayArcCid = respFacts.respCid,
                supplementTypeUrl = respFacts.typeUrl,
                supplementDialogType = respFacts.supplementDialogType,
                supplementEndPageDialogType = respFacts.supplementEndPageDialogType,
                isPreview = respFacts.isPreview,
            )
            val verdict = PlayViewDecision.classify(facts)
            HookProbe.first(module, "unlock:verdict:${verdict.name}", 5) {
                "via=$via cid=${facts.reqVodCid} respCid=${facts.respPlayArcCid} " +
                    "ep=${facts.epId} dialog=${facts.supplementDialogType} " +
                    "endDlg=${facts.supplementEndPageDialogType} prev=${facts.isPreview} " +
                    "typeUrl=${facts.supplementTypeUrl ?: "null"} usable=${facts.respUsable}"
            }
            val config = UnlockConfig.load(module)
            // 开发专用强制路径：命中的 ep_id == unlock_test_epid 的正常 PGC 请求
            // 被强制按受限处理——没有已知受限样本时验证闭环用（U7 评估去留）
            val forcedTest = config.testEpId != 0L &&
                verdict == PlayViewDecision.Verdict.NORMAL_PGC &&
                facts.epId.toLongOrNull() == config.testEpId
            // U4.7 诊断：所有 PGC 响应落盘（定位受限形态的真实字节位置——
            // 国际网关的受限信号不在 view_info.dialog，需字节级定位）
            if (verdict == PlayViewDecision.Verdict.NORMAL_PGC) {
                runCatching { captureRealStreams(module, cl, reply) }
                captureReplyOnce(module, reply, "NORMAL_PGC")
            }
            if (verdict != PlayViewDecision.Verdict.RESTRICTED &&
                verdict != PlayViewDecision.Verdict.THAI_REDIRECT && !forcedTest
            ) {
                runCatching { captureRealStreams(module, cl, reply) }
                return reply
            }
            if (forcedTest) {
                HookProbe.first(module, "unlock:forceTest", 3) { "ep=${facts.epId} 强制受限路径（开发验证）" }
            }
            captureReplyOnce(module, reply, verdict.name)

            if (!config.enabled || config.servers.isEmpty()) {
                HookProbe.first(module, "unlock:skippedOff", 3) { "受限但解锁未启用/未配置服务器" }
                return reply
            }
            // U4.7：access_key 解析——配置覆盖优先，否则用宿主运行时捕获的令牌
            // （HostAccessKey 经 addCommonParam 被动捕获，等价参考实现读宿主账户管理器）
            val configAccessKey = config.servers[0].accessKey
            val accessKey = configAccessKey.ifBlank { HostAccessKey.lastSeen() }
            if (accessKey.isNullOrBlank()) {
                HookProbe.first(module, "unlock:noAccessKey", 3) {
                    "宿主令牌尚未捕获（REST 请求还没发生过）且未配置 access_key"
                }
                return reply
            }
            HookProbe.first(module, "unlock:ak", 2) {
                "access_key len=${accessKey.length} hex32=${accessKey.length == 32 && accessKey.all { c -> c.isDigit() || c in 'a'..'f' }} head4=${accessKey.take(4)}"
            }

            // req 七参数与「cid 缺省借响应 playArc」的逻辑都在 RoamingUnlockCore 里，
            // 适配器只把 ep/cid 补齐后交事实过去（下面 roamAndRebuild 内）。
            return roamAndRebuild(reply, respFacts.respCid, respFacts.supplementEpId) ?: reply
        }

        /**
         * 漫游取播放地址 → 重建响应。受限判定路径与「宿主自己抛异常」路径共用同一实现。
         *
         * 网络与重建本身已抽到 [RoamingUnlockCore]（P0）；这里只剩适配器职责：事实拼装、
         * 3s 限时、探针、失败 Toast。下面的 access_key 检查**刻意保留**（核心内另有一份）：
         * 无令牌时这里直接返回 null，不走「失败文案 + Toast」那条路 —— 下载取地址的兜底
         * 路径原本就是静默放弃。
         *
         * @param hostReply 原响应。**null = 宿主直接抛了异常，没有响应可变换**——下载引擎取地址
         *   走的就是这条（2026-10-05 真机实证：`executePlayViewUnite` 抛
         *   `BusinessException: 抱歉您所在地区不可观看！`）。此时重建用宿主 `newBuilder()`
         *   从零构造，只带漫游流与空的 PGC 载荷。
         * @param fallbackCid 原响应缺失时定位内容的 cid（取请求侧 vodCid）。
         * @param supplementEpId 原响应里的 ep_id（请求没带 ep 时用它补）。
         */
        private fun roamAndRebuild(hostReply: Any?, fallbackCid: Long, supplementEpId: Long): Any? {
            val config = UnlockConfig.load(module)
            val configAccessKey = config.servers[0].accessKey
            val accessKey = configAccessKey.ifBlank { HostAccessKey.lastSeen() }
            if (accessKey.isNullOrBlank()) {
                HookProbe.first(module, "unlock:noAccessKey", 3) {
                    "宿主令牌尚未捕获（REST 请求还没发生过）且未配置 access_key"
                }
                return null
            }
            HookProbe.first(module, "unlock:ak", 2) {
                "access_key len=${accessKey.length} " +
                    "hex32=${accessKey.length == 32 && accessKey.all { c -> c.isDigit() || c in 'a'..'f' }} " +
                    "head4=${accessKey.take(4)}"
            }

            // req 七参数：cid 缺省时借响应 playArc（重定向场景 respCid 即目标 cid）
            val epId = reqFacts.epId.toLongOrNull()?.takeIf { it != 0L } ?: supplementEpId
            val cid = reqFacts.vodCid.takeIf { it != 0L } ?: fallbackCid
            // 核心只要事实本身：查询串、网络、解析、重建全在 RoamingUnlockCore
            val facts = RoamingUnlockCore.UnlockRequestFacts(
                epId = epId,
                cid = cid,
                seasonId = reqFacts.seasonId,
                qn = reqFacts.qn.toInt(),
                fnver = reqFacts.fnver,
                fnval = reqFacts.fnval,
                forceHost = reqFacts.forceHost,
                fourk = reqFacts.fourk,
                isDownload = reqFacts.isDownload,
            )
            var failReason = ""
            val rebuilt = runCatching {
                // 网络+重构限时执行；等待超时后取消未完成任务。
                val task = java.util.concurrent.Callable {
                    val result = RoamingUnlockCore.roam(config, facts, module)
                    if (!result.isSuccess) {
                        // 失败原因文案留在适配器：它同时决定 Toast 文案
                        failReason = "解析服务器不可用"
                        return@Callable null
                    }
                    val data = RoamingUnlockCore.parsePlayurl(config, result.content!!, module)
                    if (data == null) {
                        failReason = "漫游响应解析失败"
                        return@Callable null
                    }
                    val inner = runCatching {
                        if (config.passthrough && hostReply != null) {
                            // U4.6 纯重序列化测试：newBuilder(reply).build() 零修改。
                            // 这一步吃的是宿主对象本身（不是字节），属纯适配器职责，留在这一侧。
                            val replyCls = cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REPLY_CLASS)
                            val b = replyCls.getMethod("newBuilder", replyCls).invoke(null, hostReply)
                            val reserialized = b.javaClass.getMethod("build").invoke(b)
                            val n1 = (hostReply.javaClass.getMethod("toByteArray").invoke(hostReply) as ByteArray).size
                            val n2 = (reserialized.javaClass.getMethod("toByteArray")
                                .invoke(reserialized) as ByteArray).size
                            HookProbe.first(module, "unlock:reserialized", 2) { "原 ${n1}B -> 重序列化 ${n2}B" }
                            return@Callable reserialized
                        }
                        // 重建走核心（宿主字节进、宿主字节出），再 parseFrom 回宿主实例：
                        // MOSS 的回调链要求交回的是宿主自己的 PlayViewUniteReply 类型。
                        val hostReplyBytes = hostReply?.let {
                            it.javaClass.getMethod("toByteArray").invoke(it) as ByteArray
                        }
                        val rebuiltBytes =
                            RoamingUnlockCore.rebuildReplyBytes(cl, hostReplyBytes, data, cid, 0L)
                        cl.loadClass(HostTargets.PLAY_VIEW_UNITE_REPLY_CLASS)
                            .getMethod("parseFrom", ByteArray::class.java)
                            .invoke(null, rebuiltBytes)
                    }.onFailure { t ->
                        failReason = "响应重建失败"
                        HookProbe.first(module, "unlock:rebuildFailed", 5) {
                            // 反射包装类(InvocationTargetException 等)展开到根因
                            var root = t
                            while (root.cause != null) root = root.cause!!
                            "${t.javaClass.simpleName}: ${t.message} <- " +
                                "${root.javaClass.simpleName}: ${root.message} " +
                                "at ${root.stackTrace.firstOrNull()?.let { "${it.className}#${it.methodName}" }}"
                        }
                    }.getOrNull()
                    if (inner != null) {
                        HookProbe.first(module, "unlock:proxied", 5) {
                            "area=${result.areaUsed} quality=${data.quality} streams=${data.videos.size} " +
                                "audio=${data.audios.size} cid=$cid ep=${facts.epId} " +
                                "from=${if (hostReply == null) "hostFail" else "restricted"}"
                        }
                        UnlockConfig.rememberArea(result.areaUsed)
                    }
                    inner
                }
                BoundedCall.await(unlockExecutor, 3000) { task.call() }
            }.onFailure { t ->
                failReason = "处理超时"
                HookProbe.first(module, "unlock:transformTimeout", 5) {
                    "${t.javaClass.simpleName}: ${t.message}"
                }
            }.getOrNull()
            // 有原响应时才提示「已放行原片」；无原响应的路径失败会把异常抛回宿主，语义不同。
            if (rebuilt == null && hostReply != null) showFailToast(module, failReason)
            return rebuilt
        }

        /**
         * 宿主取地址**直接抛异常**时的兜底（下载引擎路径）。
         * 适用时返回漫游重建的响应（宿主异常被替代，下载得以继续）；不适用返回 null，
         * 由调用方把原异常抛回，保持宿主原本行为。
         */
        fun rebuildAfterHostFailure(): Any? {
            if (!reqFacts.isDownload) {
                HookProbe.first(module, "unlock:hostFailSkipped", 3) { "非下载请求，保留宿主异常" }
                return null
            }
            val config = UnlockConfig.load(module)
            if (!config.enabled || config.servers.isEmpty()) {
                HookProbe.first(module, "unlock:hostFailOff", 3) { "宿主取地址失败且解锁未启用/未配服务器" }
                return null
            }
            // 下载请求只带 cid（season/ep 都是 0），而 PGC 播放接口只认 ep_id ——
            // 用本季剧集缓存（ViewTabHook 的 episodesBySeasonId）按 cid 反查补上。
            val epId = reqFacts.epId.toLongOrNull()?.takeIf { it != 0L }
                ?: ViewTabHook.epIdForCid(reqFacts.vodCid)
            if (epId == null && reqFacts.vodCid == 0L) {
                HookProbe.first(module, "unlock:hostFailNoId", 3) { "既无 cid 也无 ep，无法漫游" }
                return null
            }
            HookProbe.first(module, "unlock:hostFailEp", 3) {
                "cid=${reqFacts.vodCid} ep=${epId ?: 0L} resolved=${epId != null}"
            }
            return roamAndRebuild(null, reqFacts.vodCid, epId ?: 0L)
        }
    }

    private val mainHandler by lazy { Handler(android.os.Looper.getMainLooper()) }

    /** 同一窗口只弹一次（重试风暴下不刷屏）。 */
    private val lastFailToastAt = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 漫游失败的用户可见提示（D1）：宿主 Application Context + 主线程 Toast。
     * 30s 节流——超时/失败后的宿主自动重试会反复走到这里。
     */
    private fun showFailToast(module: XposedModule, reason: String) {
        if (reason.isEmpty()) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastFailToastAt.get() < 30_000) return
        lastFailToastAt.set(now)
        runCatching {
            val context = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? android.content.Context
            if (context == null) {
                HookProbe.first(module, "unlock:failToastNoCtx", 2) { "拿不到宿主 Context" }
                return
            }
            val text = com.ctf.bilisb.ui.ModuleStrings.get(
                context,
                com.ctf.bilisb.R.string.toast_unlock_failed,
                reason,
                fallback = "解锁失败：$reason（已放行原片）",
            )
            mainHandler.post {
                runCatching {
                    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_LONG).show()
                    // 真 D1 验证走这条：截图时机不可控，logcat 探针才是硬证据
                    HookProbe.first(module, "unlock:failToastShown", 2) { text }
                }
            }
        }.onFailure { t ->
            HookProbe.first(module, "unlock:failToastError", 2) { "${t.javaClass.simpleName}: ${t.message}" }
        }
    }

    /** 生产 HTTP 传输：GET + gzip + 超时（unlock 线各网络路径共用）。 */
    internal fun defaultFetch(url: String, mobiApp: String, timeoutMs: Int = 8000): String {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.setRequestProperty("Accept-Encoding", "gzip")
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 BiliDroid/$mobiApp")
        try {
            val stream = try {
                conn.inputStream
            } catch (e: java.io.IOException) {
                conn.errorStream ?: throw e
            }
            val body = (if (conn.contentEncoding == "gzip") java.util.zip.GZIPInputStream(stream) else stream)
                .bufferedReader().use { it.readText() }
            if (Thread.currentThread().isInterrupted) throw InterruptedException("unlock request cancelled")
            return body
        } finally {
            conn.disconnect()
        }
    }

    // ---- 事实提取（ResponseTransformer 复用）----

    data class ExtractedRequestFacts(
        val vodCid: Long,
        val qn: Long,
        val fnver: Int,
        val fnval: Int,
        val forceHost: Int,
        val fourk: Boolean,
        val seasonId: String,
        val epId: String,
        val isDownload: Boolean,
    )

    data class ExtractedResponseFacts(
        val usable: Boolean,
        val respCid: Long,
        val typeUrl: String?,
        /** supplement 内 episodeInfo.epId（番剧请求 ep_id 缺席时的权威来源）。 */
        val supplementEpId: Long,
        /** supplement.view_info.dialog.type（"area_limit" = 国际网关受限信号）。 */
        val supplementDialogType: String,
        /** supplement.view_info.end_page.dialog.type（片尾页形态受限信号，BiliRoaming G0.g 同款）。 */
        val supplementEndPageDialogType: String,
        /** supplement.business.is_preview（预览形态，BiliRoaming 同款触发漫游）。 */
        val isPreview: Boolean,
    )

    private fun extractRequestFacts(module: XposedModule, req: Any?): ExtractedRequestFacts {
        if (req == null) return ExtractedRequestFacts(0, 0, 0, 0, 0, false, "0", "0", false)
        var vodCid = 0L
        var qn = 0L
        var fnver = 0
        var fnval = 0
        var forceHost = 0
        var fourk = false
        var seasonId = "0"
        var epId = "0"
        var isDownload = false
        runCatching {
            req.javaClass.methods.firstOrNull { it.name == "getVod" }?.invoke(req)?.let { vod ->
                fun num(name: String): Number? =
                    vod.javaClass.methods.firstOrNull { it.name == name }?.invoke(vod) as? Number
                fun bool(name: String): Boolean =
                    vod.javaClass.methods.firstOrNull { it.name == name }?.invoke(vod) as? Boolean ?: false
                vodCid = num("getCid")?.toLong() ?: 0L
                qn = num("getQn")?.toLong() ?: 0L
                fnver = num("getFnver")?.toInt() ?: 0
                fnval = num("getFnval")?.toInt() ?: 0
                forceHost = num("getForceHost")?.toInt() ?: 0
                fourk = bool("getFourk")
                isDownload = (num("getDownload")?.toInt() ?: 0) >= 1
            }
            val extra = req.javaClass.methods.firstOrNull { it.name == "getExtraContentMap" }?.invoke(req)
            @Suppress("UNCHECKED_CAST")
            (extra as? Map<String, String>)?.let { map ->
                seasonId = map.getOrDefault("season_id", "0")
                epId = map.getOrDefault("ep_id", "0")
            }
        }.onFailure { t ->
            HookProbe.first(module, "unlock:extractFailed", 3) { "req: ${t.javaClass.simpleName}: ${t.message}" }
        }
        return ExtractedRequestFacts(vodCid, qn, fnver, fnval, forceHost, fourk, seasonId, epId, isDownload)
    }

    private fun extractResponseFacts(module: XposedModule, cl: ClassLoader, result: Any?): ExtractedResponseFacts {
        var usable = result != null
        var respCid = 0L
        var typeUrl: String? = null
        var supplementEpId = 0L
        var supplementDialogType = ""
        var supplementEndPageDialogType = ""
        var isPreview = false
        runCatching {
            result?.let { res ->
                val hasVod = res.javaClass.methods.firstOrNull { it.name == "hasVodInfo" }
                    ?.invoke(res) as? Boolean
                if (hasVod == false) return ExtractedResponseFacts(false, 0L, null, 0L, "", "", false)
                val playArc = res.javaClass.methods.firstOrNull { it.name == "getPlayArc" }?.invoke(res)
                playArc?.let {
                    respCid = (it.javaClass.methods.firstOrNull { f -> f.name == "getCid" }?.invoke(it) as? Number)?.toLong() ?: 0L
                }
                val supplement = res.javaClass.methods.firstOrNull { it.name == "getSupplement" }?.invoke(res)
                typeUrl = supplement?.javaClass?.methods?.firstOrNull { f -> f.name == "getTypeUrl" }?.invoke(supplement) as? String
                // supplement（PGC Any）里的 episodeInfo.epId：番剧请求 ep_id 缺席时的
                // 权威来源（参考实现 reconstructQueryUnite 同款回退链）
                if (typeUrl == PlayViewDecision.PGC_ANY_MODEL_TYPE_URL) {
                    runCatching {
                        val value = supplement?.javaClass?.methods
                            ?.firstOrNull { f -> f.name == "getValue" }?.invoke(supplement)
                        val bytes = value?.javaClass?.getMethod("toByteArray")?.invoke(value) as? ByteArray
                        HookProbe.first(module, "unlock:dlgStep", 2) {
                            "value=${value?.javaClass?.name ?: "null"} bytes=${bytes?.size ?: -1}"
                        }
                        if (bytes != null && bytes.isNotEmpty()) {
                            // dialog/end_page 走自备 schema（真名类链路对受限响应不可靠——
                            // 受限时 supplement 内可能缺宿主类字段）
                            val pgcSelf = com.ctf.bilisb.unlock.proto.PlayViewReply.parseFrom(bytes)
                            supplementDialogType = pgcSelf.viewInfo.dialog.type
                            supplementEndPageDialogType = pgcSelf.viewInfo.endPage.dialog.type
                            isPreview = pgcSelf.business.isPreview
                            HookProbe.first(module, "unlock:dlgStep", 2) {
                                "parse ok dialog=$supplementDialogType endDlg=$supplementEndPageDialogType prev=$isPreview"
                            }
                            val pgc = cl.loadClass(HostTargets.PLAY_VIEW_REPLY_CLASS)
                                .getMethod("parseFrom", ByteArray::class.java)
                                .invoke(null, bytes)
                            val epId = pgc.javaClass.methods.firstOrNull { f -> f.name == "getBusiness" }
                                ?.invoke(pgc)
                                ?.let { biz ->
                                    biz.javaClass.methods.firstOrNull { f -> f.name == "getEpisodeInfo" }
                                        ?.invoke(biz)
                                }
                                ?.let { ep ->
                                    ep.javaClass.methods.firstOrNull { f -> f.name == "getEpId" }
                                        ?.invoke(ep) as? Number
                                }?.toLong() ?: 0L
                            supplementEpId = epId
                        }
                    }.onFailure { t ->
                        HookProbe.first(module, "unlock:dlgFail", 2) {
                            "${t.javaClass.simpleName}: ${t.message}"
                        }
                    }
                }
            }
        }.onFailure { t ->
            HookProbe.first(module, "unlock:extractFailed", 3) { "resp: ${t.javaClass.simpleName}: ${t.message}" }
        }
        return ExtractedResponseFacts(usable, respCid, typeUrl, supplementEpId, supplementDialogType, supplementEndPageDialogType, isPreview)
    }
}
